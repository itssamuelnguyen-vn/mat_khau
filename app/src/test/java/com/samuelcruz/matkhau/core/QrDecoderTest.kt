package com.samuelcruz.matkhau.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.samuelcruz.matkhau.core.qr.QrDecoder
import com.samuelcruz.matkhau.core.totp.Totp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class QrDecoderTest {

    /** Vẽ mã QR thành khung hình độ sáng giống kênh Y của camera (hàng dài hơn ảnh: rowStride > width). */
    private fun frame(text: String, size: Int, rowStride: Int): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val data = ByteArray(rowStride * size) { 0x55 }
        for (y in 0 until size) for (x in 0 until size) {
            data[y * rowStride + x] = if (matrix[x, y]) 0 else 0xFF.toByte()
        }
        return data
    }

    @Test
    fun decodesOtpauthQrFromCameraLikeFrame() {
        val uri = "otpauth://totp/GitHub:user%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub"
        val text = QrDecoder.decode(frame(uri, 320, rowStride = 336), rowStride = 336, width = 320, height = 320)
        assertEquals(uri, text)
        assertTrue(Totp.isValid(text!!))
    }

    @Test
    fun returnsNullWithoutQrOrForBadDimensions() {
        val noise = ByteArray(320 * 240).also { Random(1).nextBytes(it) }
        assertNull(QrDecoder.decode(noise, 320, 320, 240))
        assertNull(QrDecoder.decode(ByteArray(10), 320, 320, 240))
        assertNull(QrDecoder.decode(noise, 100, 320, 240))
    }
}
