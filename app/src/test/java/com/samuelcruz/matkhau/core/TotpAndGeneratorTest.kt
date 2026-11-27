package com.samuelcruz.matkhau.core

import com.samuelcruz.matkhau.core.format.Base32
import com.samuelcruz.matkhau.core.generator.PassphraseOptions
import com.samuelcruz.matkhau.core.generator.PasswordGenerator
import com.samuelcruz.matkhau.core.generator.PasswordOptions
import com.samuelcruz.matkhau.core.generator.Strength
import com.samuelcruz.matkhau.core.generator.StrengthEstimator
import com.samuelcruz.matkhau.core.totp.Totp
import com.samuelcruz.matkhau.core.totp.TotpAlgorithm
import com.samuelcruz.matkhau.core.totp.TotpConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpAndGeneratorTest {

    /** Test vector RFC 4648 mục 10. */
    @Test
    fun base32_rfc4648Vectors() {
        val vectors = mapOf(
            "" to "", "f" to "MY======", "fo" to "MZXQ====", "foo" to "MZXW6===",
            "foob" to "MZXW6YQ=", "fooba" to "MZXW6YTB", "foobar" to "MZXW6YTBOI======",
        )
        for ((plain, encoded) in vectors) {
            assertEquals(encoded, Base32.encode(plain.toByteArray()))
            assertArrayEquals(plain.toByteArray(), Base32.decode(encoded))
        }
    }

    /** Test vector RFC 6238 phụ lục B (8 chữ số). */
    @Test
    fun totp_rfc6238Vectors() {
        val sha1 = TotpConfig("12345678901234567890".toByteArray(), digits = 8, algorithm = TotpAlgorithm.SHA1)
        val sha256 = TotpConfig("12345678901234567890123456789012".toByteArray(), digits = 8, algorithm = TotpAlgorithm.SHA256)
        val sha512 = TotpConfig(
            "1234567890123456789012345678901234567890123456789012345678901234".toByteArray(),
            digits = 8, algorithm = TotpAlgorithm.SHA512,
        )
        val expected = listOf(
            Triple(59L, sha1, "94287082"), Triple(59L, sha256, "46119246"), Triple(59L, sha512, "90693936"),
            Triple(1111111109L, sha1, "07081804"), Triple(1111111111L, sha1, "14050471"),
            Triple(1234567890L, sha1, "89005924"), Triple(2000000000L, sha1, "69279037"),
            Triple(20000000000L, sha1, "65353130"),
        )
        for ((time, cfg, code) in expected) assertEquals("t=$time", code, Totp.generate(cfg, time))
    }

    @Test
    fun totp_parsesOtpauthUri() {
        val cfg = Totp.parse("otpauth://totp/GitHub:user%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&digits=6&period=30")
        assertEquals("GitHub", cfg.issuer)
        assertEquals("user@example.com", cfg.account)
        assertEquals(6, cfg.digits)
        assertArrayEquals(Base32.decode("JBSWY3DPEHPK3PXP"), cfg.secret)
        assertEquals(6, Totp.generate(cfg, 1_700_000_000L).length)
        assertTrue(Totp.isValid("jbsw y3dp ehpk 3pxp"))
        assertFalse(Totp.isValid("not-a-secret!"))
        assertFalse(Totp.isValid("otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun generator_respectsOptions() {
        repeat(200) {
            val pw = PasswordGenerator.generate(PasswordOptions(length = 16, symbols = false, excludeAmbiguous = true))
            assertEquals(16, pw.length)
            assertTrue(pw.any { it.isUpperCase() } && pw.any { it.isLowerCase() } && pw.any { it.isDigit() })
            assertTrue(pw.all { it.isLetterOrDigit() })
            assertFalse(pw.any { it in "Il1O0o" })
        }
        val onlyDigits = PasswordGenerator.generate(PasswordOptions(length = 10, uppercase = false, lowercase = false, symbols = false))
        assertTrue(onlyDigits.all { it.isDigit() })
    }

    @Test
    fun passphrase_hasRequestedWordCount() {
        val words = (0 until 2048).map { "word$it" }
        val phrase = PasswordGenerator.passphrase(words, PassphraseOptions(words = 6, separator = "-"))
        assertEquals(6, phrase.split("-").size)
        val bits = PasswordGenerator.passphraseEntropyBits(2048, PassphraseOptions(words = 6))
        assertTrue(bits > 69 && bits < 70) // 6*11 + log2(10)
    }

    @Test
    fun strength_ranksObviousCases() {
        assertEquals(Strength.VERY_WEAK, StrengthEstimator.estimate("password"))
        assertEquals(Strength.VERY_WEAK, StrengthEstimator.estimate("aaaaaaaaaaaa"))
        assertTrue(StrengthEstimator.estimate("Tre-Xanh-Bien-Rong-7-Mua") >= Strength.STRONG)
    }
}
