package com.samuelcruz.matkhau.security

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Tự khóa vault (spec 2.8):
 *  - Không thao tác trong app quá thời gian cài đặt.
 *  - App xuống nền: khóa ngay (nếu chọn "Ngay khi rời app") hoặc sau thời gian cài đặt.
 *  - Tắt màn hình: luôn khóa ngay (đăng ký ở MatKhauApp).
 *
 * Ngoại lệ: khi app tự mở trình chọn file của hệ thống (xuất/nhập backup), Android coi như app
 * xuống nền — lúc đó tạm hoãn khóa tối đa [MAX_SUSPEND_MS] để thao tác không bị cắt ngang.
 *
 * Handler dùng đồng hồ "uptime" (dừng khi máy ngủ sâu), nên khi app quay lại foreground
 * ta kiểm tra thêm bằng elapsedRealtime (luôn chạy) để không bỏ lỡ hạn khóa.
 */
class AutoLockManager(
    private val settings: AppSettings,
    private val isUnlocked: () -> Boolean,
    /** Tham số: có xóa clipboard ngay không (false khi khóa vì app đang ở nền). */
    private val lockNow: (Boolean) -> Unit,
) : DefaultLifecycleObserver {

    private val handler = Handler(Looper.getMainLooper())
    private val lockRunnable = Runnable { if (isUnlocked()) lockNow(backgroundSince == 0L) }
    private var backgroundSince = 0L
    private var suspendedSince = 0L

    /**
     * Tạm dừng hẹn giờ "không thao tác" (vẫn khóa khi tắt màn hình / rời app), dùng khi màn hình đang hiện
     * Recovery Key để người dùng chép tay mà không bị khóa giữa chừng.
     */
    private var inactivityPaused = false

    fun pauseInactivityTimer(paused: Boolean) {
        inactivityPaused = paused
        reschedule()
    }

    /** Gọi trước khi mở trình chọn file của hệ thống. */
    fun suspendForSystemPicker() {
        suspendedSince = SystemClock.elapsedRealtime()
    }

    /** Gọi khi trình chọn file trả kết quả (kể cả khi người dùng hủy). */
    fun resume() {
        suspendedSince = 0L
        reschedule()
    }

    fun onUserInteraction() = reschedule()

    /** Lên lịch lại hạn khóa do không thao tác (chỉ khi đang ở foreground). */
    fun reschedule() {
        // Ở nền: giữ nguyên hẹn giờ khóa do onStop đặt (không được hủy nó).
        if (backgroundSince != 0L) return
        handler.removeCallbacks(lockRunnable)
        val timeout = settings.autoLockMillis
        if (backgroundSince == 0L && isUnlocked()) {
            when {
                // Đang hiện Recovery Key: cho thời gian chép tay nhưng vẫn khóa sau tối đa MAX_PAUSE_MS.
                inactivityPaused -> handler.postDelayed(lockRunnable, MAX_PAUSE_MS)
                timeout > 0 -> handler.postDelayed(lockRunnable, timeout)
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        val now = SystemClock.elapsedRealtime()
        val suspendedTooLong = suspendedSince != 0L && now - suspendedSince > MAX_SUSPEND_MS
        if (backgroundSince != 0L && (suspendedSince == 0L || suspendedTooLong)) {
            val timeout = settings.autoLockMillis
            if (now - backgroundSince >= timeout && isUnlocked()) lockNow(true)
        }
        // Quay lại app => hết lượt hoãn khóa, kể cả khi callback của trình chọn file không bao giờ chạy.
        suspendedSince = 0L
        backgroundSince = 0L
        reschedule()
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundSince = SystemClock.elapsedRealtime()
        handler.removeCallbacks(lockRunnable)
        if (suspendedSince != 0L) {
            // Đang ở trình chọn file: vẫn đặt "chốt chặn" khóa sau tối đa 5 phút.
            handler.postDelayed(lockRunnable, MAX_SUSPEND_MS)
            return
        }
        val timeout = settings.autoLockMillis
        if (timeout == 0L) {
            // Gọi cả khi chưa mở: lock() hủy luôn lần mở khóa còn đang chạy Argon2id.
            lockNow(false)
        } else {
            handler.postDelayed(lockRunnable, timeout)
        }
    }

    companion object {
        private const val MAX_SUSPEND_MS = 5 * 60_000L
        private const val MAX_PAUSE_MS = 10 * 60_000L
    }
}
