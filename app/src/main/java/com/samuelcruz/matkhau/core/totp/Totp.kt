package com.samuelcruz.matkhau.core.totp

import com.samuelcruz.matkhau.core.format.Base32
import com.samuelcruz.matkhau.core.format.FormatException
import java.net.URLDecoder
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class TotpAlgorithm(val macName: String) {
    SHA1("HmacSHA1"),
    SHA256("HmacSHA256"),
    SHA512("HmacSHA512"),
}

class TotpConfig(
    val secret: ByteArray,
    val digits: Int = 6,
    val period: Int = 30,
    val algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
    val issuer: String? = null,
    val account: String? = null,
) {
    init {
        // period = 0 sẽ chia cho 0; digits > 9 làm tràn phép modulo của HOTP.
        require(digits in 6..8) { "digits" }
        require(period in 1..300) { "period" }
        require(secret.isNotEmpty()) { "secret" }
    }
}

/** TOTP theo RFC 6238 (dựa trên HOTP RFC 4226), tính hoàn toàn offline. */
object Totp {

    fun generate(config: TotpConfig, unixSeconds: Long): String {
        val counter = Math.floorDiv(unixSeconds, config.period.toLong())
        val message = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance(config.algorithm.macName)
        mac.init(SecretKeySpec(config.secret, config.algorithm.macName))
        val hash = mac.doFinal(message)

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        var modulus = 1L
        repeat(config.digits) { modulus *= 10 }
        return (binary.toLong() % modulus).toString().padStart(config.digits, '0')
    }

    fun secondsRemaining(config: TotpConfig, unixSeconds: Long): Int =
        (config.period - Math.floorMod(unixSeconds, config.period.toLong())).toInt()

    /**
     * Nhận secret Base32 thuần ("JBSWY3DPEHPK3PXP") hoặc URI otpauth://totp/...
     * (chuỗi bạn thấy khi dùng app quét QR để xem nội dung).
     */
    fun parse(input: String): TotpConfig {
        val text = input.trim()
        if (text.isEmpty()) throw FormatException("Secret TOTP trống")
        if (!text.startsWith("otpauth://", ignoreCase = true)) {
            return TotpConfig(secret = decodeSecret(text))
        }

        val afterScheme = text.substring("otpauth://".length)
        val type = afterScheme.substringBefore('/').lowercase()
        if (type != "totp") throw FormatException("Chỉ hỗ trợ otpauth://totp (không hỗ trợ $type)")
        val label = decode(afterScheme.substringAfter('/', "").substringBefore('?'))
        val query = afterScheme.substringAfter('?', "")
        val params = query.split('&')
            .filter { it.contains('=') }
            .associate { decode(it.substringBefore('=')).lowercase() to decode(it.substringAfter('=')) }

        val secret = decodeSecret(params["secret"] ?: throw FormatException("URI thiếu tham số secret"))
        // Giá trị không đọc được thì báo lỗi, không lặng lẽ dùng mặc định (mã sinh ra sẽ sai).
        val digits = params["digits"]?.let { it.toIntOrNull() ?: throw FormatException("digits không hợp lệ") } ?: 6
        val period = params["period"]?.let { it.toIntOrNull() ?: throw FormatException("period không hợp lệ") } ?: 30
        val algorithm = when (params["algorithm"]?.uppercase()) {
            null, "SHA1" -> TotpAlgorithm.SHA1
            "SHA256" -> TotpAlgorithm.SHA256
            "SHA512" -> TotpAlgorithm.SHA512
            else -> throw FormatException("Thuật toán TOTP không hỗ trợ")
        }
        if (digits !in 6..8) throw FormatException("Số chữ số TOTP phải từ 6 đến 8")
        if (period !in 1..300) throw FormatException("Chu kỳ TOTP không hợp lệ")

        val issuer = params["issuer"] ?: label.substringBefore(':', "").ifBlank { null }
        val account = if (label.contains(':')) label.substringAfter(':').trim() else label.ifBlank { null }
        return TotpConfig(secret, digits, period, algorithm, issuer, account)
    }

    /** Không bao giờ ném exception: được gọi trong lúc vẽ form và trên luồng phân tích camera. */
    fun isValid(input: String): Boolean = try {
        parse(input); true
    } catch (e: Exception) {
        false
    }

    /** Như [parse] nhưng trả null thay vì ném exception. */
    fun parseOrNull(input: String): TotpConfig? = try {
        parse(input)
    } catch (e: Exception) {
        null
    }

    private fun decodeSecret(text: String): ByteArray {
        val secret = Base32.decode(text.replace(" ", ""))
        if (secret.size < 10) throw FormatException("Secret TOTP quá ngắn")
        return secret
    }

    /** URLDecoder ném IllegalArgumentException với "%" lỗi (vd. "%zz") — đổi thành FormatException để form không crash. */
    private fun decode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        throw FormatException("URI otpauth có ký tự % không hợp lệ")
    }
}
