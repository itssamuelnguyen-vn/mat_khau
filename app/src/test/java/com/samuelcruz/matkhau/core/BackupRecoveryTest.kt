package com.samuelcruz.matkhau.core

import com.samuelcruz.matkhau.core.crypto.DecryptionFailedException
import com.samuelcruz.matkhau.core.crypto.KdfParams
import com.samuelcruz.matkhau.core.format.FormatException
import com.samuelcruz.matkhau.core.vault.BackupFormat
import com.samuelcruz.matkhau.core.vault.RecoveryKey
import com.samuelcruz.matkhau.core.vault.VaultCrypto
import com.samuelcruz.matkhau.core.vault.WrongSecretException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

/** File backup chứa cả slot mật khẩu chính lẫn slot Recovery Key, mở được bằng một trong hai. */
class BackupRecoveryTest {
    private val fast = KdfParams(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1)
    private val pw = "correct horse battery staple".toByteArray()
    private val payload = """{"formatVersion":1,"exportedAt":0,"entries":[]}""".toByteArray()

    @Test
    fun backup_opensWithRecoveryKey_onAnotherDevice() {
        val created = VaultCrypto.createVault(pw, fast)
        val file = BackupFormat.create(created.header, created.vek, payload)

        val parsed = BackupFormat.parse(file)
        assertNotNull(parsed.header.recoverySlot)
        val recovery = RecoveryKey.parse(RecoveryKey.format(created.recoveryKey))
        val vek = VaultCrypto.unlockWithRecoveryKey(parsed.header, recovery)
        assertArrayEquals(payload, BackupFormat.decrypt(parsed, vek))
    }

    @Test
    fun backup_tamperedRecoverySlot_isRejectedEvenWithCorrectPassword() {
        val created = VaultCrypto.createVault(pw, fast)
        val file = BackupFormat.create(created.header, created.vek, payload)
        val headerSize = created.header.encode().size
        // Byte cuối của header = byte cuối ciphertext của slot Recovery Key ("LVBK" + u16 version đứng trước header).
        val pos = 4 + 2 + headerSize - 1
        val bad = file.copyOf().also { it[pos] = (it[pos].toInt() xor 0x01).toByte() }
        try {
            val parsed = BackupFormat.parse(bad)
            // Slot mật khẩu vẫn nguyên nên mở được VEK, nhưng AAD của khối dữ liệu phủ cả header => GCM từ chối.
            val vek = VaultCrypto.unlockWithPassword(parsed.header, pw)
            BackupFormat.decrypt(parsed, vek)
            fail("Backup bị sửa phải bị từ chối")
        } catch (e: DecryptionFailedException) {
        } catch (e: FormatException) {
        } catch (e: WrongSecretException) {
        }
    }
}
