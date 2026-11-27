package com.samuelcruz.matkhau.core.format

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** File header/backup bị hỏng hoặc không đúng định dạng. */
class FormatException(message: String) : Exception(message)

/** Ghi dữ liệu nhị phân đơn giản: số big-endian + mảng byte có tiền tố độ dài. */
class ByteWriter {
    private val buffer = ByteArrayOutputStream()
    private val out = DataOutputStream(buffer)

    fun raw(bytes: ByteArray) = apply { out.write(bytes) }
    fun u8(value: Int) = apply { require(value in 0..0xFF); out.writeByte(value) }
    fun u16(value: Int) = apply { require(value in 0..0xFFFF); out.writeShort(value) }
    fun u32(value: Int) = apply { require(value >= 0); out.writeInt(value) }
    fun bytes(value: ByteArray) = apply { out.writeInt(value.size); out.write(value) }

    fun toByteArray(): ByteArray {
        out.flush()
        return buffer.toByteArray()
    }
}

/** Đọc dữ liệu nhị phân, kiểm tra biên ở mọi bước để file hỏng/giả mạo bị từ chối an toàn. */
class ByteReader(private val data: ByteArray) {
    var position: Int = 0
        private set

    private fun need(n: Int) {
        if (n < 0 || position + n > data.size) throw FormatException("Dữ liệu bị cắt cụt")
    }

    fun raw(n: Int): ByteArray {
        need(n)
        return data.copyOfRange(position, position + n).also { position += n }
    }

    fun u8(): Int {
        need(1)
        return (data[position++].toInt() and 0xFF)
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun u32(): Int {
        val v = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        if (v < 0) throw FormatException("Giá trị không hợp lệ")
        return v
    }

    fun bytes(maxLength: Int): ByteArray {
        val len = u32()
        if (len > maxLength) throw FormatException("Trường dữ liệu quá dài")
        return raw(len)
    }

    /** Bản sao các byte từ đầu đến vị trí hiện tại (dùng làm AAD). */
    fun consumedSoFar(): ByteArray = data.copyOfRange(0, position)

    fun requireEnd() {
        if (position != data.size) throw FormatException("Có dữ liệu thừa ở cuối")
    }
}
