package com.samuelcruz.matkhau.core

import com.samuelcruz.matkhau.core.crypto.Aead
import com.samuelcruz.matkhau.core.crypto.Argon2id
import com.samuelcruz.matkhau.core.crypto.Bytes
import com.samuelcruz.matkhau.core.crypto.KdfParams
import com.samuelcruz.matkhau.core.crypto.SealedBox
import com.samuelcruz.matkhau.core.format.ByteWriter
import com.samuelcruz.matkhau.core.format.FormatException
import com.samuelcruz.matkhau.core.generator.PassphraseOptions
import com.samuelcruz.matkhau.core.generator.PasswordGenerator
import com.samuelcruz.matkhau.core.generator.PasswordOptions
import com.samuelcruz.matkhau.core.totp.Totp
import com.samuelcruz.matkhau.core.vault.BackupFormat
import com.samuelcruz.matkhau.core.vault.KeySlot
import com.samuelcruz.matkhau.core.vault.RecoveryKey
import com.samuelcruz.matkhau.core.vault.UnlockPolicy
import com.samuelcruz.matkhau.core.vault.VaultCrypto
import com.samuelcruz.matkhau.core.vault.VaultHeader
import com.samuelcruz.matkhau.core.vault.WrongSecretException
import com.samuelcruz.matkhau.security.BiometricKeyManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Đóng băng định dạng (AAD, thứ tự trường) bằng vector tính độc lập, và các trường hợp biên.
 * Nếu một test ở đây hỏng sau khi sửa code => định dạng đã đổi => vault / backup cũ không mở được nữa.
 */
class FormatAndEdgeCaseTest {
    private val fast = KdfParams(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1)
    private val pw = "correct horse battery staple".toByteArray()
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val ascii = Charsets.US_ASCII

    /** Ciphertext tính bằng thư viện `cryptography` của Python (AES-GCM), AAD = "localvault/item/v1" + vaultId + itemId. */
    @Test
    fun itemAad_matchesIndependentGoldenVector() {
        val key = ByteArray(32) { 0x11 }
        val nonce = ByteArray(12) { 0x22 }
        val vaultId = ByteArray(16) { 0x33 }
        val ct = hex("6cd56f2caca3f07ddf1da65526868abd7efcef9f36704c33f581546db24915b7d6f63164")
        val plain = VaultCrypto.decryptItem(key, vaultId, "item-1", SealedBox(nonce, ct))
        assertEquals("""{"hello":"xin chao"}""", String(plain, Charsets.UTF_8))
    }

    /** Dựng slot bằng tay theo CLAUDE.md: AAD = "localvault/key-slot/v1" + type + vaultId. */
    @Test
    fun keySlotAad_andSlotTypes_areFrozen() {
        val vaultId = Bytes.random(16)
        val vek = Bytes.random(32)
        val salt = Bytes.random(16)
        val kek = Argon2id.deriveKey(pw, salt, fast)
        val box = Aead.seal(kek, vek, "localvault/key-slot/v1".toByteArray(ascii) + byteArrayOf(1) + vaultId)
        val header = VaultHeader(vaultId, fast, KeySlot(salt, box), null)
        assertArrayEquals(vek, VaultCrypto.unlockWithPassword(VaultHeader.decode(header.encode()), pw))

        // Đặt slot mật khẩu vào vị trí slot recovery: type trong AAD khác => phải thất bại.
        val swapped = VaultHeader(vaultId, fast, KeySlot(salt, box), KeySlot(salt, box))
        try { VaultCrypto.unlockWithRecoveryKey(swapped, pw); fail() } catch (e: WrongSecretException) {}
        // Slot của vault khác (vaultId khác) => thất bại.
        val otherVault = VaultHeader(Bytes.random(16), fast, KeySlot(salt, box), null)
        try { VaultCrypto.unlockWithPassword(otherVault, pw); fail() } catch (e: WrongSecretException) {}
    }

    /** Dựng file backup bằng tay theo CLAUDE.md: AAD = "localvault/backup/v1" + mọi byte trước nonce. */
    @Test
    fun backupLayout_isFrozen() {
        val created = VaultCrypto.createVault(pw, fast)
        val prefix = ByteWriter().raw("LVBK".toByteArray(ascii)).u16(1).raw(created.header.encode()).toByteArray()
        val box = Aead.seal(created.vek, "payload".toByteArray(), "localvault/backup/v1".toByteArray(ascii) + prefix)
        val file = ByteWriter().raw(prefix).bytes(box.nonce).bytes(box.ciphertext).toByteArray()
        val parsed = BackupFormat.parse(file)
        assertEquals("payload", String(BackupFormat.decrypt(parsed, VaultCrypto.unlockWithPassword(parsed.header, pw))))
    }

    @Test
    fun header_everyTruncationAndBitFlip_failsSafely() {
        val created = VaultCrypto.createVault(pw, fast)
        val bytes = created.header.encode()
        for (len in 0 until bytes.size) {
            try { VaultHeader.decode(bytes.copyOf(len)); fail("cắt ở $len") } catch (e: FormatException) {}
        }
        for (i in bytes.indices) {
            val bad = bytes.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            val h = try { VaultHeader.decode(bad) } catch (e: FormatException) { continue }
            try {
                VaultCrypto.unlockWithPassword(h, pw)
                // Byte nằm trong slot recovery: slot mật khẩu vẫn đúng là hợp lệ, nhưng recovery phải hỏng.
                try { VaultCrypto.unlockWithRecoveryKey(h, created.recoveryKey); fail("byte $i") } catch (e: WrongSecretException) {}
            } catch (e: WrongSecretException) {
            }
        }
        val noRecovery = VaultHeader(created.header.vaultId, fast, created.header.passwordSlot, null)
        val decoded = VaultHeader.decode(noRecovery.encode())
        assertTrue(decoded.recoverySlot == null)
        try { VaultCrypto.unlockWithRecoveryKey(decoded, created.recoveryKey); fail() } catch (e: WrongSecretException) {}
    }

    @Test
    fun kdfParams_bounds() {
        assertTrue(KdfParams(8 * 1024, 1, 1).isSane())
        assertTrue(KdfParams(256 * 1024, 20, 8).isSane())
        for (p in listOf(KdfParams(8 * 1024 - 1, 1, 1), KdfParams(256 * 1024 + 1, 1, 1), KdfParams(8192, 0, 1),
            KdfParams(8192, 21, 1), KdfParams(8192, 1, 0), KdfParams(8192, 1, 9))) assertFalse(p.toString(), p.isSane())
        assertTrue(KdfParams.DEFAULT.isSane())
    }

    @Test
    fun totp_edgeCases() {
        val ok = "otpauth://totp/A:b?secret=JBSWY3DPEHPK3PXP"
        assertTrue(Totp.isValid(ok))
        for (bad in listOf("$ok&digits=5", "$ok&digits=9", "$ok&digits=abc", "$ok&period=0", "$ok&period=301",
            "$ok&period=x", "$ok&algorithm=MD5", "otpauth://totp/A:b?issuer=x", "otpauth://hotp/A:b?secret=JBSWY3DPEHPK3PXP",
            "otpauth://totp/a%?secret=JBSWY3DPEHPK3PXP", "otpauth://totp/a?secret%zz=1", "JBSWY3DP", "JBSWY3DPEHPK3PX!")) {
            assertFalse(bad, Totp.isValid(bad))
            assertTrue(bad, Totp.parseOrNull(bad) == null)
        }
        val cfg = Totp.parse("$ok&digits=8&period=60&algorithm=sha256")
        assertEquals(8, cfg.digits); assertEquals(60, cfg.period)
        assertEquals(8, Totp.generate(cfg, 1_700_000_000L).length)
        assertTrue(Totp.secondsRemaining(cfg, 1_700_000_000L) in 1..60)
    }

    @Test
    fun recoveryKey_parsingEdgeCases() {
        val raw = RecoveryKey.generate()
        val text = RecoveryKey.format(raw)
        assertArrayEquals(raw, RecoveryKey.parse(" ${text.lowercase()}\n"))
        val compact = text.replace("-", "")
        // 0→O, 1→I, 8→B được sửa tự động
        val confusable = compact.replace('O', '0').replace('I', '1').replace('B', '8')
        assertArrayEquals(raw, RecoveryKey.parse(confusable))
        for (bad in listOf(compact.dropLast(1), compact + "A", compact.dropLast(1) + "9", compact.dropLast(1) + "Ａ")) {
            try { RecoveryKey.parse(bad); fail(bad) } catch (e: FormatException) {}
        }
    }

    @Test
    fun generator_validatesOptions() {
        for (o in listOf(PasswordOptions(length = 7), PasswordOptions(length = 129),
            PasswordOptions(uppercase = false, lowercase = false, digits = false, symbols = false))) {
            try { PasswordGenerator.generate(o); fail(o.toString()) } catch (e: IllegalArgumentException) {}
        }
        repeat(200) {
            val s = PasswordGenerator.generate(PasswordOptions(length = 12, uppercase = false, lowercase = false, digits = false, excludeAmbiguous = true))
            assertFalse(s, s.any { it in "Il1O0o|`'\";:,." })
        }
        val words = (0 until 2048).map { "w$it" }
        for (n in listOf(2, 21)) {
            try { PasswordGenerator.passphrase(words, PassphraseOptions(words = n)); fail() } catch (e: IllegalArgumentException) {}
        }
        try { PasswordGenerator.passphrase(words.take(1023), PassphraseOptions()); fail() } catch (e: IllegalArgumentException) {}
    }

    @Test
    fun unlockPolicy_neverRejectsVerifiedKey() {
        assertTrue(UnlockPolicy.accept(total = 1, failed = 1, keyVerified = true))   // 1 mục hỏng + mật khẩu đúng => vẫn mở
        assertTrue(UnlockPolicy.accept(total = 0, failed = 0, keyVerified = false))
        assertTrue(UnlockPolicy.accept(total = 3, failed = 2, keyVerified = false))
        assertFalse(UnlockPolicy.accept(total = 3, failed = 3, keyVerified = false))
    }

    @Test
    fun biometricBlob_roundTripAndRejectsBadInput() {
        val iv = Bytes.random(12)
        val ct = Bytes.random(48)
        val (iv2, ct2) = BiometricKeyManager.decodeBlob(BiometricKeyManager.encodeBlob(iv, ct))
        assertArrayEquals(iv, iv2); assertArrayEquals(ct, ct2)
        val good = BiometricKeyManager.encodeBlob(iv, ct)
        // byte 1 = blob v1 của 0.1.0 (không có AAD) => từ chối, người dùng bật lại vân tay
        for (bad in listOf(byteArrayOf(1) + good.copyOfRange(1, good.size), byteArrayOf(3) + good.copyOfRange(1, good.size), good + byteArrayOf(0),
            BiometricKeyManager.encodeBlob(ByteArray(33), ct), good.copyOf(good.size - 1))) {
            try { BiometricKeyManager.decodeBlob(bad); fail() } catch (e: Exception) {}
        }
        assertEquals("localvault/biometric/v1".length + 16, BiometricKeyManager.aad(ByteArray(16)).size)
    }
}
