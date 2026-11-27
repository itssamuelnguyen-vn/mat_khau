package com.samuelcruz.matkhau.core.vault

import com.samuelcruz.matkhau.core.crypto.Aead
import com.samuelcruz.matkhau.core.crypto.KdfParams
import com.samuelcruz.matkhau.core.crypto.SealedBox
import com.samuelcruz.matkhau.core.format.ByteReader
import com.samuelcruz.matkhau.core.format.ByteWriter
import com.samuelcruz.matkhau.core.format.FormatException

/**
 * Một "ổ khóa" chứa VEK đã được bọc (mã hóa) bằng một khóa khác (KEK).
 * Vault có slot mật khẩu (bắt buộc) và slot Recovery Key (tùy chọn).
 */
class KeySlot(val salt: ByteArray, val wrappedKey: SealedBox)

/**
 * Header của vault — phần KHÔNG mã hóa nhưng không chứa bí mật nào:
 * salt, tham số Argon2 và VEK đã bọc. Thiếu mật khẩu chính (hoặc Recovery Key) thì
 * không thể lấy VEK từ đây.
 *
 * Định dạng nhị phân (big-endian):
 *   "LVHD" | u16 version | bytes vaultId | u8 kdfAlg | u32 memKiB | u32 iter | u8 par
 *   | slot mật khẩu | u8 hasRecovery | [slot recovery]
 *   slot = bytes salt | bytes nonce | bytes ciphertext
 */
class VaultHeader(
    val vaultId: ByteArray,
    val kdf: KdfParams,
    val passwordSlot: KeySlot,
    val recoverySlot: KeySlot?,
) {
    fun withPasswordSlot(slot: KeySlot) = VaultHeader(vaultId, kdf, slot, recoverySlot)
    fun withRecoverySlot(slot: KeySlot?) = VaultHeader(vaultId, kdf, passwordSlot, slot)
    fun withKdf(params: KdfParams, slot: KeySlot) = VaultHeader(vaultId, params, slot, recoverySlot)

    fun encode(): ByteArray {
        val w = ByteWriter()
            .raw(MAGIC)
            .u16(VERSION)
            .bytes(vaultId)
            .u8(KDF_ARGON2ID)
            .u32(kdf.memoryKiB)
            .u32(kdf.iterations)
            .u8(kdf.parallelism)
        writeSlot(w, passwordSlot)
        if (recoverySlot != null) {
            w.u8(1)
            writeSlot(w, recoverySlot)
        } else {
            w.u8(0)
        }
        return w.toByteArray()
    }

    companion object {
        val MAGIC = "LVHD".toByteArray(Charsets.US_ASCII)
        const val VERSION = 1
        const val KDF_ARGON2ID = 1
        const val VAULT_ID_LENGTH = 16
        const val SALT_LENGTH = 16
        private const val WRAPPED_KEY_LENGTH = Aead.KEY_LENGTH + Aead.TAG_LENGTH

        private fun writeSlot(w: ByteWriter, slot: KeySlot) {
            w.bytes(slot.salt).bytes(slot.wrappedKey.nonce).bytes(slot.wrappedKey.ciphertext)
        }

        private fun readSlot(r: ByteReader): KeySlot {
            val salt = r.bytes(64)
            val nonce = r.bytes(64)
            val ct = r.bytes(256)
            if (salt.size != SALT_LENGTH || nonce.size != Aead.NONCE_LENGTH || ct.size != WRAPPED_KEY_LENGTH) {
                throw FormatException("Key slot không hợp lệ")
            }
            return KeySlot(salt, SealedBox(nonce, ct))
        }

        /** Đọc header từ một ByteReader (dùng chung cho DB và file backup). */
        fun read(r: ByteReader): VaultHeader {
            val magic = r.raw(4)
            if (!magic.contentEquals(MAGIC)) throw FormatException("Không phải header Mật Khẩu")
            val version = r.u16()
            if (version != VERSION) throw FormatException("Phiên bản header không hỗ trợ: $version")
            val vaultId = r.bytes(64)
            if (vaultId.size != VAULT_ID_LENGTH) throw FormatException("Vault ID không hợp lệ")
            val alg = r.u8()
            if (alg != KDF_ARGON2ID) throw FormatException("Thuật toán KDF không hỗ trợ")
            val kdf = KdfParams(memoryKiB = r.u32(), iterations = r.u32(), parallelism = r.u8())
            if (!kdf.isSane()) throw FormatException("Tham số Argon2 nằm ngoài giới hạn an toàn")
            val passwordSlot = readSlot(r)
            val recoverySlot = when (r.u8()) {
                0 -> null
                1 -> readSlot(r)
                else -> throw FormatException("Cờ recovery không hợp lệ")
            }
            return VaultHeader(vaultId, kdf, passwordSlot, recoverySlot)
        }

        fun decode(bytes: ByteArray): VaultHeader {
            val r = ByteReader(bytes)
            val header = read(r)
            r.requireEnd()
            return header
        }
    }
}
