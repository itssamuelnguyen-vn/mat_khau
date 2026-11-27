package com.samuelcruz.matkhau.core.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Đọc mã QR từ một khung hình độ sáng (kênh Y của camera) bằng ZXing core — thư viện Java thuần,
 * chạy hoàn toàn offline (không dùng ML Kit / Google Play Services).
 *
 * Kotlin thuần, không phụ thuộc Android: test được trên JVM. Ảnh chỉ nằm trong RAM; người gọi
 * ghi đè mảng [luminance] bằng 0 sau khi dùng.
 */
object QrDecoder {
    private val HINTS = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /**
     * @param luminance mỗi byte là độ sáng 1 pixel; mỗi hàng dài [rowStride] byte (có thể > [width]).
     * @return nội dung mã QR, hoặc null nếu khung hình không có mã QR đọc được.
     */
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        if (luminance.size < rowStride * (height - 1) + width) return null
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), HINTS).text
        } catch (e: NotFoundException) {
            null
        } catch (e: ChecksumException) {
            null
        } catch (e: com.google.zxing.FormatException) {
            null
        }
    }
}
