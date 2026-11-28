package com.samuelcruz.matkhau.core.totp

import com.samuelcruz.matkhau.core.format.Base32
import com.samuelcruz.matkhau.core.format.FormatException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * Mã QR "Chuyển tài khoản" (Transfer accounts) của Google Authenticator:
 *   otpauth-migration://offline?data=<base64 của một protobuf MigrationPayload>
 *
 * Một mã QR chứa tối đa khoảng 10 tài khoản; nhiều tài khoản thì app kia chia thành nhiều mã (batchIndex / batchSize).
 * Đọc hoàn toàn offline, không cần thư viện protobuf (chỉ cần varint + trường độ dài).
 */
object OtpMigration {
    const val SCHEME = "otpauth-migration://"
    private const val MAX_DATA_LENGTH = 64 * 1024
    private const val MAX_ACCOUNTS = 1000

    enum class Kind { TOTP, HOTP, UNKNOWN }

    class Account(
        val secret: ByteArray,
        /** Tên tài khoản (thường là email), đã bỏ tiền tố "issuer:" nếu có. */
        val account: String,
        val issuer: String,
        /** null = thuật toán không hỗ trợ (MD5). */
        val algorithm: TotpAlgorithm?,
        val digits: Int,
        val kind: Kind,
    ) {
        /** Lý do không nhập được (HOTP, MD5, secret rỗng), null = nhập được. */
        val unsupportedReason: String?
            get() = when {
                kind == Kind.HOTP -> "mã HOTP (theo bộ đếm) chưa được hỗ trợ"
                kind == Kind.UNKNOWN -> "loại mã không rõ"
                algorithm == null -> "thuật toán không hỗ trợ"
                secret.size < 10 -> "secret quá ngắn"
                else -> null
            }

        /** URI otpauth://totp/... để lưu vào mục (Totp.parse đọc lại được). */
        fun toOtpauthUri(): String {
            val label = if (issuer.isNotBlank()) "$issuer:$account" else account
            val params = buildList {
                add("secret=" + Base32.encode(secret, padding = false))
                if (issuer.isNotBlank()) add("issuer=" + enc(issuer))
                if (algorithm != null && algorithm != TotpAlgorithm.SHA1) add("algorithm=" + algorithm.name)
                if (digits != 6) add("digits=$digits")
                add("period=30")
            }
            return "otpauth://totp/" + enc(label) + "?" + params.joinToString("&")
        }

        override fun toString(): String = "Account(issuer=$issuer)"   // không in secret
    }

    class Batch(val accounts: List<Account>, val batchIndex: Int, val batchSize: Int, val batchId: Int)

    fun isMigrationUri(text: String): Boolean = text.trim().startsWith(SCHEME, ignoreCase = true)

    fun parse(uri: String): Batch {
        val text = uri.trim()
        if (!isMigrationUri(text)) throw FormatException("Không phải mã chuyển tài khoản của Google Authenticator")
        val query = text.substringAfter('?', "")
        val raw = query.split('&').firstOrNull { it.startsWith("data=") }?.substringAfter("data=")
            ?: throw FormatException("Mã QR thiếu dữ liệu")
        if (raw.length > MAX_DATA_LENGTH) throw FormatException("Mã QR quá lớn")
        val base64 = try {
            // '+' trong base64 có thể đã bị mã hóa thành %2B, hoặc bị đổi thành dấu cách bởi app quét QR khác.
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8").replace(' ', '+')
        } catch (e: IllegalArgumentException) {
            throw FormatException("Dữ liệu trong mã QR bị hỏng")
        }
        val bytes = try {
            Base64.getDecoder().decode(base64.trim())
        } catch (e: IllegalArgumentException) {
            try {
                Base64.getUrlDecoder().decode(base64.trim())
            } catch (e2: IllegalArgumentException) {
                throw FormatException("Dữ liệu trong mã QR không phải base64")
            }
        }
        return decodePayload(bytes)
    }

    // ------------------------------------------------------------------ protobuf tối giản

    private fun decodePayload(bytes: ByteArray): Batch {
        val r = Proto(bytes)
        val accounts = ArrayList<Account>()
        var batchSize = 1
        var batchIndex = 0
        var batchId = 0
        while (!r.atEnd()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 2 -> {
                    if (accounts.size >= MAX_ACCOUNTS) throw FormatException("Quá nhiều tài khoản trong mã QR")
                    accounts += decodeAccount(r.lengthDelimited())
                }
                field == 3 && wire == 0 -> batchSize = r.varint().toInt()
                field == 4 && wire == 0 -> batchIndex = r.varint().toInt()
                field == 5 && wire == 0 -> batchId = r.varint().toInt()
                else -> r.skip(wire)
            }
        }
        if (batchSize < 1 || batchIndex !in 0 until batchSize) {
            batchSize = 1; batchIndex = 0
        }
        return Batch(accounts, batchIndex, batchSize, batchId)
    }

    private fun decodeAccount(bytes: ByteArray): Account {
        val r = Proto(bytes)
        var secret = ByteArray(0)
        var name = ""
        var issuer = ""
        var algorithm = 0L
        var digits = 0L
        var type = 0L
        while (!r.atEnd()) {
            val (field, wire) = r.tag()
            when {
                field == 1 && wire == 2 -> secret = r.lengthDelimited()
                field == 2 && wire == 2 -> name = String(r.lengthDelimited(), Charsets.UTF_8)
                field == 3 && wire == 2 -> issuer = String(r.lengthDelimited(), Charsets.UTF_8)
                field == 4 && wire == 0 -> algorithm = r.varint()
                field == 5 && wire == 0 -> digits = r.varint()
                field == 6 && wire == 0 -> type = r.varint()
                else -> r.skip(wire)
            }
        }
        // Tên thường có dạng "Issuer:email": bỏ phần issuer cho gọn.
        val account = if (issuer.isNotBlank() && name.startsWith("$issuer:")) name.substring(issuer.length + 1).trim() else name.trim()
        return Account(
            secret = secret,
            account = account,
            issuer = issuer.trim(),
            algorithm = when (algorithm) {
                0L, 1L -> TotpAlgorithm.SHA1
                2L -> TotpAlgorithm.SHA256
                3L -> TotpAlgorithm.SHA512
                else -> null   // 4 = MD5
            },
            digits = if (digits == 2L) 8 else 6,
            kind = when (type) {
                0L, 2L -> Kind.TOTP   // 0 = không ghi: Google Authenticator coi là TOTP
                1L -> Kind.HOTP
                else -> Kind.UNKNOWN
            },
        )
    }

    /** Đọc protobuf có kiểm tra biên ở mọi bước (mã QR do người khác tạo có thể bị sửa). */
    private class Proto(private val data: ByteArray) {
        private var pos = 0

        fun atEnd() = pos >= data.size

        fun tag(): Pair<Int, Int> {
            val v = varint()
            val field = (v ushr 3).toInt()
            if (field <= 0) throw FormatException("Dữ liệu mã QR bị hỏng")
            return field to (v and 7).toInt()
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= data.size || shift >= 64) throw FormatException("Dữ liệu mã QR bị cắt cụt")
                val b = data[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun lengthDelimited(): ByteArray {
            val len = varint()
            if (len < 0 || len > data.size - pos) throw FormatException("Dữ liệu mã QR bị cắt cụt")
            return data.copyOfRange(pos, pos + len.toInt()).also { pos += len.toInt() }
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> advance(8)
                2 -> lengthDelimited()
                5 -> advance(4)
                else -> throw FormatException("Dữ liệu mã QR bị hỏng")
            }
        }

        private fun advance(n: Int) {
            if (n > data.size - pos) throw FormatException("Dữ liệu mã QR bị cắt cụt")
            pos += n
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
