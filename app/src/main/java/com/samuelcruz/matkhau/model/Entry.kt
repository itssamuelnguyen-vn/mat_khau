package com.samuelcruz.matkhau.model

import kotlinx.serialization.Serializable

@Serializable
enum class EntryType { LOGIN, NOTE }

@Serializable
data class CustomField(
    val name: String,
    val value: String,
    val hidden: Boolean = true,
) {
    override fun toString(): String = "CustomField(name=$name)"   // không in giá trị
}

/** Một mật khẩu cũ: [changedAt] = lúc nó bị thay bằng mật khẩu khác (spec 3.2). Mã hóa cùng mục. */
@Serializable
data class PasswordHistoryItem(
    val password: String,
    val changedAt: Long,
) {
    override fun toString(): String = "PasswordHistoryItem(changedAt=$changedAt)"
}

/**
 * Một mục trong vault. TOÀN BỘ đối tượng này (kể cả tiêu đề, URL, tag, ngày tạo)
 * được serialize thành JSON rồi mã hóa AES-GCM trước khi ghi xuống SQLite.
 * Trong DB chỉ có: id ngẫu nhiên, nonce, ciphertext.
 */
@Serializable
data class Entry(
    val id: String,
    val type: EntryType = EntryType.LOGIN,
    val title: String,
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val totp: String = "",
    val notes: String = "",
    val category: String = Categories.PERSONAL,
    val tags: List<String> = emptyList(),
    val customFields: List<CustomField> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    // Thêm ở 0.2.0. Có giá trị mặc định nên mục / backup cũ vẫn đọc được.
    val favorite: Boolean = false,
    /** Lúc đặt mật khẩu hiện tại; 0 = không rõ (mục cũ) => dùng [createdAt]. */
    val passwordUpdatedAt: Long = 0L,
    /** Mật khẩu cũ, mới nhất trước, tối đa [MAX_HISTORY]. */
    val passwordHistory: List<PasswordHistoryItem> = emptyList(),
) {
    /** data class tự sinh toString() in cả mật khẩu/TOTP/ghi chú: ghi đè để không bao giờ lộ qua log hay lỗi. */
    override fun toString(): String = "Entry(id=$id, type=$type)"

    /**
     * Mật khẩu hiện tại được đặt từ lúc nào (để phát hiện mật khẩu lâu chưa đổi). Mục từ 0.1.x chưa có
     * [passwordUpdatedAt]: dùng [updatedAt] (lần sửa cuối luôn mới hơn hoặc bằng lần đổi mật khẩu cuối),
     * để không báo nhầm "lâu chưa đổi". 0 = không rõ.
     */
    fun passwordSince(): Long = if (passwordUpdatedAt > 0) passwordUpdatedAt else updatedAt

    /**
     * Đổi sang [newPassword]: mật khẩu hiện tại (nếu có) vào đầu lịch sử. Mật khẩu mới trùng một bản trong
     * lịch sử (khôi phục bản cũ) thì bản đó được bỏ khỏi lịch sử. Không đổi gì nếu mật khẩu giống hệt.
     */
    fun withPassword(newPassword: String, now: Long, setAt: Long = now): Entry {
        if (newPassword == password) return this
        val older = passwordHistory.filterNot { it.password == newPassword }
        val history = if (password.isEmpty()) older else listOf(PasswordHistoryItem(password, now)) + older
        return copy(password = newPassword, passwordUpdatedAt = setAt, passwordHistory = history.take(MAX_HISTORY))
    }

    companion object {
        const val MAX_HISTORY = 20
    }

    /** Tìm kiếm trong RAM sau khi mở khóa. Không tìm theo mật khẩu/TOTP/ghi chú (spec 2.11). */
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().removePrefix("#").lowercase()
        return title.lowercase().contains(q) ||
            username.lowercase().contains(q) ||
            url.lowercase().contains(q) ||
            Categories.label(category).lowercase().contains(q) ||
            tags.any { it.lowercase().contains(q) }
    }
}

/** Nội dung được mã hóa bên trong file backup. */
@Serializable
data class BackupPayload(
    val formatVersion: Int = 1,
    val exportedAt: Long,
    val entries: List<Entry>,
)

object Categories {
    const val PERSONAL = "personal"
    const val WORK = "work"
    const val FINANCE = "finance"
    const val SOCIAL = "social"
    const val DEVELOPMENT = "development"
    const val OTHER = "other"

    val all = listOf(PERSONAL, WORK, FINANCE, SOCIAL, DEVELOPMENT, OTHER)

    fun label(id: String): String = when (id) {
        PERSONAL -> "Cá nhân"
        WORK -> "Công việc"
        FINANCE -> "Tài chính"
        SOCIAL -> "Mạng xã hội"
        DEVELOPMENT -> "Lập trình"
        OTHER -> "Khác"
        else -> id
    }
}
