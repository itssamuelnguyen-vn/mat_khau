package com.samuelcruz.matkhau.core

import com.samuelcruz.matkhau.core.autofill.AutofillMatcher
import com.samuelcruz.matkhau.core.autofill.AutofillTarget
import com.samuelcruz.matkhau.core.autofill.DomainMatcher
import com.samuelcruz.matkhau.core.autofill.FormClassifier
import com.samuelcruz.matkhau.core.autofill.FormField
import com.samuelcruz.matkhau.core.autofill.TrustedBrowsers
import com.samuelcruz.matkhau.model.Entry
import com.samuelcruz.matkhau.model.EntryType
import com.samuelcruz.matkhau.model.LinkedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillCoreTest {

    // ------------------------------------------------------------------ chống phishing (spec 4.2)

    @Test
    fun domain_matchesSameSiteOnly() {
        fun m(saved: String, host: String, scheme: String? = "https") = DomainMatcher.matches(saved, host, scheme)
        assertTrue(m("https://accounts.google.com", "accounts.google.com"))
        assertTrue(m("https://accounts.google.com", "mail.google.com"))      // cùng gốc google.com
        assertTrue(m("github.com", "github.com"))                            // URL không có scheme
        assertTrue(m("https://www.vietcombank.com.vn/login", "vcbdigibank.vietcombank.com.vn"))
        assertTrue(m("https://GitHub.com:443/x?y", "GITHUB.COM."))

        // Ví dụ của spec và các kiểu giả mạo thường gặp
        assertFalse(m("https://accounts.google.com", "accounts-go0gle.example"))
        assertFalse(m("https://google.com", "google.com.evil.io"))
        assertFalse(m("https://google.com", "notgoogle.com"))
        assertFalse(m("https://paypal.com", "pаypal.com"))                    // chữ "а" Cyrillic
        assertFalse(m("https://tiki.vn", "tiki.vn.phishing.com"))
        assertFalse(m("https://shop.com.vn", "other.com.vn"))                // .com.vn: gốc là shop.com.vn
        assertFalse(m("https://alice.github.io", "mallory.github.io"))       // hosting: mỗi tên miền con một chủ
        assertFalse(m("https://bank.com", "bank.com", scheme = "http"))      // https đã lưu không điền vào http
        assertTrue(m("http://router.local", "router.local", scheme = "http"))
        assertTrue(m("http://192.168.1.1", "192.168.1.1", scheme = "http"))
        assertFalse(m("http://192.168.1.1", "192.168.1.2", scheme = "http"))
        assertFalse(m("", "github.com"))
        assertFalse(m("androidapp://com.github.android", "github.com"))
        assertEquals("tiki.vn", DomainMatcher.baseDomain("shop.tiki.vn"))
        assertEquals("tiki.com.vn", DomainMatcher.baseDomain("a.b.tiki.com.vn"))
        assertTrue(DomainMatcher.hostOf("https://pаypal.com/")!!.startsWith("xn--"))   // punycode, không bao giờ trùng paypal.com
    }

    // ------------------------------------------------------------------ nhận diện form

    @Test
    fun form_findsUsernameAndPassword() {
        // App native: hint autofill rõ ràng
        val native = FormClassifier.classify(listOf(
            FormField(0, texts = listOf("search_box", "Tìm kiếm")),
            FormField(1, hints = listOf("username")),
            FormField(2, hints = listOf("password")),
        ))
        assertEquals(1, native.username); assertEquals(listOf(2), native.passwords)

        // Web: thẻ input type=email / password
        val web = FormClassifier.classify(listOf(
            FormField(0, htmlType = "text", htmlAttrs = listOf("q")),
            FormField(1, htmlType = "email", htmlAttrs = listOf("login_email")),
            FormField(2, htmlType = "password", htmlAttrs = listOf("pass")),
            FormField(3, htmlType = "submit"),
        ))
        assertEquals(1, web.username); assertEquals(listOf(2), web.passwords)

        // App không có hint: inputType mật khẩu + ô chữ ngay trước nó; ô OTP bị bỏ qua
        val plain = FormClassifier.classify(listOf(
            FormField(0, inputType = 0x00000001, texts = listOf("et_phone_number_vn")),
            FormField(1, inputType = 0x00000081),     // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD
            FormField(2, inputType = 0x00000002, texts = listOf("Mã OTP")),
        ))
        assertEquals(0, plain.username); assertEquals(listOf(1), plain.passwords)

        // Tiếng Việt, ô "nhập lại mật khẩu" khi đăng ký
        val register = FormClassifier.classify(listOf(
            FormField(0, texts = listOf("Tên đăng nhập")),
            FormField(1, texts = listOf("Mật khẩu")),
            FormField(2, texts = listOf("Nhập lại mật khẩu")),
        ))
        assertEquals(0, register.username); assertEquals(listOf(1, 2), register.passwords)

        assertTrue(FormClassifier.classify(listOf(FormField(0, htmlType = "search"))).isEmpty)
        assertNull(FormClassifier.classify(listOf(FormField(0, hints = listOf("password")))).username)
    }

    // ------------------------------------------------------------------ chọn mục được điền

    private fun login(title: String, url: String = "", apps: List<LinkedApp> = emptyList(), fav: Boolean = false) =
        Entry(id = title, title = title, username = "u", password = "p", url = url, linkedApps = apps,
            favorite = fav, createdAt = 0, updatedAt = 0)

    @Test
    fun matcher_webAndAppRules() {
        val gh = login("GitHub", "https://github.com")
        val bank = login("VCB", "https://vietcombank.com.vn", apps = listOf(LinkedApp("com.VCB", "AA11")))
        val note = Entry(id = "n", type = EntryType.NOTE, title = "github.com", url = "https://github.com", createdAt = 0, updatedAt = 0)
        val all = listOf(gh, bank, note)

        assertEquals(listOf(gh), AutofillMatcher.matching(all, AutofillTarget.Web("github.com", "https", "com.android.chrome")))
        assertEquals(listOf(bank), AutofillMatcher.matching(all, AutofillTarget.App("com.VCB", "aa11")))
        assertTrue(AutofillMatcher.matching(all, AutofillTarget.App("com.VCB", "BB22")).isEmpty())   // chứng chỉ khác: app giả
        assertTrue(AutofillMatcher.matching(all, AutofillTarget.App("com.VCB", null)).isEmpty())
        assertTrue(AutofillMatcher.matching(all, AutofillTarget.App("com.github.android", "x")).isEmpty()) // chưa liên kết

        // Gợi ý khi chọn tay (không tự điền)
        assertTrue(AutofillMatcher.suggestedForApp(gh, "com.github.android"))
        assertTrue(AutofillMatcher.suggestedForApp(login("x", "androidapp://com.foo.bar"), "com.foo.bar"))
        assertFalse(AutofillMatcher.suggestedForApp(gh, "com.google.android.youtube"))

        // Yêu thích lên đầu
        val a = login("A", "https://a.example.com"); val b = login("B", "https://b.example.com", fav = true)
        assertEquals(listOf(b, a), AutofillMatcher.matching(listOf(a, b), AutofillTarget.Web("example.com", "https", "org.mozilla.firefox")))

        assertTrue(TrustedBrowsers.isTrusted("com.android.chrome"))
        assertFalse(TrustedBrowsers.isTrusted("com.evil.browser"))
    }
}
