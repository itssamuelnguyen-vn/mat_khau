package com.samuelcruz.matkhau.core.vault

import com.samuelcruz.matkhau.core.crypto.Bytes
import com.samuelcruz.matkhau.core.crypto.DecryptionFailedException
import com.samuelcruz.matkhau.core.crypto.SealedBox
import com.samuelcruz.matkhau.model.AttachmentRef
import java.security.MessageDigest

/**
 * File đính kèm (ảnh CCCD, file khóa...). Không có bảng mới: mỗi file được cắt thành các đoạn [CHUNK_SIZE], mỗi đoạn
 * mã hóa riêng (AES-GCM, VEK, AAD gắn vault + file + vị trí + tổng số đoạn) và lưu thành một DÒNG của bảng items với
 * fmt = [ROW_FORMAT]. Tên file, loại, kích thước, SHA-256 nằm trong mục (đã mã hóa), không nằm ở cột nào.
 *
 * Cắt đoạn vì Android đọc một dòng SQLite qua CursorWindow ~2 MB: dòng lớn hơn sẽ không đọc được.
 */
object Attachments {
    const val ROW_FORMAT = 2
    const val CHUNK_SIZE = 512 * 1024
    const val MAX_FILE_SIZE = 10 * 1024 * 1024
    const val MAX_TOTAL_SIZE = 40L * 1024 * 1024
    const val MAX_PER_ENTRY = 20
    private const val ROW_PREFIX = "att:"

    fun rowId(attachmentId: String, index: Int): String = "$ROW_PREFIX$attachmentId:$index"

    fun isAttachmentRowId(id: String): Boolean = id.startsWith(ROW_PREFIX)

    /** id của file chứa dòng này, null nếu không phải dòng file đính kèm. */
    fun attachmentIdOf(rowId: String): String? =
        if (!isAttachmentRowId(rowId)) null else rowId.removePrefix(ROW_PREFIX).substringBeforeLast(':', "").ifEmpty { null }

    fun chunkCount(size: Int): Int = if (size == 0) 1 else (size + CHUNK_SIZE - 1) / CHUNK_SIZE

    fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    class SealedChunk(val rowId: String, val box: SealedBox)

    /** Mã hóa cả file thành các đoạn. */
    fun seal(vek: ByteArray, vaultId: ByteArray, attachmentId: String, data: ByteArray): List<SealedChunk> {
        val count = chunkCount(data.size)
        return (0 until count).map { i ->
            val chunk = data.copyOfRange(i * CHUNK_SIZE, minOf(data.size, (i + 1) * CHUNK_SIZE))
            try {
                SealedChunk(rowId(attachmentId, i), VaultCrypto.encryptAttachmentChunk(vek, vaultId, attachmentId, i, count, chunk))
            } finally {
                Bytes.wipe(chunk)
            }
        }
    }

    /**
     * Giải mã và kiểm tra cả file: đủ đoạn, đúng kích thước, đúng SHA-256 đã ghi trong mục.
     * [load] trả về dòng theo row id (null = thiếu). Ném [DecryptionFailedException] nếu thiếu / bị sửa.
     */
    fun open(vek: ByteArray, vaultId: ByteArray, ref: AttachmentRef, load: (String) -> SealedBox?): ByteArray {
        if (ref.chunks != chunkCount(ref.size.toInt()) || ref.size < 0 || ref.size > MAX_FILE_SIZE) throw DecryptionFailedException()
        val out = ByteArray(ref.size.toInt())
        var offset = 0
        try {
            for (i in 0 until ref.chunks) {
                val box = load(rowId(ref.id, i)) ?: throw DecryptionFailedException()
                val plain = VaultCrypto.decryptAttachmentChunk(vek, vaultId, ref.id, i, ref.chunks, box)
                try {
                    if (offset + plain.size > out.size) throw DecryptionFailedException()
                    plain.copyInto(out, offset)
                    offset += plain.size
                } finally {
                    Bytes.wipe(plain)
                }
            }
            if (offset != out.size || !sha256Hex(out).equals(ref.sha256, ignoreCase = true)) throw DecryptionFailedException()
            return out
        } catch (e: Throwable) {
            Bytes.wipe(out)
            throw e
        }
    }

    /** Chỉ kiểm tra (không giữ nội dung): dùng khi xác minh backup trước khi ghi đè vault. */
    fun verify(vek: ByteArray, vaultId: ByteArray, ref: AttachmentRef, load: (String) -> SealedBox?) {
        Bytes.wipe(open(vek, vaultId, ref, load))
    }
}
