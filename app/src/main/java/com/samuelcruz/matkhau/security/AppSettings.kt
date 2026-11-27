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

    /**
     * true khi Recovery Key vừa tạo chưa được xác nhận "đã chép" (vd. vault tự khóa giữa chừng).
     * Lần mở khóa sau, app tạo key mới và hiện lại. Không phải bí mật: chỉ là một cờ.
     */
    var recoveryKeyPending: Boolean
        get() = prefs.getBoolean(KEY_RECOVERY_PENDING, false)
        set(value) = prefs.edit().putBoolean(KEY_RECOVERY_PENDING, value).apply()

    /** true sau khi mở bằng Recovery Key: mọi lần mở khóa sau đều bắt đặt mật khẩu chính mới cho tới khi xong. */
    var mustChangePassword: Boolean
        get() = prefs.getBoolean(KEY_MUST_CHANGE_PASSWORD, false)
        set(value) = prefs.edit().putBoolean(KEY_MUST_CHANGE_PASSWORD, value).apply()

    /**
     * Trình duyệt người dùng đã xác nhận cho tự động điền: tên gói -> SHA-256 chứng chỉ ký lúc xác nhận.
     * Không phải bí mật, nhưng quyết định tên miền nào được tin (chống app giả mạo tên gói trình duyệt).
     */
    fun trustedBrowserCert(packageName: String): String? = prefs.getString(KEY_BROWSER_PREFIX + packageName, null)

    fun trustBrowser(packageName: String, certSha256: String) =
        prefs.edit().putString(KEY_BROWSER_PREFIX + packageName, certSha256).apply()

    fun trustedBrowsers(): List<String> =
        prefs.all.keys.filter { it.startsWith(KEY_BROWSER_PREFIX) }.map { it.removePrefix(KEY_BROWSER_PREFIX) }.sorted()

    fun untrustBrowser(packageName: String) = prefs.edit().remove(KEY_BROWSER_PREFIX + packageName).apply()

    /** Bắt xác thực (vân tay / khóa màn hình) mỗi lần hiện, copy hoặc tự điền bí mật (chống trojan Trợ năng). */
    var requireAuthForSecrets: Boolean
        get() = prefs.getBoolean(KEY_REQUIRE_AUTH, false)
        set(value) = prefs.edit().putBoolean(KEY_REQUIRE_AUTH, value).apply()

    companion object {
        private const val KEY_REQUIRE_AUTH = "require_auth_for_secrets"
        private const val KEY_BROWSER_PREFIX = "trusted_browser:"
        private const val KEY_MUST_CHANGE_PASSWORD = "must_change_password"
        private const val KEY_RECOVERY_PENDING = "recovery_key_pending"
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
