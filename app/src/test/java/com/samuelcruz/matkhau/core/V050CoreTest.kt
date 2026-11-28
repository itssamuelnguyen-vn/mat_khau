package com.samuelcruz.matkhau.core

import com.samuelcruz.matkhau.core.autofill.FormClassifier
import com.samuelcruz.matkhau.core.autofill.FormField
import com.samuelcruz.matkhau.core.backup.BackupReminder
import com.samuelcruz.matkhau.core.card.CardBrand
import com.samuelcruz.matkhau.core.card.CardExpiry
import com.samuelcruz.matkhau.core.card.PaymentCard
import com.samuelcruz.matkhau.core.crypto.Bytes
import com.samuelcruz.matkhau.core.crypto.DecryptionFailedException
import com.samuelcruz.matkhau.core.crypto.KdfParams
import com.samuelcruz.matkhau.core.crypto.SealedBox
import com.samuelcruz.matkhau.core.format.FormatException
import com.samuelcruz.matkhau.core.totp.OtpMigration
import com.samuelcruz.matkhau.core.totp.Totp
import com.samuelcruz.matkhau.core.totp.TotpAlgorithm
import com.samuelcruz.matkhau.core.vault.Attachments
import com.samuelcruz.matkhau.core.vault.BackupFormat
import com.samuelcruz.matkhau.core.vault.UnlockThrottle
import com.samuelcruz.matkhau.core.vault.VaultCrypto
import com.samuelcruz.matkhau.model.AttachmentRef
import com.samuelcruz.matkhau.model.CardInfo
import com.samuelcruz.matkhau.model.Entry
import com.samuelcruz.matkhau.model.EntryType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class V050CoreTest {
    private val fastKdf = KdfParams(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1)

    // ------------------------------------------------------------------ Google Authenticator migration

    @Test
    fun migration_goldenSample() {
        // Mẫu hay được trích dẫn (lưu ý: digits / type nằm NGOÀI message tài khoản — parser phải bỏ qua an toàn).
        val uri = "otpauth-migration://offline?data=CjEKCkhlbGxvId6tvu8SGEV4YW1wbGU6YWxpY2VAZ29vZ2xlLmNvbRoHRXhhbXBsZSABKAEwAhABGAEgACjr4JqoBQ%3D%3D"
        val batch = OtpMigration.parse(uri)
        assertEquals(1, batch.accounts.size)
        val a = batch.accounts[0]
        assertEquals("Example", a.issuer)
        assertEquals("alice@google.com", a.account)
        assertEquals(TotpAlgorithm.SHA1, a.algorithm)
        assertEquals(6, a.digits)
        assertNull(a.unsupportedReason)
        val cfg = Totp.parse(a.toOtpauthUri())
        assertArrayEquals("Hello!".toByteArray() + byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), cfg.secret)
        assertEquals("Example", cfg.issuer)
        assertEquals("alice@google.com", cfg.account)
        assertFalse(a.toString().contains("JBSW"))
    }

    private fun varint(v: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var x = v
        while (true) {
            if (x and 0x7F.inv().toLong() == 0L) { out.write(x.toInt()); return out.toByteArray() }
            out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7
        }
    }
    private fun field(n: Int, bytes: ByteArray) = varint((n.toLong() shl 3) or 2) + varint(bytes.size.toLong()) + bytes
    private fun field(n: Int, v: Long) = varint((n.toLong() shl 3)) + varint(v)

    private fun account(secret: ByteArray, name: String, issuer: String, alg: Long, digits: Long, type: Long) =
        field(1, secret) + field(2, name.toByteArray()) + field(3, issuer.toByteArray()) + field(4, alg) + field(5, digits) + field(6, type)

    private fun migrationUri(payload: ByteArray) =
        "otpauth-migration://offline?data=" + java.net.URLEncoder.encode(Base64.getEncoder().encodeToString(payload), "UTF-8")

    @Test
    fun migration_multipleAccountsAndBatches() {
        val s1 = ByteArray(20) { it.toByte() }
        val s2 = ByteArray(32) { (it * 3).toByte() }
        val payload = field(1, account(s1, "GitHub:me", "GitHub", 1, 1, 2)) +
            field(1, account(s2, "Đỗ Văn A", "Ngân hàng", 2, 2, 2)) +
            field(1, account(s1, "counter", "Old", 1, 1, 1)) +
            field(1, account(s1, "md5", "Weird", 4, 1, 2)) +
            field(2, 1) + field(3, 3) + field(4, 1) + field(5, 77)
        val b = OtpMigration.parse(migrationUri(payload))
        assertEquals(4, b.accounts.size)
        assertEquals(1, b.batchIndex); assertEquals(3, b.batchSize); assertEquals(77, b.batchId)
        assertEquals("me", b.accounts[0].account)
        val bank = b.accounts[1]
        assertEquals(TotpAlgorithm.SHA256, bank.algorithm); assertEquals(8, bank.digits)
        val cfg = Totp.parse(bank.toOtpauthUri())
        assertEquals("Ngân hàng", cfg.issuer); assertEquals("Đỗ Văn A", cfg.account); assertEquals(8, cfg.digits)
        assertEquals(TotpAlgorithm.SHA256, cfg.algorithm); assertArrayEquals(s2, cfg.secret)
        assertNotNull(b.accounts[2].unsupportedReason)   // HOTP
        assertNotNull(b.accounts[3].unsupportedReason)   // MD5
        // '+' của base64 bị đổi thành dấu cách bởi app quét khác vẫn đọc được
        val spaced = "otpauth-migration://offline?data=" + Base64.getEncoder().encodeToString(payload).replace('+', ' ')
        assertEquals(4, OtpMigration.parse(spaced).accounts.size)
    }

    @Test
    fun migration_rejectsGarbage() {
        assertThrows(FormatException::class.java) { OtpMigration.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP") }
        assertThrows(FormatException::class.java) { OtpMigration.parse("otpauth-migration://offline?foo=1") }
        assertThrows(FormatException::class.java) { OtpMigration.parse("otpauth-migration://offline?data=%%%") }
        // Độ dài khai báo vượt dữ liệu thật
        assertThrows(FormatException::class.java) { OtpMigration.parse(migrationUri(byteArrayOf(0x0A, 0x7F, 0x01))) }
        // Varint không bao giờ kết thúc
        assertThrows(FormatException::class.java) { OtpMigration.parse(migrationUri(ByteArray(20) { 0xFF.toByte() })) }
        // Field number 0
        assertThrows(FormatException::class.java) { OtpMigration.parse(migrationUri(byteArrayOf(0x00, 0x01))) }
        for (seed in 0 until 300) {
            val r = java.util.Random(seed.toLong())
            val junk = ByteArray(r.nextInt(64)).also { r.nextBytes(it) }
            try { OtpMigration.parse(migrationUri(junk)) } catch (e: FormatException) { }
        }
    }

    // ------------------------------------------------------------------ thẻ

    @Test
    fun card_brandLuhnFormatMask() {
        assertEquals(CardBrand.VISA, PaymentCard.brand("4111 1111 1111 1111"))
        assertEquals(CardBrand.MASTERCARD, PaymentCard.brand("5500000000000004"))
        assertEquals(CardBrand.MASTERCARD, PaymentCard.brand("2221000000000009"))
        assertEquals(CardBrand.AMEX, PaymentCard.brand("378282246310005"))
        assertEquals(CardBrand.JCB, PaymentCard.brand("3530111333300000"))
        assertEquals(CardBrand.NAPAS, PaymentCard.brand("9704 0000 0000 0018"))
        assertTrue(PaymentCard.looksValid("4111-1111-1111-1111"))
        assertFalse(PaymentCard.looksValid("4111 1111 1111 1112"))
        assertFalse(PaymentCard.looksValid("4111"))
        assertTrue(PaymentCard.looksValid("9704000000000019"))   // Napas: không kiểm Luhn
        assertEquals("3782 822463 10005", PaymentCard.format("378282246310005"))
        assertEquals("4111 1111 1111 1111", PaymentCard.format("4111111111111111"))
        assertEquals("•••• 1111", PaymentCard.masked("4111 1111 1111 1111"))
        assertEquals("••••", PaymentCard.masked("12"))
    }

    @Test
    fun card_expiry() {
        assertEquals(CardExpiry(3, 2027), PaymentCard.parseExpiry("03/27"))
        assertEquals(CardExpiry(3, 2027), PaymentCard.parseExpiry("3/27"))
        assertEquals(CardExpiry(12, 2030), PaymentCard.parseExpiry("12/2030"))
        assertEquals(CardExpiry(1, 2028), PaymentCard.parseExpiry("0128"))
        assertEquals(CardExpiry(1, 2028), PaymentCard.parseExpiry("01-28"))
        assertNull(PaymentCard.parseExpiry("13/27"))
        assertNull(PaymentCard.parseExpiry("ab/cd"))
        assertNull(PaymentCard.parseExpiry(""))
        assertEquals("03/27", CardExpiry(3, 2027).mmyy)
        assertTrue(CardExpiry(3, 2026).isExpired(2026, 4))
        assertFalse(CardExpiry(3, 2026).isExpired(2026, 3))
        assertFalse(CardExpiry(1, 2027).isExpired(2026, 12))
    }

    @Test
    fun card_detectedInForms_andNotTakenAsLogin() {
        val fields = listOf(
            FormField(0, htmlAttrs = listOf("cardnumber"), texts = listOf("Số thẻ")),
            FormField(1, htmlAttrs = listOf("exp-date"), texts = listOf("MM/YY"), maxLength = 5),
            FormField(2, htmlType = "password", htmlAttrs = listOf("cvc")),
            FormField(3, htmlAttrs = listOf("name on card")),
        )
        val card = FormClassifier.classifyCard(fields)
        assertEquals(0, card.number); assertEquals(1, card.expiry); assertEquals(2, card.cvc); assertEquals(3, card.holder)
        assertEquals("MM/YY", card.expiryPattern)
        // CVC kiểu password không bị coi là ô mật khẩu đăng nhập
        val login = FormClassifier.classify(fields, exclude = card.indices)
        assertTrue(login.isEmpty)

        val split = FormClassifier.classifyCard(listOf(
            FormField(0, hints = listOf("creditcardnumber")),
            FormField(1, hints = listOf("creditcardexpirationmonth")),
            FormField(2, hints = listOf("creditcardexpirationyear"), maxLength = 4),
            FormField(3, hints = listOf("creditcardsecuritycode")),
        ))
        assertEquals(1, split.expMonth); assertEquals(2, split.expYear); assertTrue(split.fourDigitYear); assertNull(split.expiry)

        // Form đăng nhập thường: không có thẻ; chữ ngắn "pan"/"mm" không khớp một phần từ
        assertTrue(FormClassifier.classifyCard(listOf(FormField(0, texts = listOf("company name")), FormField(1, htmlType = "password"))).isEmpty)
        assertEquals("MM/YYYY", FormClassifier.classifyCard(listOf(FormField(0, hints = listOf("cc-number")), FormField(1, hints = listOf("cc-exp"), maxLength = 7))).expiryPattern)
    }

    @Test
    fun card_detectionDoesNotStealLoginForms_andSplitsMonthYear() {
        // Đăng nhập bằng "số thẻ thư viện" + mật khẩu: KHÔNG phải form thẻ (không có CVV / hết hạn)
        val library = listOf(FormField(0, texts = listOf("Số thẻ thư viện")), FormField(1, htmlType = "password", texts = listOf("Mật khẩu")))
        assertTrue(FormClassifier.classifyCard(library).isEmpty)
        val login = FormClassifier.classify(library, exclude = FormClassifier.classifyCard(library).indices)
        assertEquals(0, login.username); assertEquals(listOf(1), login.passwords)
        assertTrue(FormClassifier.classifyCard(listOf(FormField(0, texts = listOf("ID card number")), FormField(1, htmlType = "password"))).isEmpty)

        // "Expiration month" / "Expiration year" là hai ô riêng, không phải ô gộp MM/YY
        val split = FormClassifier.classifyCard(listOf(
            FormField(0, htmlAttrs = listOf("card-number")),
            FormField(1, htmlAttrs = listOf("expiration month")),
            FormField(2, htmlAttrs = listOf("expiration year"), maxLength = 4),
            FormField(3, htmlAttrs = listOf("cvv")),
        ))
        assertNull(split.expiry); assertEquals(1, split.expMonth); assertEquals(2, split.expYear); assertTrue(split.fourDigitYear); assertEquals(3, split.cvc)
        // Form CCCD / e-KYC: "Số thẻ căn cước" + ngày dd/mm/yyyy + "Ngày hết hạn" KHÔNG phải form thẻ
        assertTrue(FormClassifier.classifyCard(listOf(
            FormField(0, texts = listOf("Số thẻ căn cước")),
            FormField(1, texts = listOf("Ngày cấp (dd/mm/yyyy)")),
            FormField(2, texts = listOf("Ngày hết hạn")),
        )).isEmpty)
        // Thẻ thư viện + ngày sinh dd/mm/yyyy: vẫn là đăng nhập
        assertTrue(FormClassifier.classifyCard(listOf(
            FormField(0, texts = listOf("Số thẻ")), FormField(1, texts = listOf("Ngày sinh dd/mm/yyyy")), FormField(2, htmlType = "password"),
        )).isEmpty)
        // Ô "MM / YY" có maxlength 7: vẫn điền 2 chữ số năm
        val spaced = FormClassifier.classifyCard(listOf(
            FormField(0, htmlAttrs = listOf("cardnumber")), FormField(1, texts = listOf("MM / YY"), maxLength = 7), FormField(2, htmlAttrs = listOf("cvc")),
        ))
        assertEquals(1, spaced.expiry); assertEquals("MM/YY", spaced.expiryPattern)
        // Gợi ý chuẩn trùng ô: không gán một ô cho hai vai
        val dup = FormClassifier.classifyCard(listOf(FormField(0, hints = listOf("cc-number")), FormField(1, hints = listOf("cc-exp", "cc-exp-month"))))
        assertEquals(1, dup.expMonth); assertNull(dup.expiry)
    }

    @Test
    fun inAppWebPage_onlyNarrowsLinkedEntries_andGetsNoCards() {
        val cert = "ab".repeat(32)
        val linked = Entry(id = "z", title = "Zalo", url = "https://id.zalo.me", createdAt = 0, updatedAt = 0,
            linkedApps = listOf(com.samuelcruz.matkhau.model.LinkedApp("com.zing.zalo", cert)))
        val native = com.samuelcruz.matkhau.core.autofill.AutofillTarget.App("com.zing.zalo", cert)
        val phishing = native.copy(webHost = "zalo-login.evil.example")
        val real = native.copy(webHost = "id.zalo.me")
        assertTrue(com.samuelcruz.matkhau.core.autofill.AutofillMatcher.matches(linked, native))
        assertFalse(com.samuelcruz.matkhau.core.autofill.AutofillMatcher.matches(linked, phishing))
        assertTrue(com.samuelcruz.matkhau.core.autofill.AutofillMatcher.matches(linked, real))
        assertFalse(com.samuelcruz.matkhau.core.autofill.AutofillMatcher.matches(linked.copy(url = ""), real))
        assertTrue(com.samuelcruz.matkhau.core.autofill.AutofillMatcher.cardAllowed(native))
        assertFalse(com.samuelcruz.matkhau.core.autofill.AutofillMatcher.cardAllowed(real))
    }

    @Test
    fun recoveryKey_groupCheck() {
        val key = com.samuelcruz.matkhau.core.vault.RecoveryKey.format(ByteArray(20) { (it * 37).toByte() })
        val groups = key.split('-')
        assertEquals(8, groups.size)
        assertTrue(com.samuelcruz.matkhau.core.vault.RecoveryKey.groupMatches(key, 0, groups[0].lowercase()))
        assertTrue(com.samuelcruz.matkhau.core.vault.RecoveryKey.groupMatches(key, 7, " " + groups[7] + " "))
        assertFalse(com.samuelcruz.matkhau.core.vault.RecoveryKey.groupMatches(key, 1, groups[2]))
        assertFalse(com.samuelcruz.matkhau.core.vault.RecoveryKey.groupMatches(key, 9, groups[0]))
        // Gõ 0 / 1 / 8 thay cho O / I / B vẫn khớp
        assertEquals("OIBZ", com.samuelcruz.matkhau.core.vault.RecoveryKey.normalize("01-8z"))
    }

    // ------------------------------------------------------------------ file đính kèm

    private fun ref(id: String, data: ByteArray) =
        AttachmentRef(id, "a.bin", size = data.size.toLong(), chunks = Attachments.chunkCount(data.size), sha256 = Attachments.sha256Hex(data))

    @Test
    fun attachments_roundTripAndTamper() {
        val vek = Bytes.random(32)
        val vaultId = Bytes.random(16)
        val data = ByteArray(Attachments.CHUNK_SIZE * 2 + 123) { (it % 251).toByte() }
        val chunks = Attachments.seal(vek, vaultId, "A", data)
        assertEquals(3, chunks.size)
        val r = ref("A", data)
        val rows = chunks.associate { it.rowId to it.box }.toMutableMap()
        assertArrayEquals(data, Attachments.open(vek, vaultId, r, rows::get))

        // Thiếu đoạn cuối
        val missing = rows.toMutableMap().apply { remove(Attachments.rowId("A", 2)) }
        assertThrows(DecryptionFailedException::class.java) { Attachments.open(vek, vaultId, r, missing::get) }
        // Tráo hai đoạn
        val swapped = rows.toMutableMap().apply {
            val a = get(Attachments.rowId("A", 0))!!; put(Attachments.rowId("A", 0), get(Attachments.rowId("A", 1))!!); put(Attachments.rowId("A", 1), a)
        }
        assertThrows(DecryptionFailedException::class.java) { Attachments.open(vek, vaultId, r, swapped::get) }
        // Đoạn của file khác cùng vault
        val other = Attachments.seal(vek, vaultId, "B", data)
        val foreign = rows.toMutableMap().apply { put(Attachments.rowId("A", 0), other[0].box) }
        assertThrows(DecryptionFailedException::class.java) { Attachments.open(vek, vaultId, r, foreign::get) }
        // Khai báo ít đoạn hơn (cắt bớt) / sai SHA-256 / vault khác
        assertThrows(DecryptionFailedException::class.java) { Attachments.open(vek, vaultId, r.copy(size = Attachments.CHUNK_SIZE * 2L, chunks = 2), rows::get) }
        assertThrows(DecryptionFailedException::class.java) { Attachments.open(vek, vaultId, r.copy(sha256 = "00"), rows::get) }
        assertThrows(DecryptionFailedException::class.java) { Attachments.open(vek, Bytes.random(16), r, rows::get) }
        // Một bit trong ciphertext
        val flipped = rows.toMutableMap().apply {
            val b = get(Attachments.rowId("A", 1))!!
            put(Attachments.rowId("A", 1), SealedBox(b.nonce, b.ciphertext.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }))
        }
        assertThrows(DecryptionFailedException::class.java) { Attachments.open(vek, vaultId, r, flipped::get) }

        // File rỗng = 1 đoạn rỗng
        val empty = Attachments.seal(vek, vaultId, "E", ByteArray(0))
        assertEquals(1, empty.size)
        assertEquals(0, Attachments.open(vek, vaultId, ref("E", ByteArray(0))) { id -> empty.first { it.rowId == id }.box }.size)
        assertEquals("A", Attachments.attachmentIdOf(Attachments.rowId("A", 7)))
        assertNull(Attachments.attachmentIdOf("some-uuid"))
    }

    // ------------------------------------------------------------------ backup v2

    @Test
    fun backupV2_roundTrip_withAttachments_andReadsV1() {
        val created = VaultCrypto.createVault("pw".toByteArray(), fastKdf)
        val data = ByteArray(700_000) { (it * 7).toByte() }
        val chunks = Attachments.seal(created.vek, created.header.vaultId, "F", data)
        val payload = "{\"hello\":1}".toByteArray()
        val out = ByteArrayOutputStream()
        BackupFormat.write(out, created.header, created.vek, payload, chunks.map { it.rowId }) { id ->
            val c = chunks.first { it.rowId == id }; BackupFormat.Row(id, c.box.nonce, c.box.ciphertext)
        }
        val parsed = BackupFormat.parse(out.toByteArray())
        assertEquals(2, parsed.version)
        assertEquals(chunks.size, parsed.rows.size)
        val vek = VaultCrypto.unlockWithPassword(parsed.header, "pw".toByteArray())
        assertArrayEquals(payload, BackupFormat.decrypt(parsed, vek))
        val opened = Attachments.open(vek, parsed.header.vaultId, ref("F", data)) { id -> parsed.row(id)?.let(parsed::rowBox) }
        assertArrayEquals(data, opened)

        // File cũ (v1) vẫn đọc được, không có đoạn đính kèm
        val v1 = BackupFormat.parse(BackupFormat.createV1(created.header, created.vek, payload))
        assertEquals(1, v1.version); assertTrue(v1.rows.isEmpty())
        assertArrayEquals(payload, BackupFormat.decrypt(v1, created.vek))

        // Sửa header của v2 => khối mục không mở được
        val tampered = out.toByteArray().also { it[30] = (it[30].toInt() xor 1).toByte() }
        try {
            val p = BackupFormat.parse(tampered)
            assertThrows(DecryptionFailedException::class.java) { BackupFormat.decrypt(p, created.vek) }
        } catch (e: FormatException) { }
        // Cắt cụt / thừa byte
        assertThrows(FormatException::class.java) { BackupFormat.parse(out.toByteArray().copyOf(out.size() - 3)) }
        assertThrows(FormatException::class.java) { BackupFormat.parse(out.toByteArray() + byteArrayOf(0)) }
    }

    @Test
    fun backupV2_rejectsDuplicateOrForeignRowIds() {
        val created = VaultCrypto.createVault("pw".toByteArray(), fastKdf)
        val c = Attachments.seal(created.vek, created.header.vaultId, "F", ByteArray(10))[0]
        fun file(ids: List<String>) = ByteArrayOutputStream().also { o ->
            BackupFormat.write(o, created.header, created.vek, ByteArray(1), ids) { BackupFormat.Row(it, c.box.nonce, c.box.ciphertext) }
        }.toByteArray()
        assertThrows(FormatException::class.java) { BackupFormat.parse(file(listOf(c.rowId, c.rowId))) }
        assertThrows(FormatException::class.java) { BackupFormat.parse(file(listOf("not-an-attachment"))) }
    }

    // ------------------------------------------------------------------ chậm dần khi nhập sai, nhắc backup

    @Test
    fun throttle_policy() {
        assertEquals(0L, UnlockThrottle.delayMs(4))
        assertEquals(30_000L, UnlockThrottle.delayMs(5))
        assertEquals(30 * 60_000L, UnlockThrottle.delayMs(50))
        assertEquals(0L, UnlockThrottle.remainingMs(3, 1000, 1, 2000, 1))
        assertEquals(20_000L, UnlockThrottle.remainingMs(5, 1000, 1, 11_000, 1))
        assertEquals(0L, UnlockThrottle.remainingMs(5, 1000, 1, 40_000, 1))
        // Khởi động lại máy / đồng hồ lùi: chờ lại đủ, không được miễn
        assertEquals(30_000L, UnlockThrottle.remainingMs(5, 1000, 1, 5, 2))
        assertEquals(30_000L, UnlockThrottle.remainingMs(5, 50_000, 1, 10, 1))
        assertTrue(UnlockThrottle.needsRebase(1000, 1, 5, 2))
        assertFalse(UnlockThrottle.needsRebase(1000, 1, 5000, 1))
    }

    @Test
    fun backupReminder_policy() {
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        assertNull(BackupReminder.message(0, 0, 5, 0, now))                      // vault trống
        assertNotNull(BackupReminder.message(3, 0, 0, 0, now))                   // chưa backup lần nào
        assertNull(BackupReminder.message(3, 0, 0, now + 1, now))                // đang hoãn
        assertNull(BackupReminder.message(3, now - day, 0, 0, now))              // không có gì mới
        assertNull(BackupReminder.message(3, now - day, 5, 0, now))
        assertNotNull(BackupReminder.message(3, now - day, 11, 0, now))
        assertNotNull(BackupReminder.message(3, now - 31 * day, 1, 0, now))
    }

    // ------------------------------------------------------------------ model

    @Test
    fun entry_newFieldsDecodeOldJson_andDoNotLeak() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"id":"x","title":"t","createdAt":1,"updatedAt":2}"""
        val e = json.decodeFromString(Entry.serializer(), old)
        assertNull(e.card); assertNull(e.identity); assertTrue(e.attachments.isEmpty())
        val card = Entry(id = "c", type = EntryType.CARD, title = "Visa", createdAt = 0, updatedAt = 0, card = CardInfo(number = "4111111111111111", cvv = "123", pin = "9999"))
        val back = json.decodeFromString(Entry.serializer(), json.encodeToString(Entry.serializer(), card))
        assertEquals(card, back)
        assertFalse(card.card.toString().contains("4111"))
        assertFalse(card.toString().contains("123"))
    }
}
