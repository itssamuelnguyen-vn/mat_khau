package com.samuelcruz.matkhau.security

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.samuelcruz.matkhau.core.vault.UnlockThrottle

/**
 * Bộ đếm nhập sai mật khẩu chính / Recovery Key (luật ở [UnlockThrottle]). Dùng chung cho app chính và màn hình tự
 * động điền. Mở khóa bằng vân tay không bị đếm (hệ thống đã tự giới hạn).
 *
 * Đếm TRƯỚC khi chạy Argon2id ([tryBegin]): hai lần thử cùng lúc (app chính + màn tự động điền) không cùng lọt qua,
 * và app bị kill giữa lần thử vẫn bị tính một lần sai. Mở được => [reset]; lỗi không phải sai mật khẩu => [cancel].
 */
class UnlockLimiter(context: Context, private val settings: AppSettings) {
    private val resolver = context.applicationContext.contentResolver

    private fun boot(): Int = runCatching { Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, 0) }.getOrDefault(0)

    val failures: Int get() = settings.unlockFailures

    /** Còn phải chờ bao lâu (ms) trước lần thử tiếp. */
    @Synchronized
    fun remainingMs(): Long {
        val failures = settings.unlockFailures
        if (UnlockThrottle.delayMs(failures) == 0L) return 0L
        val now = SystemClock.elapsedRealtime()
        val boot = boot()
        if (UnlockThrottle.needsRebase(settings.unlockLastFailureAt, settings.unlockLastBoot, now, boot)) {
            // Máy vừa khởi động lại: thời gian chờ tính lại từ bây giờ (không được miễn).
            settings.setUnlockState(failures, now, boot)
        }
        return UnlockThrottle.remainingMs(failures, settings.unlockLastFailureAt, settings.unlockLastBoot, now, boot)
    }

    private var before: Triple<Int, Long, Int>? = null

    /** 0 = được thử (đã tính trước là một lần sai); > 0 = phải chờ thêm chừng đó ms. */
    @Synchronized
    fun tryBegin(): Long {
        val wait = remainingMs()
        if (wait > 0) return wait
        before = Triple(settings.unlockFailures, settings.unlockLastFailureAt, settings.unlockLastBoot)
        settings.setUnlockState((settings.unlockFailures + 1).coerceAtMost(1000), SystemClock.elapsedRealtime(), boot())
        return 0L
    }

    /** Lần thử bị gián đoạn vì lý do khác (vault bị khóa giữa chừng, lỗi đọc DB): không tính là sai. */
    @Synchronized
    fun cancel() {
        // Trả lại đúng trạng thái trước lần thử (cả mốc thời gian: không bắt chờ lại từ đầu).
        val b = before
        if (b != null) settings.setUnlockState(b.first, b.second, b.third)
        before = null
    }

    @Synchronized
    fun reset() {
        before = null
        if (settings.unlockFailures != 0) settings.setUnlockState(0, 0L, 0)
    }
}
