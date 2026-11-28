package com.samuelcruz.matkhau.core.vault

import com.samuelcruz.matkhau.core.crypto.Aead
import com.samuelcruz.matkhau.core.crypto.Bytes
import com.samuelcruz.matkhau.core.crypto.SealedBox
import com.samuelcruz.matkhau.core.format.ByteReader
import com.samuelcruz.matkhau.core.format.ByteWriter
import com.samuelcruz.matkhau.core.format.FormatException
import java.io.DataOutputStream
import java.io.OutputStream

/**
 * File backup (.lvbackup). Chỉ chứa dữ liệu đã mã hóa (spec 2.15):
 *
 *   v1: "LVBK" | u16 1 | header vault (có VEK đã bọc) | bytes nonce | bytes ciphertext
 *   v2: "LVBK" | u16 2 | header vault | bytes nonce | bytes ciphertext
 *       | u32 n | n × (bytes rowId | bytes nonce | bytes ct)      ← các đoạn file đính kèm, y nguyên như trong DB
 *
 * Danh sách mục được mã hóa thành MỘT khối AES-GCM với AAD = mọi byte đứng trước nó, nên sửa bất kỳ byte nào của
 * header hay xóa bớt một mục đều khiến khôi phục thất bại. Các đoạn file đính kèm vốn đã được mã hóa bằng VEK với AAD
 * gắn vault + file + vị trí + tổng số đoạn (xem [Attachments]); SHA-256 của từng file nằm trong khối danh sách mục,
 * nên đoạn bị sửa, bị thiếu, bị tráo hay bị thêm đều bị phát hiện trước khi ghi đè vault.
 *
 * Backup mở được chỉ bằng mật khẩu chính hoặc Recovery Key, không phụ thuộc Android Keystore,
 * nên khôi phục được trên điện thoại khác. Bản này đọc được cả v1 (0.1.x – 0.4.x).
 */
object BackupFormat {
    val MAGIC = "LVBK".toByteArray(Charsets.US_ASCII)
    const val VERSION_1 = 1
    const val VERSION = 2
    /** File đính kèm tối đa 40 MB + danh sách mục: 96 MB là đủ rộng. */
    const val MAX_FILE_SIZE = 96 * 1024 * 1024
    private const val MAX_PAYLOAD_SIZE = 64 * 1024 * 1024
    private const val MAX_ROWS = 100_000
    private const val MAX_ROW_ID = 256
    private val CONTEXT = "localvault/backup/v1".toByteArray(Charsets.US_ASCII)

    /** Vị trí một đoạn file đính kèm trong file backup (không chép ra, xem [rowBox]). */
    class RowRef internal constructor(val id: String, internal val nonce: Pair<Int, Int>, internal val ct: Pair<Int, Int>)

    class Parsed internal constructor(
        val header: VaultHeader,
        val version: Int,
        internal val authenticatedPrefix: ByteArray,
        internal val box: SealedBox,
        internal val file: ByteArray,
        val rows: List<RowRef>,
    ) {
        private val byId: Map<String, RowRef> by lazy { rows.associateBy { it.id } }

        fun row(id: String): RowRef? = byId[id]

        /** Bản chép nonce + ciphertext của một đoạn (chỉ khi cần). */
        fun rowBox(ref: RowRef): SealedBox = SealedBox(
            file.copyOfRange(ref.nonce.first, ref.nonce.first + ref.nonce.second),
            file.copyOfRange(ref.ct.first, ref.ct.first + ref.ct.second),
        )
    }

    /** Đoạn file đính kèm như đang lưu trong DB. */
    class Row(val id: String, val nonce: ByteArray, val ciphertext: ByteArray)

    /**
     * Ghi backup v2 thẳng ra [out] (không dựng cả file trong RAM). [rowIds] đã biết trước (để ghi số lượng);
     * [loadRow] đọc từng đoạn từ DB lúc ghi.
     */
    fun write(out: OutputStream, header: VaultHeader, vek: ByteArray, payload: ByteArray, rowIds: List<String>, loadRow: (String) -> Row) {
        val prefix = ByteWriter().raw(MAGIC).u16(VERSION).raw(header.encode()).toByteArray()
        val box = Aead.seal(vek, payload, Bytes.concat(CONTEXT, prefix))
        val data = DataOutputStream(out)
        data.write(prefix)
        data.writeInt(box.nonce.size); data.write(box.nonce)
        data.writeInt(box.ciphertext.size); data.write(box.ciphertext)
        data.writeInt(rowIds.size)
        for (id in rowIds) {
            val row = loadRow(id)
            require(row.id == id)
            val idBytes = id.toByteArray(Charsets.UTF_8)
            data.writeInt(idBytes.size); data.write(idBytes)
            data.writeInt(row.nonce.size); data.write(row.nonce)
            data.writeInt(row.ciphertext.size); data.write(row.ciphertext)
        }
        data.flush()
    }

    /** Backup v2 không có file đính kèm, trong RAM (test và vault nhỏ). */
    fun create(header: VaultHeader, vek: ByteArray, payload: ByteArray): ByteArray =
        java.io.ByteArrayOutputStream().also { write(it, header, vek, payload, emptyList()) { error("no rows") } }.toByteArray()

    /** Bản v1 trong RAM (giữ để test đọc được file cũ). */
    fun createV1(header: VaultHeader, vek: ByteArray, payload: ByteArray): ByteArray {
        val prefix = ByteWriter().raw(MAGIC).u16(VERSION_1).raw(header.encode()).toByteArray()
        val box = Aead.seal(vek, payload, Bytes.concat(CONTEXT, prefix))
        return ByteWriter().raw(prefix).bytes(box.nonce).bytes(box.ciphertext).toByteArray()
    }

    /** Bước "validate backup format" — chưa cần mật khẩu. */
    fun parse(file: ByteArray): Parsed {
        if (file.size > MAX_FILE_SIZE) throw FormatException("File backup quá lớn")
        val r = ByteReader(file)
        if (!r.raw(4).contentEquals(MAGIC)) throw FormatException("Không phải file backup Mật Khẩu")
        val version = r.u16()
        if (version != VERSION_1 && version != VERSION) throw FormatException("Phiên bản backup không hỗ trợ: $version")
        val header = VaultHeader.read(r)
        val prefix = r.consumedSoFar()
        val nonce = r.bytes(64)
        val ciphertext = r.bytes(MAX_PAYLOAD_SIZE)
        if (nonce.size != Aead.NONCE_LENGTH) throw FormatException("Nonce không hợp lệ")
        val rows = ArrayList<RowRef>()
        if (version == VERSION) {
            val n = r.u32()
            if (n > MAX_ROWS) throw FormatException("Backup có quá nhiều đoạn file đính kèm")
            val seen = HashSet<String>()
            repeat(n) {
                val id = String(r.bytes(MAX_ROW_ID), Charsets.UTF_8)
                if (!Attachments.isAttachmentRowId(id) || !seen.add(id)) throw FormatException("Đoạn file đính kèm không hợp lệ")
                val rowNonce = r.span(64)
                if (rowNonce.second != Aead.NONCE_LENGTH) throw FormatException("Nonce không hợp lệ")
                val rowCt = r.span(Attachments.CHUNK_SIZE + 64)
                rows += RowRef(id, rowNonce, rowCt)
            }
        }
        r.requireEnd()
        return Parsed(header, version, prefix, SealedBox(nonce, ciphertext), file, rows)
    }

    /** Giải mã + kiểm tra toàn vẹn danh sách mục. Ném DecryptionFailedException nếu file bị sửa. */
    fun decrypt(parsed: Parsed, vek: ByteArray): ByteArray =
        Aead.open(vek, parsed.box, Bytes.concat(CONTEXT, parsed.authenticatedPrefix))
}
