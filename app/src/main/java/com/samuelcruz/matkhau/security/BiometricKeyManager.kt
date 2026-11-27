package com.samuelcruz.matkhau.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import com.samuelcruz.matkhau.core.format.ByteReader
import com.samuelcruz.matkhau.core.format.ByteWriter
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Bọc VEK bằng một khóa AES nằm trong Android Keystore (chip bảo mật TEE/StrongBox).
 *
 * - Khóa KHÔNG BAO GIỜ rời khỏi chip; app chỉ nhờ chip mã hóa/giải mã.
 * - Mỗi lần dùng đều phải xác thực vân tay/khuôn mặt loại "Strong" (BiometricPrompt + CryptoObject).
 * - Thêm vân tay mới vào máy => khóa tự vô hiệu (KeyPermanentlyInvalidatedException),
 *   app sẽ yêu cầu nhập mật khẩu chính rồi bật lại vân tay. Đây là hành vi đúng.
 * - Mật khẩu chính vẫn là bí mật gốc (spec 2.7). Vân tay chỉ là đường tắt.
 */
class BiometricKeyManager {

    fun canUseBiometrics(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Tạo khóa mới (xóa khóa cũ) và trả về Cipher ở chế độ mã hóa — cần đưa qua BiometricPrompt. */
    fun newEncryptCipher(): Cipher {
        deleteKey()
        val key = generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** Ném KeyPermanentlyInvalidatedException nếu vân tay trên máy đã thay đổi. */
    fun decryptCipher(iv: ByteArray): Cipher {
        val key = loadKey() ?: throw IllegalStateException("Chưa bật mở khóa bằng vân tay")
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
    }

    fun deleteKey() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    fun hasKey(): Boolean = runCatching { keyStore().containsAlias(ALIAS) }.getOrDefault(false)

    /** true nếu khóa nằm trong phần cứng bảo mật (TEE/StrongBox); null nếu chưa có khóa. */
    fun isHardwareBacked(): Boolean? {
        val key = loadKey() ?: return null
        return runCatching {
            val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                    info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
            } else {
                @Suppress("DEPRECATION")
                info.isInsideSecureHardware
            }
        }.getOrNull()
    }

    private fun generateKey(): SecretKey {
        fun build(strongBox: Boolean): SecretKey {
            val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 0 giây = phải xác thực cho MỖI lần dùng khóa
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            }
            if (strongBox) builder.setIsStrongBoxBacked(true)
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(builder.build())
            return generator.generateKey()
        }
        // Ưu tiên StrongBox (chip bảo mật riêng) nếu máy có; không có thì dùng TEE.
        return try {
            build(strongBox = true)
        } catch (e: Exception) {
            deleteKey()
            build(strongBox = false)
        }
    }

    private fun loadKey(): SecretKey? = runCatching { keyStore().getKey(ALIAS, null) as? SecretKey }.getOrNull()

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "localvault.biometric.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val BLOB_VERSION = 1

        fun encodeBlob(iv: ByteArray, ciphertext: ByteArray): ByteArray =
            ByteWriter().u8(BLOB_VERSION).bytes(iv).bytes(ciphertext).toByteArray()

        fun decodeBlob(blob: ByteArray): Pair<ByteArray, ByteArray> {
            val r = ByteReader(blob)
            require(r.u8() == BLOB_VERSION)
            val iv = r.bytes(32)
            val ct = r.bytes(256)
            r.requireEnd()
            return iv to ct
        }
    }
}
