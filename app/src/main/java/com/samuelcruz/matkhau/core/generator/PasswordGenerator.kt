package com.samuelcruz.matkhau.core.generator

import com.samuelcruz.matkhau.core.crypto.Bytes
import java.security.SecureRandom
import kotlin.math.ln

data class PasswordOptions(
    val length: Int = 20,
    val uppercase: Boolean = true,
    val lowercase: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    val excludeAmbiguous: Boolean = false,
)

data class PassphraseOptions(
    val words: Int = 6,
    val separator: String = "-",
    val capitalize: Boolean = true,
    val includeNumber: Boolean = true,
)

/**
 * Sinh mật khẩu bằng SecureRandom. SecureRandom.nextInt(n) cho phân phối đều
 * (không bị lệch do phép modulo).
 */
object PasswordGenerator {
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.<>?/~"
    private const val AMBIGUOUS = "Il1O0o|`'\";:,."

    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 128

    private fun classes(o: PasswordOptions): List<String> {
        fun filter(s: String) = if (o.excludeAmbiguous) s.filterNot { it in AMBIGUOUS } else s
        return buildList {
            if (o.uppercase) add(filter(UPPER))
            if (o.lowercase) add(filter(LOWER))
            if (o.digits) add(filter(DIGITS))
            if (o.symbols) add(filter(SYMBOLS))
        }.filter { it.isNotEmpty() }
    }

    fun generate(options: PasswordOptions, random: SecureRandom = Bytes.secureRandom): String {
        val sets = classes(options)
        require(sets.isNotEmpty()) { "Chọn ít nhất một loại ký tự" }
        require(options.length in MIN_LENGTH..MAX_LENGTH) { "Độ dài phải từ $MIN_LENGTH đến $MAX_LENGTH" }
        val pool = sets.joinToString("")

        // Lấy mẫu loại bỏ (rejection sampling): sinh lại tới khi có đủ mọi loại ký tự đã chọn.
        // Kết quả phân phối đều trên tập mật khẩu hợp lệ.
        repeat(10_000) {
            val chars = CharArray(options.length) { pool[random.nextInt(pool.length)] }
            if (sets.all { set -> chars.any { it in set } }) return String(chars)
        }
        throw IllegalStateException("Không sinh được mật khẩu thỏa điều kiện")
    }

    fun entropyBits(options: PasswordOptions): Double {
        val poolSize = classes(options).sumOf { it.length }
        return if (poolSize == 0) 0.0 else options.length * log2(poolSize.toDouble())
    }

    fun passphrase(wordList: List<String>, options: PassphraseOptions, random: SecureRandom = Bytes.secureRandom): String {
        require(wordList.size >= 1024) { "Danh sách từ quá ngắn" }
        require(options.words in 3..20)
        val words = MutableList(options.words) {
            val w = wordList[random.nextInt(wordList.size)]
            if (options.capitalize) w.replaceFirstChar { c -> c.uppercaseChar() } else w
        }
        if (options.includeNumber) {
            val i = random.nextInt(words.size)
            words[i] = words[i] + random.nextInt(10)
        }
        return words.joinToString(options.separator)
    }

    fun passphraseEntropyBits(wordListSize: Int, options: PassphraseOptions): Double {
        var bits = options.words * log2(wordListSize.toDouble())
        if (options.includeNumber) bits += log2(10.0)
        return bits
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)
}
