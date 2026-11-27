package com.samuelcruz.matkhau.data

import android.content.Context
import com.samuelcruz.matkhau.core.crypto.Bytes
import com.samuelcruz.matkhau.core.crypto.DecryptionFailedException
import com.samuelcruz.matkhau.core.crypto.SealedBox
import com.samuelcruz.matkhau.core.format.FormatException
import com.samuelcruz.matkhau.core.vault.BackupFormat
import com.samuelcruz.matkhau.core.vault.RecoveryKey
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
 */
class VaultRepository(context: Context) {
    private val db = VaultDatabase(context.applicationContext)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val writeMutex = Mutex()
    private val sessionLock = Any()

    // Chỉ truy cập trong synchronized(sessionLock)
    private var vek: ByteArray? = null
    private var header: VaultHeader? = null

    /** null = chưa đọc DB xong (gọi [load] lúc khởi động); true = đã có vault. */
    private val _initialized = MutableStateFlow<Boolean?>(null)
    val initialized: StateFlow<Boolean?> = _initialized.asStateFlow()

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Số mục không giải mã được trong lần mở khóa gần nhất (bị hỏng / bị sửa). */
    var corruptedItemCount: Int = 0
        private set

    // ---------------------------------------------------------------- tạo / mở / khóa

    /** Đọc DB xem đã có vault chưa. Gọi một lần lúc app khởi động. */
    suspend fun load() = withContext(Dispatchers.IO) {
        _initialized.value = db.getMeta(KEY_HEADER) != null
    }

    /** Tạo vault mới, mở khóa luôn. Trả về Recovery Key dạng chữ để hiển thị MỘT lần. */
    suspend fun create(password: String): String = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            // Không bao giờ ghi đè một vault đang có (khôi phục backup mới được thay vault).
            check(db.getMeta(KEY_HEADER) == null) { "Vault đã tồn tại" }
            val pw = password.toByteArray(Charsets.UTF_8)
            val created = try {
                VaultCrypto.createVault(pw)
            } finally {
                Bytes.wipe(pw)
            }
            db.replaceVault(mapOf(KEY_HEADER to created.header.encode()), emptyList())
            val recoveryText = RecoveryKey.format(created.recoveryKey)
            Bytes.wipe(created.recoveryKey)
            startSession(created.vek, created.header, emptyList())
            _initialized.value = true
            recoveryText
        }
    }

    /** Ném [WrongSecretException] nếu sai mật khẩu. */
    suspend fun unlockWithPassword(password: String) = withContext(Dispatchers.IO) {
        val h = loadHeader()
        val pw = password.toByteArray(Charsets.UTF_8)
        val key = try {
            VaultCrypto.unlockWithPassword(h, pw)
        } finally {
            Bytes.wipe(pw)
        }
        openSession(key, h)
    }

    suspend fun unlockWithRecoveryKey(recoveryText: String) = withContext(Dispatchers.IO) {
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
        openSession(key, h)
    }

    /** Dùng sau khi Android Keystore (vân tay) trả lại VEK. Repository nhận quyền sở hữu mảng [key]. */
    suspend fun unlockWithKey(key: ByteArray) = withContext(Dispatchers.IO) {
        openSession(key, loadHeader())
    }

    fun lock() {
        synchronized(sessionLock) {
            Bytes.wipe(vek)
            vek = null
            header = null
        }
        _entries.value = emptyList()
        _unlocked.value = false
    }

    fun isUnlocked(): Boolean = _unlocked.value

    /** Header hiện tại (không chứa bí mật) — để màn hình Security hiển thị tham số. */
    suspend fun headerInfo(): VaultHeader? = withContext(Dispatchers.IO) {
        synchronized(sessionLock) { header } ?: runCatching { loadHeader() }.getOrNull()
    }

    // ---------------------------------------------------------------- CRUD

    suspend fun save(entry: Entry) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            withKey { key, h ->
                val plain = json.encodeToString(Entry.serializer(), entry).toByteArray(Charsets.UTF_8)
                val box = try {
                    VaultCrypto.encryptItem(key, h.vaultId, entry.id, plain)
                } finally {
                    Bytes.wipe(plain)
                }
                db.upsertItem(StoredItem(entry.id, ITEM_FORMAT, box.nonce, box.ciphertext))
            }
            if (_unlocked.value) {
                _entries.value = sorted(_entries.value.filterNot { it.id == entry.id } + entry)
            }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            withKey { _, _ -> db.deleteItem(id) }
            if (_unlocked.value) _entries.value = _entries.value.filterNot { it.id == id }
        }
    }

    // ---------------------------------------------------------------- mật khẩu & recovery

    suspend fun verifyPassword(password: String): Boolean = withContext(Dispatchers.IO) {
        val h = loadHeader()
        val pw = password.toByteArray(Charsets.UTF_8)
        try {
            Bytes.wipe(VaultCrypto.unlockWithPassword(h, pw))
            true
        } catch (e: WrongSecretException) {
            false
        } finally {
            Bytes.wipe(pw)
        }
    }

    suspend fun changePassword(newPassword: String) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val updated = withKey { key, h ->
                val pw = newPassword.toByteArray(Charsets.UTF_8)
                try {
                    VaultCrypto.changePassword(h, key, pw)
                } finally {
                    Bytes.wipe(pw)
                }
            }
            db.putMeta(KEY_HEADER, updated.encode())
            synchronized(sessionLock) { if (vek != null) header = updated }
        }
    }

    suspend fun regenerateRecoveryKey(): String = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val (updated, raw) = withKey { key, h -> VaultCrypto.regenerateRecoveryKey(h, key) }
            db.putMeta(KEY_HEADER, updated.encode())
            synchronized(sessionLock) { if (vek != null) header = updated }
            RecoveryKey.format(raw).also { Bytes.wipe(raw) }
        }
    }

    // ---------------------------------------------------------------- backup / restore

    suspend fun exportBackup(): ByteArray = withContext(Dispatchers.IO) {
        withKey { key, h ->
            val payload = json.encodeToString(
                BackupPayload.serializer(),
                BackupPayload(exportedAt = System.currentTimeMillis(), entries = _entries.value),
            ).toByteArray(Charsets.UTF_8)
            try {
                BackupFormat.create(h, key, payload)
            } finally {
                Bytes.wipe(payload)
            }
        }
    }

    /** Bước 2 của restore: kiểm tra định dạng. Ném [FormatException]. */
    fun parseBackup(file: ByteArray): BackupFormat.Parsed = BackupFormat.parse(file)

    /**
     * Bước 3–5: xác thực bằng mật khẩu (hoặc Recovery Key) của BẢN BACKUP, giải mã, kiểm tra toàn vẹn.
     * Ném [WrongSecretException] hoặc [DecryptionFailedException] (file bị sửa).
     */
    suspend fun decryptBackup(parsed: BackupFormat.Parsed, secret: String, isRecoveryKey: Boolean): RestoredBackup =
        withContext(Dispatchers.IO) {
            val key = if (isRecoveryKey) {
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
                val pw = secret.toByteArray(Charsets.UTF_8)
                try {
                    VaultCrypto.unlockWithPassword(parsed.header, pw)
                } finally {
                    Bytes.wipe(pw)
                }
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
                RestoredBackup(parsed.header, key, payload.entries, payload.exportedAt)
            } catch (e: Exception) {
                Bytes.wipe(key)
                throw e
            }
        }

    /** Bước 6: ghi đè vault hiện tại bằng backup (atomic), sau đó khóa lại. */
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
                // Meta mới chỉ có header → khóa vân tay cũ (gắn với VEK cũ) tự động bị bỏ.
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

    /** Bản sao VEK để bọc bằng Keystore, hoặc null nếu vault vừa bị khóa. Người gọi PHẢI wipe sau khi dùng. */
    fun copyKeyForBiometric(): ByteArray? = synchronized(sessionLock) { vek?.copyOf() }

    // ---------------------------------------------------------------- nội bộ

    private fun loadHeader(): VaultHeader =
        VaultHeader.decode(db.getMeta(KEY_HEADER) ?: throw IllegalStateException("Chưa có vault"))

    private fun openSession(key: ByteArray, h: VaultHeader) {
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
        // Mọi mục đều hỏng => gần như chắc chắn khóa sai: không mở vault (fail securely, spec 5.9).
        if (stored.isNotEmpty() && corrupted == stored.size) {
            Bytes.wipe(key)
            throw WrongSecretException()
        }
        corruptedItemCount = corrupted
        startSession(key, h, entries)
    }

    private fun startSession(key: ByteArray, h: VaultHeader, list: List<Entry>) {
        synchronized(sessionLock) {
            Bytes.wipe(vek)
            vek = key
            header = h
        }
        _entries.value = sorted(list)
        _unlocked.value = true
    }

    private inline fun <T> withKey(block: (ByteArray, VaultHeader) -> T): T {
        val (key, h) = synchronized(sessionLock) {
            val k = vek ?: throw VaultLockedException()
            k.copyOf() to header!!
        }
        try {
            return block(key, h)
        } finally {
            Bytes.wipe(key)
        }
    }

    private fun sorted(list: List<Entry>) = list.sortedBy { it.title.lowercase() }

    companion object {
        private const val KEY_HEADER = "vault_header"
        private const val KEY_BIOMETRIC = "biometric_wrapped_vek"
        private const val ITEM_FORMAT = 1
    }
}
