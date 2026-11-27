package com.samuelcruz.matkhau.core.health

import com.samuelcruz.matkhau.core.generator.Strength
import com.samuelcruz.matkhau.core.generator.StrengthEstimator
import com.samuelcruz.matkhau.model.Entry
import com.samuelcruz.matkhau.model.EntryType

/** Kết quả kiểm tra sức khỏe mật khẩu (spec 3.4–3.7). Chỉ chứa tham chiếu tới mục đang mở trong RAM. */
class HealthReport(
    /** Số mục đăng nhập có mật khẩu (mẫu số của điểm). */
    val loginCount: Int,
    val weak: List<Pair<Entry, Strength>>,
    /** Mỗi nhóm = các mục dùng CHUNG một mật khẩu. */
    val reused: List<List<Entry>>,
    val old: List<Entry>,
    val missingTotp: List<Entry>,
    val missingUrl: List<Entry>,
    /** 0–100: phần trăm mục đăng nhập không yếu, không trùng, không quá cũ. */
    val score: Int,
) {
    /** Id các mục có vấn đề bảo mật (yếu / trùng / cũ). */
    val flaggedIds: Set<String> =
        (weak.map { it.first.id } + reused.flatten().map { it.id } + old.map { it.id }).toSet()
}

/**
 * Tính hoàn toàn trong RAM, trên máy (spec 3.6: mật khẩu không bao giờ rời máy để so sánh).
 * Mục ghi chú và mục không có mật khẩu được bỏ qua.
 */
object PasswordHealth {
    const val OLD_AFTER_MS = 365L * 24 * 60 * 60 * 1000   // 1 năm

    fun analyze(entries: List<Entry>, now: Long): HealthReport {
        val logins = entries.filter { it.type == EntryType.LOGIN && it.password.isNotEmpty() }
        val weak = logins.map { it to StrengthEstimator.estimate(it.password) }
            .filter { it.second == Strength.VERY_WEAK || it.second == Strength.WEAK }
        val reused = logins.groupBy { it.password }.values.filter { it.size > 1 }
            .sortedByDescending { it.size }
        val old = logins.filter { it.passwordSince() > 0 && now - it.passwordSince() > OLD_AFTER_MS }
        val missingTotp = logins.filter { it.totp.isBlank() }
        val missingUrl = logins.filter { it.url.isBlank() }

        val report = HealthReport(logins.size, weak, reused, old, missingTotp, missingUrl, score = 0)
        val score = if (logins.isEmpty()) 100 else (logins.size - report.flaggedIds.size) * 100 / logins.size
        return HealthReport(logins.size, weak, reused, old, missingTotp, missingUrl, score)
    }

    /** Số mục KHÁC dùng cùng mật khẩu với [entry]. */
    fun reuseCount(entry: Entry, entries: List<Entry>): Int =
        if (entry.password.isEmpty()) 0
        else entries.count { it.id != entry.id && it.type == EntryType.LOGIN && it.password == entry.password }
}
