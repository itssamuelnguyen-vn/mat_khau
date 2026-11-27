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
import com.samuelcruz.matkhau.core.vault.BackupFormat
import com.samuelcruz.matkhau.core.vault.WrongSecretException
import com.samuelcruz.matkhau.data.RestoredBackup
import com.samuelcruz.matkhau.model.Entry
import com.samuelcruz.matkhau.model.EntryType
import com.samuelcruz.matkhau.security.BiometricKeyManager
import com.samuelcruz.matkhau.security.BiometricPrompter
import com.samuelcruz.matkhau.security.SecureClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Các màn hình khi vault ĐANG MỞ (điều hướng kiểu stack). */
sealed interface Screen {
    data object VaultList : Screen
    data class Detail(val id: String) : Screen
    data class Edit(val id: String?, val type: EntryType = EntryType.LOGIN) : Screen
    data object Generator : Screen
    data object Settings : Screen
    data object Security : Screen
    data class ChangePassword(val forced: Boolean) : Screen
    data class ShowRecoveryKey(val key: String, val firstTime: Boolean) : Screen
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
    /** Thông báo ngắn ở cuối màn hình. */
    var snackbar by mutableStateOf<String?>(null)

    var pendingBackup by mutableStateOf<BackupFormat.Parsed?>(null)
        private set
    var decryptedBackup by mutableStateOf<RestoredBackup?>(null)
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
            repository.unlocked.collect { unlocked ->
                if (!unlocked) {
                    // Khóa => bỏ toàn bộ trạng thái màn hình đang mở (có thể chứa dữ liệu nhạy cảm).
                    stack = listOf(Screen.VaultList)
                    formError = null
                    clearBackupState()
                    if (lockedScreen != LockedScreen.Restore && lockedScreen != LockedScreen.Create) {
                        lockedScreen = if (repository.initialized.value == false) LockedScreen.Welcome else LockedScreen.Unlock
                    }
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
        val recoveryKey = repository.create(password)
        lockedScreen = LockedScreen.Unlock
        stack = listOf(Screen.VaultList, Screen.ShowRecoveryKey(recoveryKey, firstTime = true))
    }

    fun unlock(password: String) = runBusy("Đang mở khóa…") {
        try {
            repository.unlockWithPassword(password)
            afterUnlock()
        } catch (e: WrongSecretException) {
            formError = "Sai mật khẩu chính"
        }
    }

    fun unlockWithRecoveryKey(text: String) = runBusy("Đang kiểm tra Recovery Key…") {
        try {
            repository.unlockWithRecoveryKey(text)
            afterUnlock()
            stack = listOf(Screen.VaultList, Screen.ChangePassword(forced = true))
        } catch (e: WrongSecretException) {
            formError = "Recovery Key không đúng"
        }
    }

    fun unlockWithBiometric(activity: FragmentActivity) = viewModelScope.launch {
        val blob = repository.biometricBlob() ?: return@launch
        val (iv, ct) = try {
            BiometricKeyManager.decodeBlob(blob)
        } catch (e: Exception) {
            disableBiometricInternal(); return@launch
        }
        val cipher = try {
            withContext(Dispatchers.IO) { biometric.decryptCipher(iv) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disableBiometricInternal()
            formError = "Vân tay trên máy đã thay đổi. Hãy mở bằng mật khẩu chính rồi bật lại vân tay."
            return@launch
        } catch (e: Exception) {
            disableBiometricInternal()
            formError = "Không dùng được khóa vân tay. Hãy mở bằng mật khẩu chính."
            return@launch
        }
        BiometricPrompter.authenticate(
            activity, cipher,
            title = "Mở khóa Mật Khẩu",
            subtitle = "Xác thực để giải mã vault",
            onSuccess = onSuccess@{ authorized ->
                val key = try {
                    authorized.doFinal(ct)
                } catch (e: Exception) {
                    formError = "Giải mã bằng vân tay thất bại"
                    return@onSuccess
                }
                runBusy("Đang mở khóa…") {
                    try {
                        repository.unlockWithKey(key)
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

    private fun afterUnlock() {
        formError = null
        stack = listOf(Screen.VaultList)
        app.autoLock.reschedule()
        val corrupted = repository.corruptedItemCount
        if (corrupted > 0) {
            snackbar = "Cảnh báo: $corrupted mục bị hỏng hoặc bị sửa đổi nên không được mở."
        }
    }

    fun lock() = app.lockNow()

    // ------------------------------------------------------------------ mục trong vault

    fun entry(id: String): Entry? = repository.entries.value.firstOrNull { it.id == id }

    fun saveEntry(draft: Entry, isNew: Boolean) = runBusy(null) {
        val now = System.currentTimeMillis()
        val entry = if (isNew) {
            draft.copy(id = UUID.randomUUID().toString(), createdAt = now, updatedAt = now)
        } else {
            draft.copy(updatedAt = now)
        }
        repository.save(entry)
        // Thay màn hình Edit bằng Detail của mục vừa lưu
        stack = stack.dropLast(1).let { s -> if (isNew) s + Screen.Detail(entry.id) else s }
        snackbar = "Đã lưu"
    }

    fun deleteEntry(id: String) = runBusy(null) {
        repository.delete(id)
        stack = stack.filterNot { it is Screen.Detail && it.id == id || it is Screen.Edit && it.id == id }
        snackbar = "Đã xóa"
    }

    fun copy(context: Context, label: String, value: String) {
        if (value.isEmpty()) return
        SecureClipboard.copy(context, label, value, clipboardSeconds)
        snackbar = "Đã copy $label. Clipboard sẽ được xóa sau $clipboardSeconds giây."
    }

    // ------------------------------------------------------------------ mật khẩu chính & recovery

    fun changePassword(current: String?, newPassword: String) = runBusy("Đang đổi mật khẩu…") {
        if (current != null && !repository.verifyPassword(current)) {
            formError = "Mật khẩu hiện tại không đúng"
            return@runBusy
        }
        repository.changePassword(newPassword)
        stack = listOf(Screen.VaultList)
        snackbar = "Đã đổi mật khẩu chính"
    }

    fun regenerateRecoveryKey() = runBusy("Đang tạo Recovery Key mới…") {
        val key = repository.regenerateRecoveryKey()
        navigate(Screen.ShowRecoveryKey(key, firstTime = false))
    }

    fun finishRecoveryKeyScreen() {
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
            snackbar = "Không tạo được khóa trong Keystore: ${e.javaClass.simpleName}"
            return@launch
        }
        BiometricPrompter.authenticate(
            activity, cipher,
            title = "Bật mở khóa bằng vân tay",
            subtitle = "Xác thực để lưu khóa vault vào Android Keystore",
            onSuccess = onSuccess@{ authorized ->
                // Vault có thể vừa tự khóa trong lúc hộp thoại vân tay đang mở.
                val key = repository.copyKeyForBiometric() ?: run {
                    biometric.deleteKey()
                    return@onSuccess
                }
                val blob = try {
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
    fun resumeAutoLock() = app.autoLock.resume()

    fun backupFileName(): String =
        "matkhau-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".lvbackup"

    fun exportBackup(context: Context, uri: Uri?) {
        app.autoLock.resume()
        if (uri == null) return
        runBusy("Đang tạo backup…") {
            val bytes = repository.exportBackup()
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Không mở được file để ghi")
            }
            val corrupted = repository.corruptedItemCount
            snackbar = "Đã lưu backup (${repository.entries.value.size} mục). Hãy cất file ở nơi an toàn." +
                if (corrupted > 0) " $corrupted mục bị hỏng KHÔNG có trong backup." else ""
        }
    }

    fun openBackupFile(context: Context, uri: Uri?) {
        app.autoLock.resume()
        if (uri == null) return
        runBusy("Đang đọc file…") {
            val bytes = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input -> readAtMost(input, BackupFormat.MAX_FILE_SIZE) }
                        ?: throw FormatException("Không đọc được file")
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
                decryptedBackup = repository.decryptBackup(parsed, secret, isRecoveryKey)
                formError = null
            } catch (e: WrongSecretException) {
                formError = if (isRecoveryKey) "Recovery Key không đúng với bản backup này" else "Sai mật khẩu của bản backup"
            } catch (e: DecryptionFailedException) {
                formError = "File backup đã bị sửa đổi hoặc hỏng — đã từ chối khôi phục"
            } catch (e: FormatException) {
                formError = e.message
            }
        }
    }

    fun confirmRestore() {
        val backup = decryptedBackup ?: return
        decryptedBackup = null
        runBusy("Đang khôi phục…") {
            repository.restore(backup)   // một transaction; meta mới không còn khóa vân tay cũ
            disableBiometricInternal()
            pendingBackup = null
            lockedScreen = LockedScreen.Unlock
            snackbar = "Đã khôi phục ${backup.entries.size} mục. Hãy mở khóa bằng mật khẩu của bản backup."
        }
    }

    private fun clearBackupState() {
        decryptedBackup?.discard()
        decryptedBackup = null
        pendingBackup = null
    }

    // ------------------------------------------------------------------ tiện ích

    /** Đọc tối đa [limit] byte; file lớn hơn bị từ chối mà không phải nạp hết vào RAM. */
    private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
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
                snackbar = "Lỗi: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busyText = null
            }
        }
    }

    override fun onCleared() {
        clearBackupState()
        super.onCleared()
    }
}
