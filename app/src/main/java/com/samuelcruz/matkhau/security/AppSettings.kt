package com.samuelcruz.matkhau.security

import android.content.Context

/** Cài đặt KHÔNG nhạy cảm (thời gian tự khóa, thời gian xóa clipboard). Lưu bằng SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var autoLockMillis: Long
        get() = prefs.getLong(KEY_AUTO_LOCK, AUTO_LOCK_OPTIONS[1].first)
        set(value) = prefs.edit().putLong(KEY_AUTO_LOCK, value).apply()

    var clipboardSeconds: Int
        get() = prefs.getInt(KEY_CLIPBOARD, 30)
        set(value) = prefs.edit().putInt(KEY_CLIPBOARD, value).apply()

    companion object {
        private const val KEY_AUTO_LOCK = "auto_lock_ms"
        private const val KEY_CLIPBOARD = "clipboard_seconds"

        /** Không có lựa chọn "Không bao giờ" (đã thống nhất bỏ — trái nguyên tắc bảo mật). */
        val AUTO_LOCK_OPTIONS: List<Pair<Long, String>> = listOf(
            0L to "Ngay khi rời app",
            60_000L to "1 phút",
            5 * 60_000L to "5 phút",
            15 * 60_000L to "15 phút",
            30 * 60_000L to "30 phút",
        )

        val CLIPBOARD_OPTIONS: List<Pair<Int, String>> = listOf(
            10 to "10 giây",
            30 to "30 giây",
            60 to "1 phút",
            120 to "2 phút",
        )

        fun autoLockLabel(ms: Long) = AUTO_LOCK_OPTIONS.firstOrNull { it.first == ms }?.second ?: "${ms / 60_000} phút"
        fun clipboardLabel(s: Int) = CLIPBOARD_OPTIONS.firstOrNull { it.first == s }?.second ?: "$s giây"
    }
}
