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

/** Kết quả đánh giá: số bit ước lượng, mức độ, và lý do (tiếng Việt, để hiện cho người dùng). */
class StrengthResult(val bits: Double, val strength: Strength, val warnings: List<String>)

/**
 * Ước lượng độ mạnh mật khẩu, hoàn toàn offline (spec 3.5). Không chỉ đếm "8 ký tự + hoa + số":
 * mật khẩu được cắt thành các đoạn, mỗi đoạn dễ đoán chỉ được tính vài bit:
 *  - mật khẩu / từ phổ biến (kể cả kiểu leet: P@ssw0rd), tiếng Anh lẫn tiếng Việt không dấu,
 *  - chuỗi bàn phím (qwerty, asdf, 1qaz...), dãy liên tiếp (abc, 123, 987), ký tự lặp (aaa),
 *  - năm (1990, 2024) và ngày sinh (01011990, 010190, 19900101).
 * Ký tự còn lại tính log2(kích thước bảng ký tự). Đây vẫn là ước lượng, không phải phép đo chính xác.
 */
object StrengthEstimator {
    /** Mật khẩu hay gặp nhất (danh sách rò rỉ công khai) + vài từ hay dùng ở Việt Nam. Viết thường. */
    private val COMMON: Set<String> = setOf(
        "123456", "password", "123456789", "12345678", "12345", "qwerty", "1234567", "111111", "1234567890",
        "123123", "abc123", "1234", "password1", "iloveyou", "1q2w3e4r", "000000", "qwerty123", "zaq12wsx",
        "dragon", "sunshine", "princess", "letmein", "654321", "monkey", "1qaz2wsx", "123321", "qwertyuiop",
        "superman", "asdfghjkl", "football", "baseball", "welcome", "admin", "administrator", "master", "shadow",
        "michael", "jennifer", "hello", "freedom", "whatever", "trustno1", "starwars", "login", "access",
        "mustang", "batman", "charlie", "donald", "loveme", "killer", "hunter", "ninja", "azerty", "secret",
        "123qwe", "qwe123", "abcd1234", "aaaaaa", "google", "facebook", "samsung", "iphone", "android",
        "computer", "internet", "changeme", "default", "test", "guest", "root", "toor", "pass", "abcdef",
        "abcdefg", "147258369", "159753", "987654321", "112233", "121212", "666666", "888888", "999999",
        "123654", "789456", "456789", "pokemon", "soccer", "jordan", "liverpool", "chelsea", "arsenal",
        "matkhau", "anhyeuem", "emyeuanh", "yeuem", "yeuanh", "iloveu", "vietnam", "hanoi", "saigon",
        "zalo", "matkhaucuatoi", "hoilamgi", "conmeo", "concho", "baby", "honey", "lovely", "family",
    )

    private val KEYBOARD_ROWS = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm", "1qaz2wsx3edc4rfv5tgb")
    private const val MIN_MATCH = 4
    private val COMMON_BITS = log2(COMMON.size.toDouble()) + 1   // ~8 bit: chọn 1 từ trong danh sách
    private val KEYBOARD_BITS = log2(KEYBOARD_ROWS.sumOf { it.length } * 2.0) + 2

    fun analyze(password: String): StrengthResult {
        if (password.isEmpty()) return StrengthResult(0.0, Strength.VERY_WEAK, emptyList())
        val warnings = linkedSetOf<String>()
        val lower = password.lowercase()
        val variants = deleet(lower)

        if (variants.any { it in COMMON }) {
            warnings += "Là mật khẩu phổ biến, bị dò ra đầu tiên"
            return result(COMMON_BITS - 3, warnings)
        }

        var pool = 0
        if (password.any { it.isLowerCase() }) pool += 26
        if (password.any { it.isUpperCase() }) pool += 26
        if (password.any { it.isDigit() }) pool += 10
        if (password.any { !it.isLetterOrDigit() }) pool += 33
        if (password.any { it.code > 127 }) pool += 50
        val perChar = log2(max(pool, 2).toDouble())

        var bits = 0.0
        var i = 0
        while (i < lower.length) {
            val m = longestMatch(lower, variants, i, perChar)
            if (m != null) {
                bits += m.bits
                warnings += m.warning
                i += m.length
            } else {
                bits += perChar
                i++
            }
        }
        if (password.length < 12) warnings += "Ngắn hơn 12 ký tự"
        return result(bits, warnings)
    }

    fun estimateBits(password: String): Double = analyze(password).bits

    fun estimate(password: String): Strength = analyze(password).strength

    // ------------------------------------------------------------------------------------------

    private class Match(val length: Int, val bits: Double, val warning: String)

    private fun result(bits: Double, warnings: Collection<String>): StrengthResult {
        val strength = when {
            bits < 35 -> Strength.VERY_WEAK
            bits < 50 -> Strength.WEAK
            bits < 65 -> Strength.FAIR
            bits < 85 -> Strength.STRONG
            else -> Strength.VERY_STRONG
        }
        return StrengthResult(bits, strength, warnings.toList())
    }

    /** Đoạn dễ đoán dài nhất bắt đầu tại [start], hoặc null. */
    private fun longestMatch(lower: String, variants: List<String>, start: Int, perChar: Double): Match? {
        val candidates = mutableListOf<Match>()

        // Từ / mật khẩu phổ biến nằm bên trong (kể cả dạng leet)
        for (v in variants) for (w in COMMON) {
            if (w.length >= MIN_MATCH && v.startsWith(w, start)) {
                // Không trích từ đã khớp: nó là một phần của mật khẩu, và cảnh báo hiện ngay dưới ô đang che.
                candidates += Match(w.length, COMMON_BITS, "Chứa một từ / mật khẩu phổ biến")
            }
        }
        // Chuỗi bàn phím, xuôi hoặc ngược
        for (row in KEYBOARD_ROWS) for (r in listOf(row, row.reversed())) {
            var len = 0
            while (start + len < lower.length) {
                val idx = r.indexOf(lower.substring(start, start + len + 1))
                if (idx < 0) break
                len++
            }
            if (len >= MIN_MATCH) candidates += Match(len, KEYBOARD_BITS + log2(len.toDouble()), "Có chuỗi phím liền nhau")
        }
        // Ký tự lặp (aaa) và dãy liên tiếp (abc, 321)
        run {
            var len = 1
            while (start + len < lower.length && lower[start + len] == lower[start]) len++
            if (len >= 3) candidates += Match(len, perChar + log2(len.toDouble()), "Lặp một ký tự nhiều lần")
        }
        for (step in listOf(1, -1)) {
            var len = 1
            while (start + len < lower.length && lower[start + len].code - lower[start + len - 1].code == step) len++
            if (len >= 3) candidates += Match(len, perChar + log2(len.toDouble()) + 1, "Có dãy ký tự liên tiếp")
        }
        // Ngày sinh / năm
        dateLength(lower, start)?.let { candidates += Match(it, log2(365.0 * 100) + 1, "Giống ngày tháng (ngày sinh?)") }
        if (isYear(lower, start)) candidates += Match(4, log2(140.0), "Có số năm (vd. năm sinh)")

        return candidates.maxWithOrNull(compareBy<Match> { it.length }.thenBy { -it.bits })
    }

    private fun isYear(s: String, i: Int): Boolean {
        if (i + 4 > s.length || !s.substring(i, i + 4).all { it.isDigit() }) return false
        val y = s.substring(i, i + 4).toInt()
        return y in 1900..2039
    }

    /** ddmmyyyy / yyyymmdd (8 số) hoặc ddmmyy (6 số) hợp lệ bắt đầu tại [i]. */
    private fun dateLength(s: String, i: Int): Int? {
        fun digits(n: Int) = i + n <= s.length && s.substring(i, i + n).all { it.isDigit() }
        fun dm(d: Int, m: Int) = d in 1..31 && m in 1..12
        if (digits(8)) {
            val t = s.substring(i, i + 8)
            val dmy = dm(t.substring(0, 2).toInt(), t.substring(2, 4).toInt()) && t.substring(4).toInt() in 1900..2039
            val ymd = t.substring(0, 4).toInt() in 1900..2039 && dm(t.substring(6).toInt(), t.substring(4, 6).toInt())
            if (dmy || ymd) return 8
        }
        if (digits(6)) {
            val t = s.substring(i, i + 6)
            if (dm(t.substring(0, 2).toInt(), t.substring(2, 4).toInt())) return 6
        }
        return null
    }

    /** Các cách đọc "leet" (P@ssw0rd → password). Số 1 có thể là i hoặc l. */
    private fun deleet(s: String): List<String> {
        val base = s.map {
            when (it) {
                '@', '4' -> 'a'
                '0' -> 'o'
                '3' -> 'e'
                '$', '5' -> 's'
                '7' -> 't'
                '!' -> 'i'
                else -> it
            }
        }.joinToString("")
        return listOf(s, base.replace('1', 'i'), base.replace('1', 'l')).distinct()
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)
}
