package com.samuelcruz.matkhau.core.vault

import com.samuelcruz.matkhau.core.crypto.Bytes
import com.samuelcruz.matkhau.core.format.Base32
import com.samuelcruz.matkhau.core.format.FormatException

/**
 * Recovery Key: 160 bit ngẫu nhiên, hiển thị thành 8 nhóm 4 ký tự Base32.
 * Ví dụ: ABCD-EFGH-IJKL-MNOP-QRST-UVWX-YZ23-4567
 *
 * 160 bit ngẫu nhiên mạnh hơn rất nhiều so với bất kỳ mật khẩu do người nghĩ ra,
 * nên nó không trở thành "cửa sau yếu" của vault (spec 4.6).
 */
object RecoveryKey {
    const val BYTE_LENGTH = 20

    fun generate(): ByteArray = Bytes.random(BYTE_LENGTH)

    fun format(raw: ByteArray): String {
        require(raw.size == BYTE_LENGTH)
        return Base32.encode(raw, padding = false).chunked(4).joinToString("-")
    }

    /**
     * Chuẩn hóa chuỗi người dùng nhập: bỏ khoảng trắng/gạch, in hoa,
     * sửa các ký tự dễ nhầm (0→O, 1→I, 8→B) vì Base32 không dùng 0, 1, 8.
     */
    fun parse(text: String): ByteArray {
        val cleaned = text.uppercase()
            .filter { it.isLetterOrDigit() }
            .map {
                when (it) {
                    '0' -> 'O'
                    '1' -> 'I'
                    '8' -> 'B'
                    else -> it
                }
            }
            .joinToString("")
        if (cleaned.length != 32) throw FormatException("Recovery Key phải có 32 ký tự")
        val raw = Base32.decode(cleaned)
        if (raw.size != BYTE_LENGTH) throw FormatException("Recovery Key không hợp lệ")
        return raw
    }
}
