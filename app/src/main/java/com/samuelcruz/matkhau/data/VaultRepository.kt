package com.samuelcruz.matkhau.data

import android.content.Context
import com.samuelcruz.matkhau.core.crypto.Aead
import com.samuelcruz.matkhau.core.crypto.Bytes
import com.samuelcruz.matkhau.core.crypto.DecryptionFailedException
import com.samuelcruz.matkhau.core.crypto.SealedBox
import com.samuelcruz.matkhau.core.format.FormatException
import com.samuelcruz.matkhau.core.vault.BackupFormat
import com.samuelcruz.matkhau.core.vault.MasterPassword
import com.samuelcruz.matkhau.core.vault.RecoveryKey
import com.samuelcruz.matkhau.core.vault.UnlockPolicy
import com.samuelcruz.matkhau.core.vault.VaultCrypto
import com.samuelcruz.matkhau.core.vault.VaultHeader
import com.samuelcruz.matkhau.core.vault.WrongSecretException
import com.samuelcruz.matkhau.model.BackupPayload
import com.samuelcruz.matkhau.model.Entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class VaultLockedException : Exception("Vault đang khóa")

/** Backup đã giải mã thành công, đang chờ người dùng xác nhận ghi đè. */
class RestoredBackup internal constructor(
    internal val header: VaultHeader,
    internal val vek: ByteArray,
    val entries: List<Entry>,
    val exportedAt: Long,
) {
    fun discard() = Bytes.wipe(vek)
}

/**
 * Lớp trung tâm quản lý vault — giống một "service object" trong Rails.
 *
 * Trạng thái phiên (session): khi mở khóa, VEK và danh sách mục đã giải mã nằm trong RAM.
 * Khi khóa: VEK bị ghi đè bằng 0, danh sách mục bị bỏ.
 *
 * Mọi lời gọi SQLite đều chạy trên [Dispatchers.IO] (hàm suspend), không bao giờ trên main thread.
 *
 * Khóa giữa chừng: mỗi lần [lock] tăng [generation]. Thao tác dài (Argon2id mất 1–3 giây) chụp lại
 * generation lúc bắt đầu và chỉ ghi kết quả (DB + trạng thái) trong [commit] nếu vault chưa bị khóa từ đó;
 * nếu đã bị khóa thì hủy bằng [VaultLockedException]. Nhờ vậy không có chuyện đổi mật khẩu / tạo Recovery
 * Key mới đã ghi xuống DB mà người dùng không biết, hay mở khóa xong sau khi màn hình đã tắt.
 */
class VaultRepository(context: Context) {
    private val db = VaultDatabase(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val writeMutex = Mutex()
    private val sessionLock = Any()

    // Chỉ truy cập trong synchronized(sessionLock)
    private var vek: ByteArray? = null
    private var header: VaultHeader? = null
    private var generation = 0L

    /** null = chưa đọc DB xong (gọi [load] lúc khởi động); true = đã có vault. */
    private val _initialized = MutableStateFlow<Boolean?>(null)
    val initialized: StateFlow<Boolean?> = _initialized.asStateFlow()

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Tăng mỗi lần [lock] (kể cả khi vault vốn đang khóa) — để UI xóa cả trạng thái của các màn hình khi khóa. */
    private val _lockCount = MutableStateFlow(0L)
    val lockCount: StateFlow<Long> = _lockCount.asStateFlow()

    /** Số mục không giải mã được trong lần mở khóa gần nhất (bị hỏng / bị sửa). */
    @Volatile
    var corruptedItemCount: Int = 0
        private set

    // ---------------------------------------------------------------- tạo / mở / khóa

    /** Đọc DB xem đã có vault chưa. Gọi một lần lúc app khởi động. */
    suspend fun load() = withContext(Dispatchers.IO) {
        _initialized.value = db.getMeta(KEY_HEADER) != null
    }

    /**
     * Tạo vault mới và mở khóa luôn. Trả về Recovery Key dạng chữ để hiển thị MỘT lần.
     * Nếu vault bị khóa trong lúc tạo (tắt màn hình), vault vẫn được tạo nhưng ném [VaultLockedException].
     */
    suspend fun create(password: String): String = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val gen = currentGeneration()
            // Không bao giờ ghi đè một vault đang có (khôi phục backup mới được thay vault).
            check(db.getMeta(KEY_HEADER) == null) { "Vault đã tồn tại" }
            val pw = MasterPassword.toBytes(password)
            val created = try {
                VaultCrypto.createVault(pw)
            } finally {
                Bytes.wipe(pw)
            }
            try {
                db.replaceVault(mapOf(KEY_HEADER to created.header.encode()), emptyList())
                _initialized.value = true
                val recoveryText = RecoveryKey.format(created.recoveryKey)
                startSession(created.vek, created.header, emptyList(), corrupted = 0, gen = gen)
                recoveryText
            } catch (e: Throwable) {
                Bytes.wipe(created.vek)
                throw e
            } finally {
                Bytes.wipe(created.recoveryKey)
            }
        }
    }

    /** Ném [WrongSecretException] nếu sai mật khẩu. */
    suspend fun unlockWithPassword(password: String) = withContext(Dispatchers.IO) {
        val gen = currentGeneration()
        val h = loadHeader()
        openSession(openPasswordSlot(h, password), h, gen, keyVerified = true)
    }

    suspend fun unlockWithRecoveryKey(recoveryText: String) = withContext(Dispatchers.IO) {
        val gen = currentGeneration()
        val h = loadHeader()
        val raw = try {
            RecoveryKey.parse(recoveryText)
        } catch (e: FormatException) {
            throw WrongSecretException()
        }
        val key = try {
            VaultCrypto.unlockWithRecoveryKey(h, raw)
        } finally {
            Bytes.wipe(raw)
        }
        openSession(key, h, gen, keyVerified = true)
    }

    /**
     * Dùng sau khi Android Keystore (vân tay) trả lại VEK. Repository nhận quyền sở hữu mảng [key].
     * Blob vân tay gắn với vaultId (AAD) nhưng vẫn áp quy tắc "mọi mục đều lỗi = sai khóa" cho chắc;
     * mật khẩu chính luôn là đường dự phòng.
     */
    suspend fun unlockWithKey(key: ByteArray, gen: Long) = withContext(Dispatchers.IO) {
        if (key.size != Aead.KEY_LENGTH) {
            Bytes.wipe(key)
            throw WrongSecretException()
        }
        val h = try {
            loadHeader()
        } catch (e: Throwable) {
            Bytes.wipe(key)
            throw e
        }
        openSession(key, h, gen, keyVerified = false)
    }

    /** Khóa ngay. Gọi được cả khi đang khóa: hủy luôn mọi lần mở khóa còn đang chạy Argon2id. */
    fun lock() {
        synchronized(sessionLock) {
            generation++
            _lockCount.value = generation
            Bytes.wipe(vek)
            vek = null
            header = null
            _entries.value = emptyList()
            _unlocked.value = false
        }
    }

    fun isUnlocked(): Boolean = _unlocked.value

    /** Header hiện tại (không chứa bí mật) — để màn hình Security hiển thị tham số. */
    suspend fun headerInfo(): VaultHeader? = withContext(Dispatchers.IO) {
        synchronized(sessionLock) { header } ?: runCatching { loadHeader() }.getOrNull()
    }

    class StorageLayout(val tables: List<String>, val metaKeys: List<String>, val metaColumns: List<String>, val itemColumns: List<String>)

    /** Cho màn hình Security: các bảng, khóa trong meta và cột của hai bảng (kiểm tra thật lúc chạy). */
    suspend fun storageLayout(): StorageLayout = withContext(Dispatchers.IO) {
        StorageLayout(db.tables(), db.metaKeys(), db.columns("meta"), db.columns("items"))
    }

    // ---------------------------------------------------------------- CRUD

    suspend fun save(entry: Entry) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            withSession { s ->
                val plain = json.encodeToString(Entry.serializer(), entry).toByteArray(Charsets.UTF_8)
                val box = try {
                    VaultCrypto.encryptItem(s.key, s.header.vaultId, entry.id, plain)
                } finally {
                    Bytes.wipe(plain)
                }
                commit(s.generation) {
                    db.upsertItem(StoredItem(entry.id, ITEM_FORMAT, box.nonce, box.ciphertext))
                    _entries.value = sorted(_entries.value.filterNot { it.id == entry.id } + entry)
                }
            }
        }
    }

    /** Nhập nhiều mục (từ CSV) trong MỘT transaction; chỉ ghi nếu vault không bị khóa giữa chừng. */
    suspend fun importEntries(list: List<Entry>) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            withSession { s ->
                val existingIds = _entries.value.map { it.id }.toSet()
                require(list.none { it.id in existingIds } && list.map { it.id }.toSet().size == list.size) { "Trùng id" }
                val items = list.map { e ->
                    val plain = json.encodeToString(Entry.serializer(), e).toByteArray(Charsets.UTF_8)
                    try {
                        val box = VaultCrypto.encryptItem(s.key, s.header.vaultId, e.id, plain)
                        StoredItem(e.id, ITEM_FORMAT, box.nonce, box.ciphertext)
                    } finally {
                        Bytes.wipe(plain)
                    }
                }
                commit(s.generation) {
                    db.insertItems(items)
                    _entries.value = sorted(_entries.value + list)
                }
            }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            withSession { s ->
                commit(s.generation) {
                    db.deleteItem(id)
                    _entries.value = _entries.value.filterNot { it.id == id }
                }
            }
        }
    }

    // ---------------------------------------------------------------- mật khẩu & recovery

    suspend fun verifyPassword(password: String): Boolean = withContext(Dispatchers.IO) {
        val h = loadHeader()
        try {
            Bytes.wipe(openPasswordSlot(h, password))
            true
        } catch (e: WrongSecretException) {
            false
        }
    }

    /** Chỉ ghi header mới nếu vault không bị khóa trong lúc chạy Argon2id (nếu bị: không đổi gì cả). */
    suspend fun changePassword(newPassword: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            withSession { s ->
                val pw = MasterPassword.toBytes(newPassword)
                val updated = try {
                    VaultCrypto.changePassword(s.header, s.key, pw)
                } finally {
                    Bytes.wipe(pw)
                }
                commit(s.generation) {
                    db.putMeta(KEY_HEADER, updated.encode())
                    header = updated
                }
            }
        }
    }

    /** Key mới chỉ thay key cũ nếu vault vẫn mở, để key vừa tạo chắc chắn được hiển thị cho người dùng. */
    suspend fun regenerateRecoveryKey(): String = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            withSession { s ->
                val (updated, raw) = VaultCrypto.regenerateRecoveryKey(s.header, s.key)
                try {
                    commit(s.generation) {
                        db.putMeta(KEY_HEADER, updated.encode())
                        header = updated
                    }
                    RecoveryKey.format(raw)
                } finally {
                    Bytes.wipe(raw)
                }
            }
        }
    }

    // ---------------------------------------------------------------- backup / restore

    /** Khóa và danh sách mục được chụp cùng lúc: vault bị khóa giữa chừng không thể tạo ra backup rỗng. */
    suspend fun exportBackup(): ByteArray = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            withSession { s ->
                val payload = json.encodeToString(
                    BackupPayload.serializer(),
                    BackupPayload(exportedAt = System.currentTimeMillis(), entries = s.entries),
                ).toByteArray(Charsets.UTF_8)
                try {
                    BackupFormat.create(s.header, s.key, payload)
                } finally {
                    Bytes.wipe(payload)
                }
            }
        }
    }

    /** Bước 2 của restore: kiểm tra định dạng. Ném [FormatException]. */
    fun parseBackup(file: ByteArray): BackupFormat.Parsed = BackupFormat.parse(file)

    /**
     * Bước 3–5: xác thực bằng mật khẩu (hoặc Recovery Key) của BẢN BACKUP, giải mã, kiểm tra toàn vẹn.
     * Ném [WrongSecretException], [DecryptionFailedException] (file bị sửa) hoặc [FormatException].
     */
    suspend fun decryptBackup(parsed: BackupFormat.Parsed, secret: String, isRecoveryKey: Boolean): RestoredBackup =
        withContext(Dispatchers.IO) {
            val key = try {
                if (isRecoveryKey) {
                    val raw = try {
                        RecoveryKey.parse(secret)
                    } catch (e: FormatException) {
                        throw WrongSecretException()
                    }
                    try {
                        VaultCrypto.unlockWithRecoveryKey(parsed.header, raw)
                    } finally {
                        Bytes.wipe(raw)
                    }
                } else {
                    openPasswordSlot(parsed.header, secret)
                }
            } catch (e: OutOfMemoryError) {
                // Tham số Argon2 trong file (tối đa 256 MiB) vẫn có thể quá sức một máy yếu.
                throw FormatException("Máy không đủ bộ nhớ cho tham số Argon2 của file backup này")
            }
            try {
                val plain = BackupFormat.decrypt(parsed, key)
                val payload = try {
                    json.decodeFromString(BackupPayload.serializer(), String(plain, Charsets.UTF_8))
                } catch (e: SerializationException) {
                    throw FormatException("Nội dung backup không hợp lệ")
                } catch (e: IllegalArgumentException) {
                    throw FormatException("Nội dung backup không hợp lệ")
                } finally {
                    Bytes.wipe(plain)
                }
                if (payload.formatVersion != BACKUP_PAYLOAD_VERSION) {
                    throw FormatException("Phiên bản nội dung backup không hỗ trợ: ${payload.formatVersion}")
                }
                val ids = payload.entries.map { it.id }
                if (ids.any { it.isBlank() } || ids.toSet().size != ids.size) {
                    throw FormatException("Backup có mục trùng hoặc thiếu mã định danh")
                }
                // File backup có thể do bản khác tạo: giới hạn lịch sử mật khẩu như khi app tự ghi.
                val entries = payload.entries.map { it.copy(passwordHistory = it.passwordHistory.take(Entry.MAX_HISTORY)) }
                RestoredBackup(parsed.header, key, entries, payload.exportedAt)
            } catch (e: Throwable) {
                Bytes.wipe(key)
                throw e
            }
        }

    /** Bước 6: ghi đè vault hiện tại bằng backup trong MỘT transaction, sau đó khóa lại. */
    suspend fun restore(backup: RestoredBackup) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            try {
                val items = backup.entries.map { e ->
                    val plain = json.encodeToString(Entry.serializer(), e).toByteArray(Charsets.UTF_8)
                    try {
                        val box = VaultCrypto.encryptItem(backup.vek, backup.header.vaultId, e.id, plain)
                        StoredItem(e.id, ITEM_FORMAT, box.nonce, box.ciphertext)
                    } finally {
                        Bytes.wipe(plain)
                    }
                }
                // Meta mới chỉ có header → khóa vân tay cũ (gắn với VEK/vaultId cũ) tự động bị bỏ.
                db.replaceVault(mapOf(KEY_HEADER to backup.header.encode()), items)
            } finally {
                backup.discard()
            }
            lock()
            _initialized.value = true
        }
    }

    // ---------------------------------------------------------------- vân tay

    suspend fun biometricBlob(): ByteArray? = withContext(Dispatchers.IO) { db.getMeta(KEY_BIOMETRIC) }

    suspend fun setBiometricBlob(blob: ByteArray) = withContext(Dispatchers.IO) {
        writeMutex.withLock { db.putMeta(KEY_BIOMETRIC, blob) }
    }

    suspend fun clearBiometricBlob() = withContext(Dispatchers.IO) {
        writeMutex.withLock { db.deleteMeta(KEY_BIOMETRIC) }
    }

    /** vaultId của vault đang lưu (không bí mật) — để gắn blob vân tay với đúng vault (AAD). */
    suspend fun storedVaultId(): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { loadHeader().vaultId }.getOrNull()
    }

    /**
     * Bản sao VEK + vaultId để bọc bằng Keystore, hoặc null nếu vault vừa bị khóa.
     * Người gọi PHẢI wipe khóa sau khi dùng.
     */
    fun copyKeyForBiometric(): Pair<ByteArray, ByteArray>? = synchronized(sessionLock) {
        val k = vek ?: return@synchronized null
        k.copyOf() to header!!.vaultId.copyOf()
    }

    // ---------------------------------------------------------------- nội bộ

    /** Mở slot mật khẩu, thử lần lượt các cách mã hóa của [MasterPassword.unlockCandidates]. */
    private fun openPasswordSlot(h: VaultHeader, password: String): ByteArray {
        val candidates = MasterPassword.unlockCandidates(password)
        try {
            for (pw in candidates) {
                try {
                    return VaultCrypto.unlockWithPassword(h, pw)
                } catch (e: WrongSecretException) {
                    // thử cách mã hóa tiếp theo
                }
            }
            throw WrongSecretException()
        } finally {
            candidates.forEach { Bytes.wipe(it) }
        }
    }

    private class Session(val key: ByteArray, val header: VaultHeader, val generation: Long, val entries: List<Entry>)

    /** Chụp lại trước khi chờ một thao tác dài (vd. hộp thoại vân tay), rồi đưa vào [unlockWithKey]. */
    fun currentGeneration(): Long = synchronized(sessionLock) { generation }

    private fun loadHeader(): VaultHeader =
        VaultHeader.decode(db.getMeta(KEY_HEADER) ?: throw IllegalStateException("Chưa có vault"))

    /** Giải mã mọi mục rồi mở phiên. Nhận quyền sở hữu [key]: lỗi ở đâu cũng wipe. */
    private fun openSession(key: ByteArray, h: VaultHeader, gen: Long, keyVerified: Boolean) {
        try {
            val stored = db.allItems()
            val entries = ArrayList<Entry>(stored.size)
            var corrupted = 0
            for (item in stored) {
                val entry = try {
                    if (item.format != ITEM_FORMAT) throw DecryptionFailedException()
                    val plain = VaultCrypto.decryptItem(key, h.vaultId, item.id, SealedBox(item.nonce, item.ciphertext))
                    try {
                        json.decodeFromString(Entry.serializer(), String(plain, Charsets.UTF_8))
                    } finally {
                        Bytes.wipe(plain)
                    }
                } catch (e: Exception) {
                    null
                }
                if (entry == null || entry.id != item.id) corrupted++ else entries.add(entry)
            }
            // Fail securely (spec 5.9), nhưng không bao giờ từ chối một khóa đã được kiểm chứng (xem UnlockPolicy).
            if (!UnlockPolicy.accept(stored.size, corrupted, keyVerified)) throw WrongSecretException()
            startSession(key, h, entries, corrupted, gen)
        } catch (e: Throwable) {
            Bytes.wipe(key)
            throw e
        }
    }

    private fun startSession(key: ByteArray, h: VaultHeader, list: List<Entry>, corrupted: Int, gen: Long) {
        synchronized(sessionLock) {
            // Bị khóa (vd. tắt màn hình) trong lúc đang chạy Argon2id: không mở phiên nữa.
            if (generation != gen) throw VaultLockedException()
            Bytes.wipe(vek)
            vek = key
            header = h
            corruptedItemCount = corrupted
            _entries.value = sorted(list)
            _unlocked.value = true
        }
    }

    /** Bản chụp phiên hiện tại (khóa là bản sao, tự wipe sau [block]). Ném [VaultLockedException] nếu đang khóa. */
    private inline fun <T> withSession(block: (Session) -> T): T {
        val s = synchronized(sessionLock) {
            val k = vek ?: throw VaultLockedException()
            Session(k.copyOf(), header!!, generation, _entries.value)
        }
        try {
            return block(s)
        } finally {
            Bytes.wipe(s.key)
        }
    }

    /** Ghi kết quả (DB + trạng thái) chỉ khi vault chưa bị khóa kể từ [gen]; nếu đã khóa: không ghi gì. */
    private inline fun <T> commit(gen: Long, block: () -> T): T = synchronized(sessionLock) {
        if (generation != gen || vek == null) throw VaultLockedException()
        block()
    }

    private fun sorted(list: List<Entry>) = list.sortedBy { it.title.lowercase() }

    companion object {
        private const val KEY_HEADER = "vault_header"
        private const val KEY_BIOMETRIC = "biometric_wrapped_vek"
        private const val ITEM_FORMAT = 1
        private const val BACKUP_PAYLOAD_VERSION = 1
    }
}
