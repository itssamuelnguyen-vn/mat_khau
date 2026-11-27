package com.samuelcruz.matkhau.core.generator

import kotlin.math.ln
import kotlin.math.max

enum class Strength(val label: String) {
    VERY_WEAK("Rất yếu"),
    WEAK("Yếu"),
    FAIR("Tạm được"),
    STRONG("Mạnh"),
    VERY_STRONG("Rất mạnh"),
}

/**
 * Ước lượng THÔ độ mạnh mật khẩu chính (Phase 1).
 * Trừ điểm cho lặp ký tự, chuỗi tuần tự và mật khẩu phổ biến.
 * Phase 2 sẽ thay bằng bộ đánh giá đầy đủ hơn (từ điển, mẫu bàn phím...).
 */
object StrengthEstimator {
    private val COMMON = setOf(
        "password", "123456", "12345678", "123456789", "1234567890", "qwerty", "abc123",
        "iloveyou", "admin", "welcome", "letmein", "matkhau", "anhyeuem", "111111", "000000",
    )

    fun estimateBits(password: String): Double {
        if (password.isEmpty()) return 0.0
        if (password.lowercase() in COMMON) return 5.0

        var pool = 0
        if (password.any { it.isLowerCase() }) pool += 26
        if (password.any { it.isUpperCase() }) pool += 26
        if (password.any { it.isDigit() }) pool += 10
        if (password.any { !it.isLetterOrDigit() }) pool += 33
        if (password.any { it.code > 127 }) pool += 50

        // Đếm ký tự "hiệu dụng": ký tự lặp lại hoặc nối tiếp (abc, 123) chỉ tính một nửa.
        var effective = 1.0
        for (i in 1 until password.length) {
            val prev = password[i - 1]
            val cur = password[i]
            effective += when {
                cur == prev -> 0.25
                cur.code - prev.code == 1 || prev.code - cur.code == 1 -> 0.5
                else -> 1.0
            }
        }
        return effective * ln(max(pool, 2).toDouble()) / ln(2.0)
    }

    fun estimate(password: String): Strength {
        val bits = estimateBits(password)
        return when {
            bits < 35 -> Strength.VERY_WEAK
            bits < 50 -> Strength.WEAK
            bits < 65 -> Strength.FAIR
            bits < 85 -> Strength.STRONG
            else -> Strength.VERY_STRONG
        }
    }
}
