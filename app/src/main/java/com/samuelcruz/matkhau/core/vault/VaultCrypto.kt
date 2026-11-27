package com.samuelcruz.matkhau.core.vault

import com.samuelcruz.matkhau.core.crypto.Aead
import com.samuelcruz.matkhau.core.crypto.Argon2id
import com.samuelcruz.matkhau.core.crypto.Bytes
import com.samuelcruz.matkhau.core.crypto.DecryptionFailedException
import com.samuelcruz.matkhau.core.crypto.KdfParams
import com.samuelcruz.matkhau.core.crypto.SealedBox

/** Mật khẩu chính hoặc Recovery Key sai. */
class WrongSecretException : Exception("Mật khẩu hoặc Recovery Key không đúng")

/** Kết quả tạo vault mới. Gọi [Bytes.wipe] lên [vek] và [recoveryKey] khi không cần nữa. */
class CreatedVault(val header: VaultHeader, val vek: ByteArray, val recoveryKey: ByteArray)

/**
 * Cây khóa (key hierarchy) của vault:
 *
 *   Mật khẩu chính ──Argon2id(salt₁)──► KEK₁ ──bọc──► VEK
 *   Recovery Key   ──Argon2id(salt₂)──► KEK₂ ──bọc──► VEK
 *   (Vân tay: Android Keystore ──bọc──► VEK, xử lý ở tầng Android)
 *
 *   VEK (256-bit ngẫu nhiên) ──AES-256-GCM──► từng mục trong vault
 *
 * VEK chỉ sinh MỘT lần khi tạo vault, nên đổi mật khẩu chính chỉ cần bọc lại VEK.
 */
object VaultCrypto {
    private const val SLOT_PASSWORD: Byte = 1
    private const val SLOT_RECOVERY: Byte = 2
    private val SLOT_CONTEXT = "localvault/key-slot/v1".toByteArray(Charsets.US_ASCII)
    private val ITEM_CONTEXT = "localvault/item/v1".toByteArray(Charsets.US_ASCII)

    fun createVault(password: ByteArray, params: KdfParams = KdfParams.DEFAULT): CreatedVault {
        val vaultId = Bytes.random(VaultHeader.VAULT_ID_LENGTH)
        val vek = Bytes.random(Aead.KEY_LENGTH)
        val recoveryKey = RecoveryKey.generate()
        val passwordSlot = makeSlot(password, vek, vaultId, params, SLOT_PASSWORD)
        val recoverySlot = makeSlot(recoveryKey, vek, vaultId, params, SLOT_RECOVERY)
        return CreatedVault(VaultHeader(vaultId, params, passwordSlot, recoverySlot), vek, recoveryKey)
    }

    /** Trả về VEK (32 byte). Ném [WrongSecretException] nếu sai mật khẩu. */
    fun unlockWithPassword(header: VaultHeader, password: ByteArray): ByteArray =
        openSlot(header.passwordSlot, password, header, SLOT_PASSWORD)

    fun unlockWithRecoveryKey(header: VaultHeader, recoveryKey: ByteArray): ByteArray {
        val slot = header.recoverySlot ?: throw WrongSecretException()
        return openSlot(slot, recoveryKey, header, SLOT_RECOVERY)
    }

    /** Bọc lại VEK bằng mật khẩu mới (salt mới). Dữ liệu các mục không cần mã hóa lại. */
    fun changePassword(header: VaultHeader, vek: ByteArray, newPassword: ByteArray): VaultHeader {
        val slot = makeSlot(newPassword, vek, header.vaultId, header.kdf, SLOT_PASSWORD)
        return header.withPasswordSlot(slot)
    }

    /** Tạo Recovery Key mới; key cũ lập tức vô hiệu. Trả về header mới + key thô (cần wipe sau khi hiển thị). */
    fun regenerateRecoveryKey(header: VaultHeader, vek: ByteArray): Pair<VaultHeader, ByteArray> {
        val recoveryKey = RecoveryKey.generate()
        val slot = makeSlot(recoveryKey, vek, header.vaultId, header.kdf, SLOT_RECOVERY)
        return header.withRecoverySlot(slot) to recoveryKey
    }

    fun encryptItem(vek: ByteArray, vaultId: ByteArray, itemId: String, plaintext: ByteArray): SealedBox =
        Aead.seal(vek, plaintext, itemAad(vaultId, itemId))

    /** Ném [DecryptionFailedException] nếu mục bị sửa, bị tráo sang id khác, hoặc sai khóa. */
    fun decryptItem(vek: ByteArray, vaultId: ByteArray, itemId: String, box: SealedBox): ByteArray =
        Aead.open(vek, box, itemAad(vaultId, itemId))

    // ---------------------------------------------------------------------------------------

    private fun makeSlot(secret: ByteArray, vek: ByteArray, vaultId: ByteArray, params: KdfParams, type: Byte): KeySlot {
        val salt = Bytes.random(VaultHeader.SALT_LENGTH)
        val kek = Argon2id.deriveKey(secret, salt, params)
        try {
            return KeySlot(salt, Aead.seal(kek, vek, slotAad(vaultId, type)))
        } finally {
            Bytes.wipe(kek)
        }
    }

    private fun openSlot(slot: KeySlot, secret: ByteArray, header: VaultHeader, type: Byte): ByteArray {
        val kek = Argon2id.deriveKey(secret, slot.salt, header.kdf)
        try {
            val vek = Aead.open(kek, slot.wrappedKey, slotAad(header.vaultId, type))
            if (vek.size != Aead.KEY_LENGTH) {
                Bytes.wipe(vek)
                throw WrongSecretException()
            }
            return vek
        } catch (e: DecryptionFailedException) {
            throw WrongSecretException()
        } finally {
            Bytes.wipe(kek)
        }
    }

    private fun slotAad(vaultId: ByteArray, type: Byte) = Bytes.concat(SLOT_CONTEXT, byteArrayOf(type), vaultId)

    private fun itemAad(vaultId: ByteArray, itemId: String) =
        Bytes.concat(ITEM_CONTEXT, vaultId, itemId.toByteArray(Charsets.UTF_8))
}
