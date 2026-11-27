package com.samuelcruz.matkhau.core

import com.samuelcruz.matkhau.core.crypto.Aead
import com.samuelcruz.matkhau.core.crypto.Argon2id
import com.samuelcruz.matkhau.core.crypto.Bytes
import com.samuelcruz.matkhau.core.crypto.DecryptionFailedException
import com.samuelcruz.matkhau.core.crypto.SealedBox
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class CryptoTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Test vector chính thức của Argon2id trong RFC 9106, mục 5.3. */
    @Test
    fun argon2id_matchesRfc9106Vector() {
        val out = Argon2id.raw(
            password = ByteArray(32) { 0x01 },
            salt = ByteArray(16) { 0x02 },
            memoryKiB = 32,
            iterations = 3,
            parallelism = 4,
            outLength = 32,
            secret = ByteArray(8) { 0x03 },
            associatedData = ByteArray(12) { 0x04 },
        )
        val expected = hex("0d 64 0d f5 8d 78 76 6c 08 c0 37 a3 4a 8b 53 c9 d0 1e f0 45 2d 75 b6 5e b5 25 20 e9 6b 01 e6 59")
        assertArrayEquals(expected, out)
    }

    @Test
    fun aead_roundTrip() {
        val key = Bytes.random(32)
        val box = Aead.seal(key, "xin chào".toByteArray(), "ctx".toByteArray())
        assertEquals("xin chào", String(Aead.open(key, box, "ctx".toByteArray())))
    }

    @Test
    fun aead_noncesAreUnique() {
        val key = Bytes.random(32)
        val a = Aead.seal(key, ByteArray(4), ByteArray(0))
        val b = Aead.seal(key, ByteArray(4), ByteArray(0))
        assertFalse(a.nonce.contentEquals(b.nonce))
        assertFalse(a.ciphertext.contentEquals(b.ciphertext))
    }

    @Test
    fun aead_rejectsTamperedCiphertext() {
        val key = Bytes.random(32)
        val box = Aead.seal(key, "secret".toByteArray(), ByteArray(0))
        val tampered = box.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        expectFailure { Aead.open(key, SealedBox(box.nonce, tampered), ByteArray(0)) }
    }

    @Test
    fun aead_rejectsWrongAadAndWrongKey() {
        val key = Bytes.random(32)
        val box = Aead.seal(key, "secret".toByteArray(), "item-A".toByteArray())
        expectFailure { Aead.open(key, box, "item-B".toByteArray()) }
        expectFailure { Aead.open(Bytes.random(32), box, "item-A".toByteArray()) }
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Lẽ ra phải ném DecryptionFailedException")
        } catch (e: DecryptionFailedException) {
            // đúng như mong đợi
        }
    }
}
