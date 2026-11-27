package com.samuelcruz.matkhau.core.crypto

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Kết quả mã hóa: nonce + ciphertext (đã kèm tag xác thực 16 byte ở cuối). */
class SealedBox(val nonce: ByteArray, val ciphertext: ByteArray)

/** Giải mã thất bại: sai khóa, dữ liệu bị sửa, hoặc AAD không khớp. Không tiết lộ lý do cụ thể. */
class DecryptionFailedException : Exception("Không giải mã được dữ liệu (sai khóa hoặc dữ liệu đã bị sửa)")

/**
 * AES-256-GCM: vừa bảo mật (confidentiality) vừa toàn vẹn (integrity).
 * Mỗi lần mã hóa dùng nonce ngẫu nhiên 96-bit mới.
 * AAD (associated data) gắn ciphertext vào ngữ cảnh (vault nào, mục nào) để chống tráo dữ liệu.
 */
object Aead {
    const val KEY_LENGTH = 32
    const val NONCE_LENGTH = 12
    const val TAG_BITS = 128
    const val TAG_LENGTH = TAG_BITS / 8
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): SealedBox {
        require(key.size == KEY_LENGTH) { "Khóa phải dài 32 byte" }
        val nonce = Bytes.random(NONCE_LENGTH)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return SealedBox(nonce, cipher.doFinal(plaintext))
    }

    fun open(key: ByteArray, box: SealedBox, aad: ByteArray): ByteArray {
        if (key.size != KEY_LENGTH || box.nonce.size != NONCE_LENGTH || box.ciphertext.size < TAG_LENGTH) {
            throw DecryptionFailedException()
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, box.nonce))
            cipher.updateAAD(aad)
            return cipher.doFinal(box.ciphertext)
        } catch (e: GeneralSecurityException) {
            throw DecryptionFailedException()
        }
    }
}
