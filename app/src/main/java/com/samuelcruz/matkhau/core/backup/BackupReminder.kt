package com.samuelcruz.matkhau.core.backup

/** Khi nào nhắc xuất backup. Không nhắc khi vault trống hay chưa có gì thay đổi từ lần backup cuối. */
object BackupReminder {
    const val REMIND_AFTER_DAYS = 30
    const val REMIND_AFTER_CHANGES = 10
    const val SNOOZE_MS = 7L * 24 * 60 * 60 * 1000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Lời nhắc, hoặc null. [lastBackupAt] = 0: chưa backup lần nào (hoặc bản cũ chưa ghi nhận).
     * [changes] = số lần thêm / sửa / xóa từ lần backup cuối.
     */
    fun message(entryCount: Int, lastBackupAt: Long, changes: Int, snoozedUntil: Long, now: Long): String? {
        if (entryCount == 0 || now < snoozedUntil) return null
        if (lastBackupAt <= 0L) return "Bạn chưa xuất backup lần nào. Máy hỏng hay mất là mất hết dữ liệu."
        if (changes <= 0) return null
        val days = ((now - lastBackupAt) / DAY_MS).toInt()
        return when {
            days >= REMIND_AFTER_DAYS -> "Đã $days ngày chưa backup ($changes thay đổi chưa có trong backup)."
            changes > REMIND_AFTER_CHANGES -> "Có $changes thay đổi chưa có trong backup."
            else -> null
        }
    }
}
