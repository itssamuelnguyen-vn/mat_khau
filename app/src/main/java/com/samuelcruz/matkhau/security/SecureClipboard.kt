package com.samuelcruz.matkhau.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Copy dữ liệu nhạy cảm vào clipboard và tự xóa sau N giây (spec 2.9).
 *
 * Giới hạn (best-effort): nếu hệ thống kill app trước khi hết giờ, bộ đếm mất theo;
 * bàn phím có "lịch sử clipboard" có thể giữ bản sao. Đánh dấu IS_SENSITIVE giúp
 * Android 13+ và Gboard không hiển thị/lưu nội dung.
 */
object SecureClipboard {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingClear: Runnable? = null

    fun copy(context: Context, label: String, text: String, clearAfterSeconds: Int) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ClipDescription.EXTRA_IS_SENSITIVE
            } else {
                "android.content.extra.IS_SENSITIVE"
            }
            putBoolean(key, true)
        }
        cm.setPrimaryClip(clip)

        pendingClear?.let { handler.removeCallbacks(it) }
        val r = Runnable { clearNow(context) }
        pendingClear = r
        handler.postDelayed(r, clearAfterSeconds * 1000L)
    }

    /** Gọi khi khóa vault: nếu còn dữ liệu do app copy chưa bị xóa thì xóa luôn. */
    fun clearIfPending(context: Context) {
        if (pendingClear != null) clearNow(context)
    }

    private fun clearNow(context: Context) {
        pendingClear?.let { handler.removeCallbacks(it) }
        pendingClear = null
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        runCatching { cm.clearPrimaryClip() }
    }
}
