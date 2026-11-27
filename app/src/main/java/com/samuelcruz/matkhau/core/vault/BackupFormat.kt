package com.samuelcruz.matkhau.core.vault

import com.samuelcruz.matkhau.core.crypto.Aead
import com.samuelcruz.matkhau.core.crypto.Bytes
import com.samuelcruz.matkhau.core.crypto.SealedBox
import com.samuelcruz.matkhau.core.format.ByteReader
import com.samuelcruz.matkhau.core.format.ByteWriter
import com.samuelcruz.matkhau.core.format.FormatException

/**
 * File backup (.lvbackup). Chỉ chứa dữ liệu đã mã hóa (spec 2.15):
 *
 *   "LVBK" | u16 version | header vault (có VEK đã bọc) | bytes nonce | bytes ciphertext
 *
 * Toàn bộ danh sách mục được mã hóa thành MỘT khối AES-GCM với AAD = mọi byte đứng trước nó.
 * Vì vậy sửa bất kỳ byte nào (kể cả header, hay xóa bớt một mục) đều khiến khôi phục thất bại.
 *
 * Backup mở được chỉ bằng mật khẩu chính hoặc Recovery Key, không phụ thuộc Android Keystore,
 * nên khôi phục được trên điện thoại khác.
 */
object BackupFormat {
    val MAGIC = "LVBK".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val MAX_FILE_SIZE = 64 * 1024 * 1024
    private val CONTEXT = "localvault/backup/v1".toByteArray(Charsets.US_ASCII)

    class Parsed internal constructor(
        val header: VaultHeader,
        internal val authenticatedPrefix: ByteArray,
        internal val box: SealedBox,
    )

    fun create(header: VaultHeader, vek: ByteArray, payload: ByteArray): ByteArray {
        val prefix = ByteWriter()
            .raw(MAGIC)
            .u16(VERSION)
            .raw(header.encode())
            .toByteArray()
        val box = Aead.seal(vek, payload, Bytes.concat(CONTEXT, prefix))
        return ByteWriter()
            .raw(prefix)
            .bytes(box.nonce)
            .bytes(box.ciphertext)
            .toByteArray()
    }

    /** Bước "validate backup format" — chưa cần mật khẩu. */
    fun parse(file: ByteArray): Parsed {
        if (file.size > MAX_FILE_SIZE) throw FormatException("File backup quá lớn")
        val r = ByteReader(file)
        if (!r.raw(4).contentEquals(MAGIC)) throw FormatException("Không phải file backup Mật Khẩu")
        val version = r.u16()
        if (version != VERSION) throw FormatException("Phiên bản backup không hỗ trợ: $version")
        val header = VaultHeader.read(r)
        val prefix = r.consumedSoFar()
        val nonce = r.bytes(64)
        val ciphertext = r.bytes(MAX_FILE_SIZE)
        r.requireEnd()
        if (nonce.size != Aead.NONCE_LENGTH) throw FormatException("Nonce không hợp lệ")
        return Parsed(header, prefix, SealedBox(nonce, ciphertext))
    }

    /** Giải mã + kiểm tra toàn vẹn. Ném DecryptionFailedException nếu file bị sửa. */
    fun decrypt(parsed: Parsed, vek: ByteArray): ByteArray =
        Aead.open(vek, parsed.box, Bytes.concat(CONTEXT, parsed.authenticatedPrefix))
}
