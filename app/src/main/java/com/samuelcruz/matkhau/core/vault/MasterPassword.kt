package com.samuelcruz.matkhau.core.vault

import java.text.Normalizer

/**
 * Đổi mật khẩu chính (chuỗi người dùng gõ) thành byte để đưa vào Argon2id.
 *
 * Chuẩn hóa Unicode NFC trước khi mã hóa UTF-8: cùng một chữ có dấu ("ả", "ệ"...) có thể được bàn phím
 * gửi dạng dựng sẵn (NFC) hoặc dạng chữ gốc + dấu rời (NFD). Không chuẩn hóa thì đổi bàn phím là
 * cùng một mật khẩu cho ra byte khác => không mở được vault.
 */
object MasterPassword {
    fun toBytes(password: String): ByteArray =
        Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)

    /**
     * Các cách mã hóa cần thử khi MỞ khóa: NFC trước, rồi byte gốc nếu khác NFC (vault / backup tạo bằng 0.1.0,
     * khi chưa chuẩn hóa). Khi TẠO hay ĐỔI mật khẩu chỉ dùng [toBytes]. Người gọi wipe từng mảng.
     */
    fun unlockCandidates(password: String): List<ByteArray> {
        val nfc = toBytes(password)
        val raw = password.toByteArray(Charsets.UTF_8)
        return if (raw.contentEquals(nfc)) {
            raw.fill(0)
            listOf(nfc)
        } else {
            listOf(nfc, raw)
        }
    }
}
