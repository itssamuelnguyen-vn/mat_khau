package com.samuelcruz.matkhau.core

import com.samuelcruz.matkhau.core.generator.Strength
import com.samuelcruz.matkhau.core.generator.StrengthEstimator
import com.samuelcruz.matkhau.core.health.PasswordHealth
import com.samuelcruz.matkhau.model.Entry
import com.samuelcruz.matkhau.model.EntryType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthAndHistoryTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun login(id: String, pw: String, totp: String = "", url: String = "https://x.com", since: Long = now) =
        Entry(id = id, title = id, password = pw, totp = totp, url = url, createdAt = since, updatedAt = since)

    // ------------------------------------------------------------------ độ mạnh (spec 3.5)

    @Test
    fun strength_catchesPredictablePatterns() {
        val weak = listOf(
            "password", "P@ssw0rd", "Passw0rd!", "qwerty123", "matkhau2024", "anhyeuem123", "01011990", "Nam19901990",
            "aaaaaaaaaaaa", "abcdefgh1234", "1qaz2wsx3edc", "Qwertyuiop12",
        )
        for (pw in weak) {
            val r = StrengthEstimator.analyze(pw)
            assertTrue("$pw → ${r.strength} (${r.bits.toInt()} bit)", r.strength <= Strength.WEAK)
            assertTrue("$pw phải có lý do", r.warnings.isNotEmpty())
        }
        // "8 ký tự + hoa + số" KHÔNG được coi là mạnh (spec 3.5)
        assertTrue(StrengthEstimator.estimate("Hanoi2024") <= Strength.WEAK)
    }

    @Test
    fun strength_rewardsRandomAndLongPasswords() {
        for (pw in listOf("kX9#mQ2\$vL7@pR4!", "Tre-Xanh-Bien-Rong-7-Mua", "u8Rk-2fZq-Lm9w-Tq3x")) {
            assertTrue(pw, StrengthEstimator.estimate(pw) >= Strength.STRONG)
        }
        assertEquals(Strength.VERY_WEAK, StrengthEstimator.estimate(""))
        assertTrue("Ngắn hơn 12 ký tự" in StrengthEstimator.analyze("xK9#mQ2").warnings)
        // Cảnh báo hiện dưới ô mật khẩu đang che: không được chứa chữ nào của mật khẩu
        val w = StrengthEstimator.analyze("Liverpool1998!").warnings.joinToString()
        assertFalse(w, w.contains("liverpool", ignoreCase = true) || w.contains("1998"))
    }

    @Test
    fun strength_isFastEnoughForEveryKeystroke() {
        val long = (1..128).joinToString("") { "aZ3#"[it % 4].toString() }
        val t = System.nanoTime()
        repeat(50) { StrengthEstimator.analyze(long) }
        assertTrue((System.nanoTime() - t) / 50 < 50_000_000) // < 50 ms mỗi lần
    }

    // ------------------------------------------------------------------ sức khỏe (spec 3.4, 3.6, 3.7)

    @Test
    fun health_findsWeakReusedOldAndMissing() {
        val strong = "kX9#mQ2\$vL7@pR4!"
        val entries = listOf(
            login("a", strong, totp = "JBSWY3DPEHPK3PXP"),
            login("b", "password"),                                  // yếu
            login("c", "Shared-Secret-9x!Q"), login("d", "Shared-Secret-9x!Q"), // trùng
            login("e", "Old-but-Strong-77#z", since = now - 400 * day, url = ""),  // cũ + thiếu URL
            Entry(id = "n", type = EntryType.NOTE, title = "note", notes = "x", createdAt = 0, updatedAt = 0),
            login("empty", ""),                                       // không có mật khẩu: bỏ qua
        )
        val r = PasswordHealth.analyze(entries, now)
        assertEquals(5, r.loginCount)
        assertEquals(listOf("b"), r.weak.map { it.first.id })
        assertEquals(listOf(setOf("c", "d")), r.reused.map { g -> g.map { it.id }.toSet() })
        assertEquals(listOf("e"), r.old.map { it.id })
        assertEquals(listOf("b", "c", "d", "e"), r.missingTotp.map { it.id })
        assertEquals(listOf("e"), r.missingUrl.map { it.id })
        assertEquals(setOf("b", "c", "d", "e"), r.flaggedIds)
        assertEquals(20, r.score) // 1/5 mục không có vấn đề
        assertEquals(1, PasswordHealth.reuseCount(entries[2], entries))
        assertEquals(0, PasswordHealth.reuseCount(entries[0], entries))
        assertEquals(100, PasswordHealth.analyze(emptyList(), now).score)
    }

    // ------------------------------------------------------------------ lịch sử mật khẩu (spec 3.2)

    @Test
    fun history_keepsOldPasswordsNewestFirst_andRestoreRemovesDuplicate() {
        var e = login("a", "first", since = 1000)
        assertSame(e, e.withPassword("first", 2000)) // không đổi gì
        e = e.withPassword("second", 2000).withPassword("third", 3000)
        assertEquals("third", e.password)
        assertEquals(3000, e.passwordUpdatedAt)
        assertEquals(listOf("second" to 3000L, "first" to 2000L), e.passwordHistory.map { it.password to it.changedAt })

        // Khôi phục "first": bản cũ đó rời lịch sử, "third" vào lịch sử
        e = e.withPassword("first", 4000)
        assertEquals("first", e.password)
        assertEquals(listOf("third", "second"), e.passwordHistory.map { it.password })

        // Dùng lại bản cũ với setAt = lúc nó bị thay: tuổi mật khẩu không bị coi là "mới đặt"
        val restored = e.withPassword("third", 5000, setAt = 3000)
        assertEquals(3000, restored.passwordUpdatedAt)

        // Mật khẩu rỗng không được ghi vào lịch sử; tối đa MAX_HISTORY bản
        var f = login("f", "", since = 0)
        repeat(30) { f = f.withPassword("pw$it", it.toLong() + 1) }
        assertEquals(Entry.MAX_HISTORY, f.passwordHistory.size)
        assertFalse(f.passwordHistory.any { it.password.isEmpty() })
        assertEquals("pw28", f.passwordHistory.first().password)
    }

    @Test
    fun entryJson_isBackwardCompatible_andToStringRedacts() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        // JSON của một mục 0.1.x (chưa có favorite / passwordHistory / passwordUpdatedAt)
        val old = """{"id":"x","type":"LOGIN","title":"GitHub","password":"s3cret","createdAt":5,"updatedAt":6}"""
        val e = json.decodeFromString(Entry.serializer(), old)
        assertFalse(e.favorite)
        assertEquals(6, e.passwordSince()) // mục 0.1.x: lấy updatedAt, không báo nhầm "lâu chưa đổi"
        assertTrue(e.passwordHistory.isEmpty())
        val withHistory = e.withPassword("n3w", 9).copy(favorite = true)
        assertEquals(withHistory, json.decodeFromString(Entry.serializer(), json.encodeToString(Entry.serializer(), withHistory)))
        assertFalse(withHistory.toString().contains("s3cret") || withHistory.toString().contains("n3w"))
        assertFalse(withHistory.passwordHistory.toString().contains("s3cret"))
    }
}
