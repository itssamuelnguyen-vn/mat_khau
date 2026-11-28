package com.samuelcruz.matkhau.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.samuelcruz.matkhau.MatKhauApp
import com.samuelcruz.matkhau.R
import com.samuelcruz.matkhau.core.crypto.Bytes
import com.samuelcruz.matkhau.core.crypto.DecryptionFailedException
import com.samuelcruz.matkhau.core.format.FormatException
import com.samuelcruz.matkhau.core.importer.CsvImportResult
import com.samuelcruz.matkhau.core.importer.CsvImporter
import com.samuelcruz.matkhau.core.vault.BackupFormat
import com.samuelcruz.matkhau.core.vault.WrongSecretException
import com.samuelcruz.matkhau.core.backup.BackupReminder
import com.samuelcruz.matkhau.core.totp.OtpMigration
import com.samuelcruz.matkhau.core.totp.Totp
import com.samuelcruz.matkhau.core.vault.Attachments
import com.samuelcruz.matkhau.data.AttachmentLimitException
import com.samuelcruz.matkhau.data.RestoredBackup
import com.samuelcruz.matkhau.model.Entry
import com.samuelcruz.matkhau.model.EntryType
import com.samuelcruz.matkhau.model.PasswordHistoryItem
import com.samuelcruz.matkhau.security.BiometricKeyManager
import com.samuelcruz.matkhau.security.BiometricPrompter
import com.samuelcruz.matkhau.security.SecureClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** File CSV lớn nhất được đọc (file xuất từ trình quản lý mật khẩu thường chỉ vài trăm KB). */
private const val MAX_CSV_SIZE = 20 * 1024 * 1024

/** Các màn hình khi vault ĐANG MỞ (điều hướng kiểu stack). */
sealed interface Screen {
    data object VaultList : Screen
    data class Detail(val id: String) : Screen
    data class Edit(val id: String?, val type: EntryType = EntryType.LOGIN) : Screen
    data object Generator : Screen
    data object Settings : Screen
    data object Security : Screen
    data object Health : Screen
    data object ImportCsv : Screen
    /** Nhập mã 2FA từ mã QR "Chuyển tài khoản" của Google Authenticator. */
    data object ImportTotp : Screen
    /** Xem một file đính kèm (ảnh / chữ) ngay trong app. */
    data class Attachment(val entryId: String, val attachmentId: String) : Screen
    data class ChangePassword(val forced: Boolean) : Screen
    /** [replacedUnconfirmed]: key cũ chưa được xác nhận đã chép nên app vừa tạo key mới thay thế. */
    data class ShowRecoveryKey(val key: String, val firstTime: Boolean, val replacedUnconfirmed: Boolean = false) : Screen
    data object Restore : Screen
}

/** Các màn hình khi vault ĐANG KHÓA hoặc chưa tạo. */
enum class LockedScreen { Welcome, Create, Unlock, RecoveryUnlock, Restore }

class VaultViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MatKhauApp
    val repository = app.repository
    val settings = app.settings
    private val biometric = app.biometric

    var stack by mutableStateOf<List<Screen>>(listOf(Screen.VaultList))
        private set
    /** AppRoot hiện Welcome thay cho Unlock khi chưa có vault (repository.initialized == false). */
    var lockedScreen by mutableStateOf(LockedScreen.Unlock)
        private set
    var busyText by mutableStateOf<String?>(null)
        private set
    /** Lỗi hiển thị ngay trong form đang mở (sai mật khẩu, ...). */
    var formError by mutableStateOf<String?>(null)
    /**
     * Thông báo ngắn ở cuối màn hình: gán `snackbar = "..."` để đưa vào hàng đợi, AppRoot lấy ra hiển thị lần lượt.
     * (Hàng đợi thay cho một biến state: trước đây xóa biến làm LaunchedEffect khởi động lại và hủy snackbar.)
     */
    private val messageQueue = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageQueue.receiveAsFlow()
    var snackbar: String?
        get() = null
        set(value) {
            if (value != null) messageQueue.trySend(value)
        }

    /**
     * true khi ViewModel thuộc màn hình tự động điền: mở khóa ở đó không chạy các bước bắt buộc của app chính
     * (đặt mật khẩu mới, hiện Recovery Key mới) vì màn hình đó không hiển thị chúng; app chính làm khi mở lần sau.
     */
    var hostIsAutofill = false

    /** Khóa bằng tay ("Khóa ngay") thì không tự bật hộp thoại vân tay ngay sau đó. */
    var skipNextBiometricPrompt by mutableStateOf(false)

    var pendingBackup by mutableStateOf<BackupFormat.Parsed?>(null)
        private set
    var decryptedBackup by mutableStateOf<RestoredBackup?>(null)
        private set

    /** Nhập CSV: kết quả đọc file chờ xác nhận (chứa mật khẩu dạng chữ: bỏ khi khóa / rời màn hình). */
    var pendingImport by mutableStateOf<CsvImportResult?>(null)
        private set
    /** Số mục vừa nhập xong (null = chưa nhập) — để màn hình nhắc xóa file CSV. */
    var importedCount by mutableStateOf<Int?>(null)
        private set
    private var importUri: Uri? = null

    /** Nhập 2FA: các tài khoản đã quét (chứa secret: bỏ khi khóa / rời màn hình). */
    var totpImport by mutableStateOf<TotpImportState?>(null)
        private set

    var autoLockMillis by mutableStateOf(settings.autoLockMillis)
        private set
    var clipboardSeconds by mutableStateOf(settings.clipboardSeconds)
        private set
    /** Đọc từ DB/Keystore trong init (ngoài main thread), nên lúc đầu là false. */
    var biometricEnabled by mutableStateOf(false)
        private set

    val wordList: List<String> by lazy {
        application.resources.openRawResource(R.raw.bip39_english).bufferedReader().readLines().filter { it.isNotBlank() }
    }

    init {
        viewModelScope.launch { biometricEnabled = isBiometricConfigured() }
        viewModelScope.launch {
            // Mỗi lần khóa (kể cả khi đang ở màn hình khôi phục lúc chưa có vault): bỏ toàn bộ trạng thái màn hình
            // có thể chứa dữ liệu nhạy cảm, gồm cả backup đã giải mã.
            repository.lockCount.drop(1).collect {
                stack = listOf(Screen.VaultList)
                formError = null
                clearBackupState()
                clearImportState()
                totpImport = null
                if (lockedScreen != LockedScreen.Create) {
                    lockedScreen = if (repository.initialized.value == false) LockedScreen.Welcome else LockedScreen.Unlock
                }
            }
        }
    }

    // ------------------------------------------------------------------ điều hướng

    fun navigate(screen: Screen) {
        formError = null
        stack = stack + screen
    }

    /** @return false nếu đang ở màn hình gốc (để hệ thống xử lý nút Back). */
    fun back(): Boolean {
        formError = null
        if (stack.size <= 1) return false
        val top = stack.last()
        if (top is Screen.ChangePassword && top.forced) return true // bắt buộc đặt mật khẩu mới
        if (top is Screen.ShowRecoveryKey) return true // phải xác nhận đã chép key
        if (top is Screen.Restore) clearBackupState()
        if (top is Screen.ImportCsv) clearImportState()
        if (top is Screen.ImportTotp) totpImport = null
        stack = stack.dropLast(1)
        return true
    }

    fun showLocked(screen: LockedScreen) {
        formError = null
        if (screen != LockedScreen.Restore) clearBackupState()
        lockedScreen = screen
    }

    // ------------------------------------------------------------------ tạo / mở khóa

    // Argon2id chạy 2 lần (mật khẩu chính + Recovery Key) nên lâu gấp đôi lúc mở khóa.
    fun createVault(password: String) = runBusy("Đang tạo vault (Argon2id × 2, vài giây)…") {
        // Đặt cờ TRƯỚC: nếu vault bị khóa ngay sau khi tạo, lần mở khóa sau sẽ tạo và hiện Recovery Key mới.
        settings.recoveryKeyPending = true
        val recoveryKey = try {
            repository.create(password)
        } catch (e: com.samuelcruz.matkhau.data.VaultLockedException) {
            lockedScreen = LockedScreen.Unlock
            snackbar = "Vault đã được tạo nhưng bị khóa trước khi hiện Recovery Key. Mở khóa để nhận Recovery Key."
            return@runBusy
        }
        if (!repository.isUnlocked()) return@runBusy   // bị khóa ngay sau khi tạo: không đưa key vào stack
        lockedScreen = LockedScreen.Unlock
        stack = listOf(Screen.VaultList, Screen.ShowRecoveryKey(recoveryKey, firstTime = true))
        app.autoLock.reschedule()
    }

    /** Còn phải chờ bao lâu sau nhiều lần nhập sai (UI đếm ngược và khóa nút "Mở khóa"). */
    fun unlockWaitMs(): Long = app.unlockLimiter.remainingMs()

    val unlockFailures: Int get() = app.unlockLimiter.failures

    // Thời gian chờ do UnlockWaitNotice hiện (đếm ngược): thông báo lỗi không ghi kèm để khỏi bị cũ.
    private fun failureMessage(base: String): String = base

    /**
     * Một lần thử mật khẩu / Recovery Key qua bộ đếm: tính là sai TRƯỚC khi chạy Argon2id, mở được thì xóa bộ đếm,
     * lỗi khác (vault bị khóa giữa chừng...) thì không tính.
     */
    private fun attemptUnlock(busy: String, wrongMessage: String, attempt: suspend () -> Unit) {
        if (busyText != null) return
        app.unlockLimiter.tryBegin().takeIf { it > 0 }?.let { formError = null; return }
        runBusy(busy) {
            try {
                attempt()
                app.unlockLimiter.reset()
            } catch (e: WrongSecretException) {
                formError = failureMessage(wrongMessage)
                return@runBusy
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e   // màn hình đóng giữa chừng: vẫn tính là một lần thử
            } catch (e: Exception) {
                app.unlockLimiter.cancel()   // vault bị khóa giữa chừng, lỗi đọc DB...: không phải đoán sai
                throw e
            }
            afterUnlock()
        }
    }

    fun unlock(password: String) = attemptUnlock("Đang mở khóa…", "Sai mật khẩu chính") {
        repository.unlockWithPassword(password)
    }

    fun unlockWithRecoveryKey(text: String) = attemptUnlock("Đang kiểm tra Recovery Key…", "Recovery Key không đúng") {
        repository.unlockWithRecoveryKey(text)
        settings.recoveryKeyPending = false   // vừa dùng Recovery Key => người dùng đang giữ nó
        settings.mustChangePassword = true    // giữ qua các lần khóa/mở (kể cả mở bằng vân tay) tới khi đổi xong
    }

    fun unlockWithBiometric(activity: FragmentActivity) = viewModelScope.launch {
        try {
            startBiometricUnlock(activity)
        } catch (e: Exception) {
            formError = "Không dùng được vân tay. Hãy mở bằng mật khẩu chính."
        }
    }

    private suspend fun startBiometricUnlock(activity: FragmentActivity) {
        val blob = repository.biometricBlob() ?: return
        val vaultId = repository.storedVaultId() ?: return
        val (iv, ct) = try {
            BiometricKeyManager.decodeBlob(blob)
        } catch (e: Exception) {
            disableBiometricInternal()
            formError = "Hãy mở bằng mật khẩu chính rồi bật lại vân tay trong Cài đặt."
            return
        }
        // Chụp generation TRƯỚC khi chờ hộp thoại: vault bị khóa trong lúc đó thì kết quả vân tay bị bỏ.
        val gen = repository.currentGeneration()
        val cipher = try {
            withContext(Dispatchers.IO) { biometric.decryptCipher(iv) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disableBiometricInternal()
            formError = "Vân tay trên máy đã thay đổi. Hãy mở bằng mật khẩu chính rồi bật lại vân tay."
            return
        } catch (e: Exception) {
            disableBiometricInternal()
            formError = "Không dùng được khóa vân tay. Hãy mở bằng mật khẩu chính."
            return
        }
        BiometricPrompter.authenticate(
            activity, cipher,
            title = "Mở khóa Mật Khẩu",
            subtitle = "Xác thực để giải mã vault",
            onSuccess = onSuccess@{ authorized ->
                val key = try {
                    // AAD gắn blob với vaultId: blob của vault khác (sau khi khôi phục backup) không mở được.
                    authorized.updateAAD(BiometricKeyManager.aad(vaultId))
                    authorized.doFinal(ct)
                } catch (e: Exception) {
                    viewModelScope.launch { disableBiometricInternal() }
                    formError = "Khóa vân tay không còn khớp vault. Hãy dùng mật khẩu chính rồi bật lại vân tay."
                    return@onSuccess
                }
                if (busyText != null || repository.isUnlocked()) {   // đang / đã mở bằng cách khác: bỏ khóa này
                    Bytes.wipe(key)
                    return@onSuccess
                }
                runBusy("Đang mở khóa…") {
                    try {
                        repository.unlockWithKey(key, gen)
                        app.unlockLimiter.reset()   // chủ máy vừa chứng minh bằng vân tay: đếm lại từ đầu
                        afterUnlock()
                    } catch (e: WrongSecretException) {
                        disableBiometricInternal()
                        formError = "Khóa vân tay không còn khớp vault. Hãy dùng mật khẩu chính."
                    }
                }
            },
            onError = { formError = it },
        )
    }

    private suspend fun afterUnlock() {
        formError = null
        stack = listOf(Screen.VaultList)
        app.autoLock.reschedule()
        val corrupted = repository.corruptedItemCount
        if (corrupted > 0) {
            snackbar = "Cảnh báo: $corrupted mục bị hỏng hoặc bị sửa đổi nên không được mở."
        }
        if (hostIsAutofill) return
        if (settings.mustChangePassword) {
            // Đã mở bằng Recovery Key mà chưa đặt mật khẩu mới: bắt buộc, dù lần này mở bằng cách nào.
            stack = listOf(Screen.VaultList, Screen.ChangePassword(forced = true))
        } else if (settings.recoveryKeyPending) {
            // Lần trước vault bị khóa khi Recovery Key còn đang hiện: tạo key mới (key cũ vô hiệu) và hiện lại.
            val key = repository.regenerateRecoveryKey()
            if (repository.isUnlocked()) {
                stack = listOf(Screen.VaultList, Screen.ShowRecoveryKey(key, firstTime = false, replacedUnconfirmed = true))
            }
        }
    }

    /**
     * App chính hiện nội dung đã mở khóa mà KHÔNG qua afterUnlock của nó (vault được mở từ màn hình tự động điền,
     * hoặc Activity được tạo lại): vẫn chạy các bước bắt buộc còn dang dở.
     */
    fun ensureForcedFlows() {
        if (hostIsAutofill || busyText != null || !repository.isUnlocked()) return
        if (settings.mustChangePassword) {
            if (stack.none { it is Screen.ChangePassword && it.forced }) stack = listOf(Screen.VaultList, Screen.ChangePassword(forced = true))
            return
        }
        if (settings.recoveryKeyPending && stack.none { it is Screen.ShowRecoveryKey }) {
            runBusy("Đang tạo Recovery Key mới…") {
                val key = repository.regenerateRecoveryKey()
                if (repository.isUnlocked()) {
                    stack = listOf(Screen.VaultList, Screen.ShowRecoveryKey(key, firstTime = false, replacedUnconfirmed = true))
                }
            }
        }
    }

    /** Gọi khi người dùng gõ chữ / chạm trong hộp thoại (xem LocalUserActivity). */
    fun userActivity() = app.autoLock.onUserInteraction()

    fun lock() {
        skipNextBiometricPrompt = true
        app.lockNow()
    }

    // ------------------------------------------------------------------ mục trong vault

    fun entry(id: String): Entry? = repository.entries.value.firstOrNull { it.id == id }

    fun saveEntry(draft: Entry, isNew: Boolean) = runBusy(null) {
        val now = System.currentTimeMillis()
        val existing = if (isNew) null else entry(draft.id)
        val entry = when {
            isNew -> draft.copy(
                id = UUID.randomUUID().toString(), createdAt = now, updatedAt = now,
                passwordUpdatedAt = if (draft.password.isNotEmpty()) now else 0L,
            )
            // Form không biết các trường yêu thích / lịch sử: lấy từ bản đang lưu, rồi đổi mật khẩu qua
            // withPassword để mật khẩu cũ vào lịch sử (spec 3.2).
            existing != null -> draft.copy(
                password = existing.password,
                createdAt = existing.createdAt,
                updatedAt = now,
                favorite = existing.favorite,
                passwordUpdatedAt = existing.passwordUpdatedAt,
                passwordHistory = existing.passwordHistory,
                linkedApps = existing.linkedApps,
                attachments = existing.attachments,   // file đính kèm được thêm / xóa ở màn chi tiết, không qua form
            ).withPassword(draft.password, now)
            else -> draft.copy(updatedAt = now)
        }
        repository.save(entry)
        if (!repository.isUnlocked()) return@runBusy
        // Thay đúng màn hình Edit bằng Detail của mục vừa lưu (không dùng dropLast: stack có thể đã đổi trong lúc lưu)
        val edit = stack.lastOrNull { it is Screen.Edit && it.id == draft.id.ifEmpty { null } }
        stack = (stack.filterNot { it === edit } + if (isNew && edit != null) listOf(Screen.Detail(entry.id)) else emptyList())
            .ifEmpty { listOf(Screen.VaultList) }
        snackbar = "Đã lưu"
    }

    /** Ghim / bỏ ghim mục lên đầu danh sách (spec 3.3). Không đổi ngày sửa. */
    fun toggleFavorite(id: String) = runBusy(null) {
        val e = entry(id) ?: return@runBusy
        repository.save(e.copy(favorite = !e.favorite))
        snackbar = if (e.favorite) "Đã bỏ khỏi Yêu thích" else "Đã thêm vào Yêu thích"
    }

    /** Dùng lại một mật khẩu cũ; mật khẩu hiện tại vào lịch sử. */
    fun restorePassword(id: String, old: PasswordHistoryItem) = runBusy(null) {
        val e = entry(id) ?: return@runBusy
        val now = System.currentTimeMillis()
        // Tuổi của mật khẩu dùng lại: tính từ lúc nó bị thay (không coi là "mới đặt"), để Sức khỏe vẫn báo cũ.
        repository.save(e.withPassword(old.password, now, setAt = old.changedAt).copy(updatedAt = now))
        snackbar = "Đã khôi phục mật khẩu cũ. Nhớ đổi lại trên website nếu cần."
    }

    /** Bỏ liên kết tự động điền giữa mục và một ứng dụng. */
    fun unlinkApp(id: String, packageName: String) = runBusy(null) {
        val e = entry(id) ?: return@runBusy
        repository.save(e.copy(linkedApps = e.linkedApps.filterNot { it.packageName == packageName }))
        snackbar = "Đã bỏ liên kết"
    }

    fun deleteEntry(id: String) = runBusy(null) {
        repository.delete(id)
        stack = stack.filterNot { it is Screen.Detail && it.id == id || it is Screen.Edit && it.id == id }
            .ifEmpty { listOf(Screen.VaultList) }
        snackbar = "Đã xóa"
    }

    fun copy(context: Context, label: String, value: String) {
        if (value.isEmpty()) return
        SecureClipboard.copy(context, label, value, clipboardSeconds)
        snackbar = "Đã copy $label. Clipboard sẽ được xóa sau $clipboardSeconds giây."
    }

    // ------------------------------------------------------------------ mật khẩu chính & recovery

    /** [current] = null chỉ được chấp nhận khi đang bắt buộc đặt mật khẩu mới (vừa mở bằng Recovery Key). */
    fun changePassword(current: String?, newPassword: String) = runBusy("Đang đổi mật khẩu…") {
        if (current == null && !settings.mustChangePassword) {
            formError = "Hãy nhập mật khẩu chính hiện tại"
            return@runBusy
        }
        if (current != null && !repository.verifyPassword(current)) {
            formError = "Mật khẩu hiện tại không đúng"
            return@runBusy
        }
        repository.changePassword(newPassword)
        settings.mustChangePassword = false
        stack = listOf(Screen.VaultList)
        snackbar = "Đã đổi mật khẩu chính"
    }

    fun regenerateRecoveryKey() = runBusy("Đang tạo Recovery Key mới…") {
        val key = repository.regenerateRecoveryKey()
        settings.recoveryKeyPending = true   // key mới đã thay key cũ: phải được hiện và xác nhận
        if (!repository.isUnlocked()) return@runBusy   // bị khóa ngay sau đó: lần mở khóa sau sẽ tạo và hiện key mới
        navigate(Screen.ShowRecoveryKey(key, firstTime = false))
    }

    fun finishRecoveryKeyScreen() {
        settings.recoveryKeyPending = false
        stack = stack.filterNot { it is Screen.ShowRecoveryKey }.ifEmpty { listOf(Screen.VaultList) }
    }

    // ------------------------------------------------------------------ vân tay

    fun canUseBiometrics(): Boolean = biometric.canUseBiometrics(getApplication())

    private suspend fun isBiometricConfigured() =
        repository.biometricBlob() != null && withContext(Dispatchers.IO) { biometric.hasKey() }

    fun enableBiometric(activity: FragmentActivity) = viewModelScope.launch {
        val cipher = try {
            withContext(Dispatchers.IO) { biometric.newEncryptCipher() }
        } catch (e: Exception) {
            snackbar = "Không tạo được khóa vân tay trong Android Keystore"
            return@launch
        }
        BiometricPrompter.authenticate(
            activity, cipher,
            title = "Bật mở khóa bằng vân tay",
            subtitle = "Xác thực để lưu khóa vault vào Android Keystore",
            onSuccess = onSuccess@{ authorized ->
                // Vault có thể vừa tự khóa trong lúc hộp thoại vân tay đang mở.
                val (key, vaultId) = repository.copyKeyForBiometric() ?: run {
                    biometric.deleteKey()
                    return@onSuccess
                }
                val blob = try {
                    authorized.updateAAD(BiometricKeyManager.aad(vaultId))
                    BiometricKeyManager.encodeBlob(authorized.iv, authorized.doFinal(key))
                } catch (e: Exception) {
                    biometric.deleteKey()
                    snackbar = "Bật vân tay thất bại"
                    return@onSuccess
                } finally {
                    Bytes.wipe(key)
                }
                viewModelScope.launch {
                    repository.setBiometricBlob(blob)
                    biometricEnabled = true
                    snackbar = "Đã bật mở khóa bằng vân tay"
                }
            },
            onError = { snackbar = it; biometric.deleteKey() },
        )
    }

    fun disableBiometric() = viewModelScope.launch {
        disableBiometricInternal()
        snackbar = "Đã tắt mở khóa bằng vân tay"
    }

    private suspend fun disableBiometricInternal() {
        repository.clearBiometricBlob()
        withContext(Dispatchers.IO) { biometric.deleteKey() }
        biometricEnabled = false
    }

    // ------------------------------------------------------------------ cài đặt

    fun changeAutoLock(ms: Long) {
        settings.autoLockMillis = ms
        autoLockMillis = ms
        app.autoLock.reschedule()
    }

    fun changeClipboardSeconds(s: Int) {
        settings.clipboardSeconds = s
        clipboardSeconds = s
    }

    // ------------------------------------------------------------------ backup / restore

    fun suspendAutoLock() = app.autoLock.suspendForSystemPicker()
    fun pauseInactivityLock(paused: Boolean) = app.autoLock.pauseInactivityTimer(paused)
    fun resumeAutoLock() = app.autoLock.resume()

    fun backupFileName(): String =
        "matkhau-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".lvbackup"

    fun exportBackup(context: Context, uri: Uri?) {
        app.autoLock.resume()
        if (uri == null) return
        runBusy("Đang tạo backup…") {
            val changes = settings.changesSinceBackup
            val result = try {
                // Ghi thẳng ra file (backup có file đính kèm có thể vài chục MB: không dựng cả file trong RAM).
                // "wt" = ghi đè từ đầu; vài nơi lưu (vd. Google Drive) không hỗ trợ "wt" nên thử lại với "w"
                // (file vừa tạo bằng CreateDocument vốn rỗng).
                val out = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri, "wt")
                    } catch (e: Exception) {
                        context.contentResolver.openOutputStream(uri, "w")
                    }
                } ?: throw IllegalStateException("Không mở được file để ghi")
                out.use { raw -> java.io.BufferedOutputStream(raw, 256 * 1024).use { repository.exportBackup(it) } }
            } catch (e: Exception) {
                deleteQuietly(context, uri)   // CreateDocument đã tạo sẵn file: không để lại file hỏng
                throw e
            }
            settings.markBackedUp(changes, System.currentTimeMillis())
            val corrupted = repository.corruptedItemCount
            snackbar = "Đã lưu backup (${result.entryCount} mục" +
                (if (result.attachmentCount > 0) ", ${result.attachmentCount} file" else "") + "). Hãy cất file ở nơi an toàn." +
                (if (corrupted > 0) " $corrupted mục bị hỏng KHÔNG có trong backup." else "") +
                (if (result.skippedAttachments > 0) " ${result.skippedAttachments} file đính kèm bị hỏng KHÔNG có trong backup." else "")
        }
    }

    fun openBackupFile(context: Context, uri: Uri?) {
        app.autoLock.resume()
        if (uri == null) return
        runBusy("Đang đọc file…") {
            val bytes = try {
                withContext(Dispatchers.IO) {
                    readUri(context, uri, BackupFormat.MAX_FILE_SIZE)
                }
            } catch (e: FormatException) {
                snackbar = "File không hợp lệ: ${e.message}"
                return@runBusy
            }
            // Khi đã có vault, chỉ được khôi phục sau khi mở khóa (người cầm máy đang khóa không ghi đè được vault).
            if (!repository.isUnlocked() && repository.initialized.value != false) {
                snackbar = "Hãy mở khóa vault trước khi khôi phục backup"
                return@runBusy
            }
            try {
                clearBackupState()
                pendingBackup = repository.parseBackup(bytes)
                if (repository.isUnlocked()) navigate(Screen.Restore) else lockedScreen = LockedScreen.Restore
            } catch (e: FormatException) {
                snackbar = "File không hợp lệ: ${e.message}"
            }
        }
    }

    fun decryptBackup(secret: String, isRecoveryKey: Boolean) {
        val parsed = pendingBackup ?: return
        runBusy("Đang giải mã backup…") {
            try {
                decryptedBackup?.discard()
                decryptedBackup = null
                val result = repository.decryptBackup(parsed, secret, isRecoveryKey)
                if (pendingBackup !== parsed) {
                    // Người dùng đã rời màn hình khôi phục (hoặc vault bị khóa) trong lúc giải mã: bỏ kết quả.
                    result.discard()
                    return@runBusy
                }
                decryptedBackup = result
                formError = null
            } catch (e: WrongSecretException) {
                if (pendingBackup === parsed) {
                    formError = if (isRecoveryKey) "Recovery Key không đúng với bản backup này" else "Sai mật khẩu của bản backup"
                }
            } catch (e: DecryptionFailedException) {
                if (pendingBackup === parsed) formError = "File backup đã bị sửa đổi hoặc hỏng — đã từ chối khôi phục"
            } catch (e: FormatException) {
                if (pendingBackup === parsed) formError = e.message
            }
        }
    }

    fun confirmRestore() {
        val backup = decryptedBackup ?: return
        decryptedBackup = null
        runBusy("Đang khôi phục…") {
            repository.restore(backup)   // một transaction; meta mới không còn khóa vân tay cũ
            // Cờ của vault cũ không áp dụng cho vault vừa khôi phục (tránh tự tạo Recovery Key mới, làm key giấy vô hiệu).
            settings.recoveryKeyPending = false
            settings.mustChangePassword = false
            settings.resetBackupState(backup.exportedAt)   // vault bây giờ đúng bằng bản backup đó
            disableBiometricInternal()
            pendingBackup = null
            lockedScreen = LockedScreen.Unlock
            snackbar = "Đã khôi phục ${backup.entries.size} mục" +
                (if (backup.attachmentCount > 0) ", ${backup.attachmentCount} file" else "") + ". Hãy mở khóa bằng mật khẩu của bản backup."
        }
    }

    // ------------------------------------------------------------------ nhập CSV

    /** Đọc file CSV (Bitwarden, Chrome, Firefox, KeePass…), bỏ mục trùng, rồi mở màn xem trước. */
    fun openCsvFile(context: Context, uri: Uri?) {
        app.autoLock.resume()
        if (uri == null) return
        runBusy("Đang đọc file CSV…") {
            val result = try {
                val bytes = readUri(context, uri, MAX_CSV_SIZE)
                val text = try {
                    CsvImporter.decode(bytes)
                } finally {
                    bytes.fill(0)
                }
                withContext(Dispatchers.Default) {
                    CsvImporter.import(text, repository.entries.value, System.currentTimeMillis()) {
                        UUID.randomUUID().toString()
                    }
                }
            } catch (e: FormatException) {
                snackbar = "Không nhập được: ${e.message}"
                return@runBusy
            }
            if (!repository.isUnlocked()) return@runBusy
            pendingImport = result
            importedCount = null
            importUri = uri
            navigate(Screen.ImportCsv)
        }
    }

    fun confirmImport() {
        val result = pendingImport ?: return
        runBusy("Đang nhập ${result.entries.size} mục…") {
            repository.importEntries(result.entries)   // một transaction
            pendingImport = null
            importedCount = result.entries.size
        }
    }

    /** File CSV chứa mật khẩu không mã hóa: xóa ngay nếu nơi lưu cho phép. */
    fun deleteImportedCsv(context: Context) {
        val uri = importUri ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
            }
            if (ok) importUri = null
            snackbar = if (ok) "Đã xóa file CSV" else "Không xóa được từ app. Hãy xóa file CSV bằng trình quản lý file (và cả trong Thùng rác)."
        }
    }

    fun finishImport() {
        clearImportState()
        stack = stack.filterNot { it == Screen.ImportCsv }.ifEmpty { listOf(Screen.VaultList) }
    }

    private fun clearImportState() {
        pendingImport = null
        importedCount = null
        importUri = null
    }

    private fun clearBackupState() {
        decryptedBackup?.discard()
        decryptedBackup = null
        pendingBackup = null
    }

    // ------------------------------------------------------------------ nhắc backup / tự backup

    /** Lời nhắc trên màn danh sách, null = không cần nhắc. Tự backup đang chạy tốt thì không nhắc. */
    /** Tăng mỗi lần tự backup chạy xong (chạy ngầm): để lời nhắc / màn Cài đặt đọc lại. */
    val autoBackupRuns = app.autoBackup.runs

    fun backupReminder(): String? {
        val error = settings.autoBackupLastError
        val snoozed = System.currentTimeMillis() < settings.backupReminderSnoozedUntil
        if (app.autoBackup.isEnabled && error != null && !snoozed) return "Tự backup đang lỗi: $error"
        return BackupReminder.message(
            entryCount = repository.entries.value.size,
            lastBackupAt = settings.lastBackupAt,
            changes = settings.changesSinceBackup,
            snoozedUntil = settings.backupReminderSnoozedUntil,
            now = System.currentTimeMillis(),
        )
    }

    var reminderVersion by mutableStateOf(0)
        private set

    fun snoozeBackupReminder() {
        settings.backupReminderSnoozedUntil = System.currentTimeMillis() + BackupReminder.SNOOZE_MS
        reminderVersion++
    }

    /** Người dùng chọn thư mục tự backup (OpenDocumentTree): giữ quyền lâu dài rồi ghi bản đầu tiên ngay. */
    fun setAutoBackupFolder(context: Context, uri: Uri?) {
        app.autoLock.resume()
        if (uri == null) return
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            snackbar = "Nơi lưu này không cho giữ quyền ghi lâu dài. Hãy chọn thư mục khác."
            return
        }
        settings.autoBackupTreeUri?.takeIf { it != uri.toString() }?.let { old -> releaseFolder(context, old) }
        settings.autoBackupTreeUri = uri.toString()
        settings.autoBackupLastError = null
        runAutoBackupNow()
    }

    fun disableAutoBackup(context: Context) {
        settings.autoBackupTreeUri?.let { releaseFolder(context, it) }
        settings.autoBackupTreeUri = null
        settings.autoBackupLastError = null
        reminderVersion++
        snackbar = "Đã tắt tự backup (các file đã ghi vẫn còn trong thư mục)"
    }

    private fun releaseFolder(context: Context, uri: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun runAutoBackupNow() = runBusy("Đang ghi backup…") {
        val error = app.autoBackup.runNow()
        reminderVersion++
        snackbar = error ?: "Đã ghi bản tự backup (giữ ${com.samuelcruz.matkhau.security.AutoBackup.KEEP} bản mới nhất)"
    }

    fun autoBackupFolderLabel(context: Context): String? {
        val uri = settings.autoBackupTreeUri?.let(Uri::parse) ?: return null
        return runCatching {
            val treeId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            // "primary:Download/MatKhau" -> "Download/MatKhau"; nhà cung cấp khác: hiện nguyên id
            treeId.substringAfter(':', treeId).ifEmpty { "Bộ nhớ máy" }
        }.getOrNull() ?: uri.toString()
    }

    // ------------------------------------------------------------------ file đính kèm

    /** Đọc file người dùng chọn (≤ 10 MB) và thêm vào mục (mã hóa từng đoạn, một transaction). */
    fun addAttachment(context: Context, entryId: String, uri: Uri?) {
        app.autoLock.resume()
        if (uri == null) return
        runBusy("Đang mã hóa file…") {
            val (name, mime) = withContext(Dispatchers.IO) { describe(context, uri) }
            val data = try {
                readUri(context, uri, Attachments.MAX_FILE_SIZE)
            } catch (e: FormatException) {
                snackbar = if (e.message == "File quá lớn") "File lớn hơn ${Attachments.MAX_FILE_SIZE / (1024 * 1024)} MB" else "Không đọc được file"
                return@runBusy
            }
            try {
                repository.addAttachment(entryId, name, mime, data)
                snackbar = "Đã thêm “$name” (đã mã hóa). File gốc vẫn còn ở chỗ cũ: xóa nếu không cần."
            } catch (e: AttachmentLimitException) {
                snackbar = e.message
            } finally {
                Bytes.wipe(data)
            }
        }
    }

    /** Giải mã một file để xem / xuất. Người gọi wipe mảng khi xong. */
    suspend fun readAttachment(entryId: String, attachmentId: String): ByteArray? = try {
        repository.readAttachment(entryId, attachmentId)
    } catch (e: DecryptionFailedException) {
        snackbar = "File đính kèm bị hỏng hoặc bị sửa đổi"
        null
    } catch (e: com.samuelcruz.matkhau.data.VaultLockedException) {
        null
    }

    fun deleteAttachment(entryId: String, attachmentId: String) = runBusy(null) {
        repository.deleteAttachment(entryId, attachmentId)
        stack = stack.filterNot { it is Screen.Attachment && it.attachmentId == attachmentId }.ifEmpty { listOf(Screen.VaultList) }
        snackbar = "Đã xóa file đính kèm"
    }

    /** Xuất file ra ngoài (KHÔNG mã hóa) tới nơi người dùng chọn. */
    fun exportAttachment(context: Context, entryId: String, attachmentId: String, uri: Uri?) {
        app.autoLock.resume()
        if (uri == null) return
        runBusy("Đang xuất file…") {
            val data = readAttachment(entryId, attachmentId) ?: run { deleteQuietly(context, uri); return@runBusy }
            try {
                withContext(Dispatchers.IO) {
                    val out = try {
                        context.contentResolver.openOutputStream(uri, "wt")
                    } catch (e: Exception) {
                        context.contentResolver.openOutputStream(uri, "w")
                    } ?: throw IllegalStateException()
                    out.use { it.write(data) }
                }
                snackbar = "Đã xuất file (KHÔNG mã hóa). Xóa bản đó khi dùng xong."
            } catch (e: Exception) {
                deleteQuietly(context, uri)
                throw e
            } finally {
                Bytes.wipe(data)
            }
        }
    }

    private fun describe(context: Context, uri: Uri): Pair<String, String> {
        var name = "file"
        runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        }
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
        return name to mime
    }

    // ------------------------------------------------------------------ nhập 2FA từ Google Authenticator

    fun startTotpImport() {
        totpImport = TotpImportState()
        navigate(Screen.ImportTotp)
    }

    /** Thêm một mã QR (hoặc URI dán tay) vào danh sách chờ nhập. Trả về thông báo cho người dùng. */
    fun addMigrationCode(text: String): String {
        val state = totpImport ?: return ""
        val batch = try {
            OtpMigration.parse(text)
        } catch (e: FormatException) {
            return e.message ?: "Mã QR không hợp lệ"
        }
        val existingSecrets = repository.entries.value.mapNotNull { e ->
            e.totp.takeIf { it.isNotBlank() }?.let { Totp.parseOrNull(it)?.secret?.let(com.samuelcruz.matkhau.core.format.Base32::encode) }
        }.toSet()
        var added = 0
        var duplicates = 0
        val accounts = state.accounts.toMutableList()
        val skipped = state.skipped.toMutableList()
        for (a in batch.accounts) {
            val key = com.samuelcruz.matkhau.core.format.Base32.encode(a.secret)
            val reason = a.unsupportedReason
            when {
                reason != null -> skipped += "${a.issuer.ifBlank { a.account }}: $reason"
                key in existingSecrets || accounts.any { com.samuelcruz.matkhau.core.format.Base32.encode(it.secret) == key } -> duplicates++
                else -> { accounts += a; added++ }
            }
        }
        val batches = state.batches + (batch.batchId to batch.batchIndex)
        totpImport = state.copy(accounts = accounts, skipped = skipped, batches = batches, batchSize = batch.batchSize, duplicates = state.duplicates + duplicates)
        val seen = batches.count { it.first == batch.batchId }
        return buildString {
            append("Mã QR ${batch.batchIndex + 1}/${batch.batchSize}: thêm $added tài khoản")
            if (duplicates > 0) append(", $duplicates đã có")
            if (seen < batch.batchSize) append(". Quét tiếp các mã còn lại")
        }
    }

    fun removeTotpAccount(index: Int) {
        val state = totpImport ?: return
        totpImport = state.copy(accounts = state.accounts.filterIndexed { i, _ -> i != index })
    }

    fun confirmTotpImport() {
        val state = totpImport ?: return
        if (state.accounts.isEmpty()) return
        runBusy("Đang nhập ${state.accounts.size} mã 2FA…") {
            val now = System.currentTimeMillis()
            val entries = state.accounts.map { a ->
                Entry(
                    id = UUID.randomUUID().toString(),
                    type = EntryType.LOGIN,
                    title = a.issuer.ifBlank { a.account.ifBlank { "Mã 2FA" } },
                    username = a.account,
                    totp = a.toOtpauthUri(),
                    createdAt = now,
                    updatedAt = now,
                )
            }
            repository.importEntries(entries)   // một transaction
            totpImport = null
            stack = stack.filterNot { it == Screen.ImportTotp }.ifEmpty { listOf(Screen.VaultList) }
            snackbar = "Đã nhập ${entries.size} mã 2FA. Bạn có thể sửa từng mục để thêm mật khẩu / URL, rồi xóa tài khoản trong app cũ."
        }
    }

    // ------------------------------------------------------------------ tiện ích

    private fun deleteQuietly(context: Context, uri: Uri) {
        runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }

    /**
     * Đọc file từ Storage Access Framework, tối đa [limit] byte. Biết trước kích thước thì cấp phát đúng một mảng
     * (file backup vài chục MB không bị chép 2–3 lần trong RAM).
     */
    private suspend fun readUri(context: Context, uri: Uri, limit: Int): ByteArray = withContext(Dispatchers.IO) {
        val size = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
        if (size != null && size > limit) throw FormatException("File quá lớn")
        val input = context.contentResolver.openInputStream(uri) ?: throw FormatException("Không đọc được file")
        input.use {
            if (size != null && size in 0..limit.toLong()) {
                try {
                    val buf = ByteArray(size.toInt())
                    var off = 0
                    while (off < buf.size) {
                        val n = it.read(buf, off, buf.size - off)
                        if (n < 0) break
                        off += n
                    }
                    // Kích thước báo sai (file đang đổi): đọc lại kiểu thường cho chắc.
                    if (off == buf.size && it.read() == -1) return@withContext buf
                    Bytes.wipe(buf)
                } catch (e: OutOfMemoryError) {
                    throw FormatException("File quá lớn so với bộ nhớ của máy")
                }
                return@withContext context.contentResolver.openInputStream(uri)?.use { again -> readAtMost(again, limit) }
                    ?: throw FormatException("Không đọc được file")
            }
            readAtMost(it, limit)
        }
    }

    /** Đọc tối đa [limit] byte; file lớn hơn bị từ chối mà không phải nạp hết vào RAM. */
    private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray = try {
        readAtMostUnchecked(input, limit)
    } catch (e: OutOfMemoryError) {
        throw FormatException("File quá lớn so với bộ nhớ của máy")
    }

    private fun readAtMostUnchecked(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw FormatException("File quá lớn")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Chạy tác vụ nặng trên coroutine, hiển thị vòng xoay, bắt lỗi chung (không lộ dữ liệu). */
    private fun runBusy(text: String?, block: suspend () -> Unit) {
        if (busyText != null) return
        busyText = text ?: ""
        viewModelScope.launch {
            try {
                block()
            } catch (e: com.samuelcruz.matkhau.data.VaultLockedException) {
                snackbar = "Vault đã bị khóa"
            } catch (e: Exception) {
                // Không in tên class (bị R8 đổi thành "a", "b"...) và không in chi tiết có thể chứa dữ liệu.
                snackbar = "Có lỗi xảy ra, thao tác chưa hoàn tất"
            } finally {
                busyText = null
            }
        }
    }

    override fun onCleared() {
        clearBackupState()
        totpImport = null
        super.onCleared()
    }
}

/** Các tài khoản 2FA đã quét, chờ nhập. [batches] = (batchId, batchIndex) đã quét, để biết còn thiếu mã QR nào. */
data class TotpImportState(
    val accounts: List<OtpMigration.Account> = emptyList(),
    val skipped: List<String> = emptyList(),
    val batches: Set<Pair<Int, Int>> = emptySet(),
    val batchSize: Int = 1,
    val duplicates: Int = 0,
) {
    override fun toString(): String = "TotpImportState(${accounts.size})"
}
