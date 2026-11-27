package com.samuelcruz.matkhau.core

import com.samuelcruz.matkhau.core.crypto.Bytes
import com.samuelcruz.matkhau.core.crypto.DecryptionFailedException
import com.samuelcruz.matkhau.core.crypto.KdfParams
import com.samuelcruz.matkhau.core.format.FormatException
import com.samuelcruz.matkhau.core.vault.BackupFormat
import com.samuelcruz.matkhau.core.vault.RecoveryKey
import com.samuelcruz.matkhau.core.vault.VaultCrypto
import com.samuelcruz.matkhau.core.vault.VaultHeader
import com.samuelcruz.matkhau.core.vault.WrongSecretException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VaultCryptoTest {
    // Tham số nhẹ để test chạy nhanh; app thật dùng KdfParams.DEFAULT.
    private val fast = KdfParams(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1)
    private val pw = "correct horse battery staple".toByteArray()

    @Test
    fun create_thenUnlockWithPasswordAndRecoveryKey() {
        val created = VaultCrypto.createVault(pw, fast)
        val header = VaultHeader.decode(created.header.encode())

        assertArrayEquals(created.vek, VaultCrypto.unlockWithPassword(header, pw))
        val recoveryText = RecoveryKey.format(created.recoveryKey)
        assertArrayEquals(created.vek, VaultCrypto.unlockWithRecoveryKey(header, RecoveryKey.parse(recoveryText)))
    }

    @Test
    fun wrongPassword_isRejected() {
        val created = VaultCrypto.createVault(pw, fast)
        try {
            VaultCrypto.unlockWithPassword(created.header, "wrong password".toByteArray())
            fail()
        } catch (e: WrongSecretException) {
        }
    }

    @Test
    fun changePassword_keepsSameVek_andOldPasswordStopsWorking() {
        val created = VaultCrypto.createVault(pw, fast)
        val newPw = "new master password 2026".toByteArray()
        val updated = VaultCrypto.changePassword(created.header, created.vek, newPw)

        assertArrayEquals(created.vek, VaultCrypto.unlockWithPassword(updated, newPw))
        try {
            VaultCrypto.unlockWithPassword(updated, pw)
            fail()
        } catch (e: WrongSecretException) {
        }
        // Recovery key vẫn dùng được sau khi đổi mật khẩu
        assertArrayEquals(created.vek, VaultCrypto.unlockWithRecoveryKey(updated, created.recoveryKey))
    }

    @Test
    fun regenerateRecoveryKey_invalidatesOldKey() {
        val created = VaultCrypto.createVault(pw, fast)
        val (updated, newKey) = VaultCrypto.regenerateRecoveryKey(created.header, created.vek)
        assertArrayEquals(created.vek, VaultCrypto.unlockWithRecoveryKey(updated, newKey))
        try {
            VaultCrypto.unlockWithRecoveryKey(updated, created.recoveryKey)
            fail()
        } catch (e: WrongSecretException) {
        }
    }

    @Test
    fun items_areBoundToTheirId() {
        val created = VaultCrypto.createVault(pw, fast)
        val id = created.header.vaultId
        val box = VaultCrypto.encryptItem(created.vek, id, "item-1", "GitHub pw".toByteArray())
        assertEquals("GitHub pw", String(VaultCrypto.decryptItem(created.vek, id, "item-1", box)))
        try {
            // Kẻ tấn công tráo ciphertext của mục này sang mục khác -> phải bị phát hiện
            VaultCrypto.decryptItem(created.vek, id, "item-2", box)
            fail()
        } catch (e: DecryptionFailedException) {
        }
    }

    @Test
    fun header_tamperedKdfParams_failsToUnlock() {
        val created = VaultCrypto.createVault(pw, fast)
        val weakened = VaultHeader(
            created.header.vaultId,
            KdfParams(memoryKiB = 9 * 1024, iterations = 1, parallelism = 1),
            created.header.passwordSlot,
            created.header.recoverySlot,
        )
        try {
            VaultCrypto.unlockWithPassword(weakened, pw)
            fail()
        } catch (e: WrongSecretException) {
        }
    }

    @Test
    fun header_rejectsGarbageAndInsaneParams() {
        try {
            VaultHeader.decode(Bytes.random(100)); fail()
        } catch (e: FormatException) {
        }
        val bytes = VaultCrypto.createVault(pw, fast).header.encode()
        try {
            VaultHeader.decode(bytes + byteArrayOf(0)); fail()
        } catch (e: FormatException) {
        }
        try {
            VaultHeader.decode(bytes.copyOf(bytes.size - 3)); fail()
        } catch (e: FormatException) {
        }
    }

    @Test
    fun backup_roundTrip_andRejectsTampering() {
        val created = VaultCrypto.createVault(pw, fast)
        val payload = """{"entries":[{"title":"Bank"}]}""".toByteArray()
        val file = BackupFormat.create(created.header, created.vek, payload)

        // File backup không chứa plaintext
        assertFalse(String(file, Charsets.ISO_8859_1).contains("Bank"))

        val parsed = BackupFormat.parse(file)
        val vek = VaultCrypto.unlockWithPassword(parsed.header, pw)
        assertArrayEquals(payload, BackupFormat.decrypt(parsed, vek))

        // Sửa 1 byte ở bất kỳ đâu -> từ chối (format hỏng, sai mật khẩu, hoặc lỗi toàn vẹn)
        for (pos in listOf(10, 40, file.size / 2, file.size - 1)) {
            val bad = file.copyOf().also { it[pos] = (it[pos].toInt() xor 0x01).toByte() }
            assertTrue("byte $pos", rejected(bad))
        }
    }

    @Test
    fun recoveryKey_formatAndParseTolerantInput() {
        val raw = RecoveryKey.generate()
        val text = RecoveryKey.format(raw)
        assertEquals(39, text.length) // 32 ký tự + 7 gạch
        assertArrayEquals(raw, RecoveryKey.parse(text.lowercase().replace("-", " ")))
    }

    private fun rejected(file: ByteArray): Boolean = try {
        val parsed = BackupFormat.parse(file)
        val vek = VaultCrypto.unlockWithPassword(parsed.header, pw)
        BackupFormat.decrypt(parsed, vek)
        false
    } catch (e: FormatException) {
        true
    } catch (e: WrongSecretException) {
        true
    } catch (e: DecryptionFailedException) {
        true
    }
}
