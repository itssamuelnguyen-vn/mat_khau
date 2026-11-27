package com.samuelcruz.matkhau.core.format

/** Base32 theo RFC 4648 (bảng chữ A–Z, 2–7). Dùng cho secret TOTP và Recovery Key. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val LOOKUP = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { i, c ->
            table[c.code] = i
            table[c.lowercaseChar().code] = i
        }
    }

    fun encode(data: ByteArray, padding: Boolean = true): String {
        val sb = StringBuilder((data.size + 4) / 5 * 8)
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        if (padding) while (sb.length % 8 != 0) sb.append('=')
        return sb.toString()
    }

    /** Bỏ qua khoảng trắng, gạch ngang và dấu '='; không phân biệt hoa thường. */
    fun decode(input: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in input) {
            if (c == '=' || c == ' ' || c == '-' || c == '\n' || c == '\t') continue
            val v = if (c.code < 128) LOOKUP[c.code] else -1
            // Không trích ký tự vào thông báo: chuỗi đang giải mã có thể là secret.
            if (v < 0) throw FormatException("Có ký tự không thuộc bảng Base32")
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
            buffer = buffer and 0xFF // chỉ giữ các bit còn dùng, tránh tràn
        }
        return out.toByteArray()
    }
}
