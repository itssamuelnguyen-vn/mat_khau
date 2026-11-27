package com.samuelcruz.matkhau

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import com.samuelcruz.matkhau.data.VaultRepository
import com.samuelcruz.matkhau.security.AppSettings
import com.samuelcruz.matkhau.security.AutoLockManager
import com.samuelcruz.matkhau.security.BiometricKeyManager
import com.samuelcruz.matkhau.security.SecureClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * Điểm khởi tạo của app — tương tự config/initializers trong Rails.
 * Giữ các đối tượng dùng chung (singleton) cho toàn app.
 */
class MatKhauApp : Application() {
    lateinit var repository: VaultRepository
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var autoLock: AutoLockManager
        private set
    val biometric = BiometricKeyManager()

    /** Đăng nhập vừa gửi trên app / trang khác, chờ người dùng xác nhận lưu (tự động điền). Chỉ trong RAM. */
    @Volatile
    var pendingAutofillSave: com.samuelcruz.matkhau.autofill.PendingSave? = null

    /** Coroutine scope sống cùng app (cho việc đọc DB lúc khởi động). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        repository = VaultRepository(this)
        appScope.launch { repository.load() }   // đọc DB trên Dispatchers.IO, không chặn main thread
        autoLock = AutoLockManager(settings, isUnlocked = { repository.isUnlocked() }, lockNow = { clearClipboard -> lockNow(clearClipboard) })
        ProcessLifecycleOwner.get().lifecycle.addObserver(autoLock)

        // Tắt màn hình => khóa ngay. ACTION_SCREEN_OFF là broadcast hệ thống, không cần quyền.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF) lockNow()
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * @param clearClipboard false khi khóa vì rời app: người dùng thường vừa copy mật khẩu để dán sang app khác,
     *   nên để bộ đếm của SecureClipboard tự xóa (tối đa 2 phút) thay vì xóa ngay.
     */
    fun lockNow(clearClipboard: Boolean = true) {
        if (clearClipboard) SecureClipboard.clearIfPending(this)
        pendingAutofillSave = null
        // Luôn gọi lock(): kể cả khi chưa mở, nó hủy luôn lần mở khóa còn đang chạy Argon2id.
        repository.lock()
    }
}
