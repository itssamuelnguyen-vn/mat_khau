package com.samuelcruz.matkhau.security

import android.content.Context

/**
 * Cài đặt KHÔNG nhạy cảm (thời gian tự khóa, thời gian xóa clipboard, mốc backup, số lần nhập sai). Lưu bằng
 * SharedPreferences. Số lần nhập sai ghi bằng commit() (đồng bộ): app bị kill ngay sau lần sai vẫn không mất bộ đếm.
 */
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

    // ---------------------------------------------------------------- backup (0.5.0): chỉ là mốc thời gian / số đếm

    /** Lúc xuất backup thành công gần nhất (tay hoặc tự động), 0 = chưa từng. */
    var lastBackupAt: Long
        get() = prefs.getLong(KEY_LAST_BACKUP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_BACKUP, value).apply()

    /** Số thay đổi (thêm / sửa / xóa) chưa có trong backup nào. */
    val changesSinceBackup: Int get() = prefs.getInt(KEY_CHANGES, 0)

    @Synchronized
    fun addChanges(n: Int) = prefs.edit().putInt(KEY_CHANGES, (changesSinceBackup + n).coerceAtMost(1_000_000)).apply()

    /** Vừa backup xong: trừ đi số thay đổi đã có trong bản backup đó (thay đổi xảy ra trong lúc ghi vẫn được giữ lại). */
    @Synchronized
    fun markBackedUp(changesIncluded: Int, at: Long) {
        prefs.edit()
            .putInt(KEY_CHANGES, (changesSinceBackup - changesIncluded).coerceAtLeast(0))
            .putLong(KEY_LAST_BACKUP, at)
            .apply()
    }

    @Synchronized
    fun resetBackupState(lastBackupAt: Long) =
        prefs.edit().putInt(KEY_CHANGES, 0).putLong(KEY_LAST_BACKUP, lastBackupAt).apply()

    var backupReminderSnoozedUntil: Long
        get() = prefs.getLong(KEY_REMINDER_SNOOZE, 0L)
        set(value) = prefs.edit().putLong(KEY_REMINDER_SNOOZE, value).apply()

    /** Thư mục tự backup (Storage Access Framework, đã xin quyền giữ lâu dài), null = tắt. */
    var autoBackupTreeUri: String?
        get() = prefs.getString(KEY_AUTO_BACKUP_URI, null)
        set(value) = prefs.edit().putString(KEY_AUTO_BACKUP_URI, value).apply()

    var autoBackupLastAt: Long
        get() = prefs.getLong(KEY_AUTO_BACKUP_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_AUTO_BACKUP_AT, value).apply()

    /** Document id của bản tự backup ĐẦY ĐỦ gần nhất (không bao giờ bị dọn). */
    var autoBackupLastCompleteDoc: String?
        get() = prefs.getString(KEY_AUTO_BACKUP_COMPLETE, null)
        set(value) = prefs.edit().putString(KEY_AUTO_BACKUP_COMPLETE, value).apply()

    /** Lỗi của lần tự backup gần nhất (thông báo cho người dùng), null = lần gần nhất thành công. */
    var autoBackupLastError: String?
        get() = prefs.getString(KEY_AUTO_BACKUP_ERROR, null)
        set(value) = prefs.edit().putString(KEY_AUTO_BACKUP_ERROR, value).apply()

    // ---------------------------------------------------------------- nhập sai mật khẩu (0.5.0)

    val unlockFailures: Int get() = prefs.getInt(KEY_UNLOCK_FAILURES, 0)

    /** elapsedRealtime lúc sai lần cuối + số lần khởi động máy lúc đó (xem UnlockThrottle). */
    val unlockLastFailureAt: Long get() = prefs.getLong(KEY_UNLOCK_LAST_AT, 0L)
    val unlockLastBoot: Int get() = prefs.getInt(KEY_UNLOCK_LAST_BOOT, 0)

    /** Ghi cả ba giá trị trong MỘT lần commit() (đồng bộ): app bị kill giữa chừng không để lại trạng thái lệch. */
    fun setUnlockState(failures: Int, lastFailureAt: Long, lastBoot: Int) {
        prefs.edit()
            .putInt(KEY_UNLOCK_FAILURES, failures)
            .putLong(KEY_UNLOCK_LAST_AT, lastFailureAt)
            .putInt(KEY_UNLOCK_LAST_BOOT, lastBoot)
            .commit()
    }

    companion object {
        private const val KEY_LAST_BACKUP = "last_backup_at"
        private const val KEY_CHANGES = "changes_since_backup"
        private const val KEY_REMINDER_SNOOZE = "backup_reminder_snoozed_until"
        private const val KEY_AUTO_BACKUP_URI = "auto_backup_tree"
        private const val KEY_AUTO_BACKUP_AT = "auto_backup_last_at"
        private const val KEY_AUTO_BACKUP_ERROR = "auto_backup_last_error"
        private const val KEY_AUTO_BACKUP_COMPLETE = "auto_backup_last_complete_doc"
        private const val KEY_UNLOCK_FAILURES = "unlock_failures"
        private const val KEY_UNLOCK_LAST_AT = "unlock_last_failure_at"
        private const val KEY_UNLOCK_LAST_BOOT = "unlock_last_boot"
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
