package com.samuelcruz.matkhau.core

import com.samuelcruz.matkhau.core.crypto.DecryptionFailedException
import com.samuelcruz.matkhau.core.crypto.KdfParams
import com.samuelcruz.matkhau.core.format.Base32
import com.samuelcruz.matkhau.core.format.FormatException
import com.samuelcruz.matkhau.core.totp.Totp
import com.samuelcruz.matkhau.core.vault.BackupFormat
import com.samuelcruz.matkhau.core.vault.MasterPassword
import com.samuelcruz.matkhau.core.vault.RecoveryKey
import com.samuelcruz.matkhau.core.vault.VaultCrypto
import com.samuelcruz.matkhau.core.vault.VaultHeader
import com.samuelcruz.matkhau.core.vault.WrongSecretException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer
import kotlin.random.Random

/**
 * Dữ liệu hỏng / người dùng gõ bậy chỉ được ném đúng các exception mà app bắt
 * (FormatException, WrongSecretException, DecryptionFailedException), không bao giờ làm app crash.
 */
class RobustnessTest {
    private val fast = KdfParams(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1)

    @Test
    fun totpIsValid_neverThrows_onMalformedInput() {
        val samples = listOf(
            "otpauth://totp/x?secret=%", "otpauth://totp/x?secret=%zz", "otpauth://totp/%E0%A4%A?secret=JBSWY3DPEHPK3PXP",
            "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&period=0", "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=99",
            "otpauth://totp", "otpauth://", "otpauth://totp/x?secret=", "otpauth://totp/x?=&&==", "ñandú", "😀", " ", "",
        )
        for (s in samples) assertFalse("'$s'", Totp.isValid(s))
        val rnd = Random(42)
        val alphabet = "ABCXYZ234567abc=%&?/:+ -_.0189otpauth"
        repeat(3000) {
            val s = (if (rnd.nextBoolean()) "otpauth://totp/" else "") +
                String(CharArray(rnd.nextInt(0, 40)) { alphabet[rnd.nextInt(alphabet.length)] })
            Totp.isValid(s) // chỉ cần không ném exception
        }
    }

    @Test
    fun recoveryKeyAndBase32_onlyThrowFormatException() {
        val rnd = Random(7)
        repeat(3000) {
            val s = String(CharArray(rnd.nextInt(0, 45)) { (rnd.nextInt(32, 0x2FF)).toChar() })
            try { RecoveryKey.parse(s) } catch (e: FormatException) {}
            try { Base32.decode(s) } catch (e: FormatException) {}
        }
    }

    @Test
    fun mutatedHeaderAndBackup_areRejectedCleanly() {
        val pw = "correct horse battery staple".toByteArray()
        val created = VaultCrypto.createVault(pw, fast)
        val header = created.header.encode()
        val backup = BackupFormat.create(created.header, created.vek, "{}".toByteArray())
        val rnd = Random(3)
        repeat(300) {
            val bad = backup.copyOf()
            repeat(rnd.nextInt(1, 4)) { val i = rnd.nextInt(bad.size); bad[i] = (bad[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() }
            val cut = if (rnd.nextInt(4) == 0) bad.copyOf(rnd.nextInt(bad.size)) else bad
            try {
                val parsed = BackupFormat.parse(cut)
                BackupFormat.decrypt(parsed, VaultCrypto.unlockWithPassword(parsed.header, pw))
                throw AssertionError("backup hỏng mà vẫn mở được")
            } catch (e: FormatException) {
            } catch (e: WrongSecretException) {
            } catch (e: DecryptionFailedException) {
            }
            try { VaultHeader.decode(header.copyOf(rnd.nextInt(header.size))) ; throw AssertionError("header cụt") } catch (e: FormatException) {}
        }
        // Độ dài khai báo khổng lồ không được làm cấp phát bộ nhớ lớn
        val huge = "LVBK".toByteArray() + byteArrayOf(0, 1) + header + byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        try { BackupFormat.parse(huge); throw AssertionError() } catch (e: FormatException) {}
    }

    @Test
    fun masterPassword_isUnicodeNormalized() {
        val nfc = Normalizer.normalize("Mật khẩu của tôi 2026", Normalizer.Form.NFC)
        val nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD)
        assertFalse(nfc == nfd)
        assertArrayEquals(MasterPassword.toBytes(nfc), MasterPassword.toBytes(nfd))

        val created = VaultCrypto.createVault(MasterPassword.toBytes(nfc), fast)
        assertArrayEquals(created.vek, VaultCrypto.unlockWithPassword(created.header, MasterPassword.toBytes(nfd)))
        assertTrue(MasterPassword.toBytes("abc").contentEquals("abc".toByteArray()))

        // Vault tạo bằng 0.1.0 với mật khẩu gõ dạng NFD (chưa chuẩn hóa) vẫn mở được qua danh sách ứng viên.
        val legacy = VaultCrypto.createVault(nfd.toByteArray(Charsets.UTF_8), fast)
        val candidates = MasterPassword.unlockCandidates(nfd)
        assertEquals(2, candidates.size)
        val opened = candidates.mapNotNull { pw ->
            try { VaultCrypto.unlockWithPassword(legacy.header, pw) } catch (e: WrongSecretException) { null }
        }
        assertEquals(1, opened.size)
        assertArrayEquals(legacy.vek, opened[0])
        assertEquals(1, MasterPassword.unlockCandidates("ascii only").size)
    }
}
