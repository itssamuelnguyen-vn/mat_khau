package com.samuelcruz.matkhau.core

import com.samuelcruz.matkhau.core.format.FormatException
import com.samuelcruz.matkhau.core.importer.Csv
import com.samuelcruz.matkhau.core.importer.CsvImporter
import com.samuelcruz.matkhau.core.importer.CsvSource
import com.samuelcruz.matkhau.model.Entry
import com.samuelcruz.matkhau.model.EntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CsvImportTest {
    private val now = 1_800_000_000_000L
    private var counter = 0
    private fun import(csv: String, existing: List<Entry> = emptyList()) =
        CsvImporter.import(csv, existing, now) { "id-${counter++}" }

    @Test
    fun csvParser_handlesQuotesNewlinesBomAndCrlf() {
        val text = "﻿a,b,c\r\n\"x, y\",\"he said \"\"hi\"\"\",\"line1\nline2\"\r\n,,\n\nlast,,\n"
        val rows = Csv.parse(text)
        assertEquals(listOf("a", "b", "c"), rows[0])
        assertEquals(listOf("x, y", "he said \"hi\"", "line1\nline2"), rows[1])
        assertEquals(listOf("", "", ""), rows[2])        // dòng chỉ có dấu phẩy vẫn là một dòng
        assertEquals(listOf("last", "", ""), rows[3])    // dòng trống hoàn toàn bị bỏ
        assertEquals(4, rows.size)
        assertEquals(listOf(listOf("no newline at end")), Csv.parse("no newline at end"))
        try { Csv.parse("a,\"unterminated"); fail() } catch (e: FormatException) {}
    }

    @Test
    fun bitwarden_loginsNotesFoldersFieldsFavoritesTotp() {
        val csv = """
            folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp
            Work/Dev,1,login,GitHub,"my notes","PIN: 1234
            Recovery: abc",0,"https://github.com,https://gist.github.com",me@example.com,s3cret!,JBSWY3DPEHPK3PXP
            ,,note,Wi-Fi nhà,"SSID: Nha
            Pass: 12345678",,0,,,,
            ,,login,Bank,,,0,https://bank.example,user,pw,not-a-valid-totp!
        """.trimIndent()
        val r = import(csv)
        assertEquals(CsvSource.BITWARDEN, r.source)
        assertEquals(3, r.entries.size)

        val gh = r.entries[0]
        assertEquals("GitHub", gh.title)
        assertEquals("me@example.com", gh.username)
        assertEquals("s3cret!", gh.password)
        assertEquals("https://github.com", gh.url)
        assertEquals("JBSWY3DPEHPK3PXP", gh.totp)
        assertTrue(gh.favorite)
        assertEquals(listOf("Work", "Dev"), gh.tags)
        assertEquals(listOf("PIN" to "1234", "Recovery" to "abc"), gh.customFields.map { it.name to it.value })
        assertTrue(gh.notes.startsWith("my notes") && gh.notes.contains("https://gist.github.com"))
        assertEquals(now, gh.passwordUpdatedAt)

        val note = r.entries[1]
        assertEquals(EntryType.NOTE, note.type)
        assertEquals("Wi-Fi nhà", note.title)
        assertTrue(note.notes.contains("Pass: 12345678"))
        assertEquals("", note.password)

        // TOTP không đọc được: không bỏ vào ô TOTP (sẽ báo lỗi) mà giữ trong ghi chú
        val bank = r.entries[2]
        assertEquals("", bank.totp)
        assertTrue(bank.notes.contains("not-a-valid-totp!"))
    }

    @Test
    fun chrome_firefox_keepass_andGeneric() {
        val chrome = import("name,url,username,password,note\ngithub.com,https://github.com/login,me,pw1,hello\n")
        assertEquals(CsvSource.CHROME, chrome.source)
        assertEquals(listOf("github.com", "me", "pw1", "hello"), chrome.entries.single().let { listOf(it.title, it.username, it.password, it.notes) })

        val firefox = import(
            "\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\",\"timeCreated\",\"timeLastUsed\",\"timePasswordChanged\"\n" +
                "\"https://www.example.com\",\"bob\",\"pw2\",,\"https://www.example.com\",\"{x}\",\"1700000000000\",\"1700000000000\",\"1710000000000\"\n",
        )
        assertEquals(CsvSource.FIREFOX, firefox.source)
        val ff = firefox.entries.single()
        assertEquals("example.com", ff.title)  // Firefox không có tên: lấy tên miền
        assertEquals(1_700_000_000_000L, ff.createdAt)
        assertEquals(1_710_000_000_000L, ff.passwordUpdatedAt)

        val keepass = import(
            "\"Group\",\"Title\",\"Username\",\"Password\",\"URL\",\"Notes\",\"TOTP\",\"Icon\",\"Last Modified\",\"Created\"\n" +
                "\"Root/Email\",\"Gmail\",\"me@gmail.com\",\"pw3\",\"https://mail.google.com\",\"\",\"otpauth://totp/G:me?secret=JBSWY3DPEHPK3PXP\",\"0\",\"2024-05-01T10:00:00Z\",\"2020-01-01T00:00:00Z\"\n",
        )
        assertEquals(CsvSource.KEEPASS, keepass.source)
        val kp = keepass.entries.single()
        assertEquals(listOf("Email"), kp.tags)  // "Root" bị bỏ
        assertTrue(kp.totp.startsWith("otpauth://"))
        assertEquals(java.time.Instant.parse("2020-01-01T00:00:00Z").toEpochMilli(), kp.createdAt)

        val keepass2 = import("\"Account\",\"Login Name\",\"Password\",\"Web Site\",\"Comments\"\n\"Shop\",\"ann\",\"pw4\",\"https://shop.vn\",\"c\"\n")
        assertEquals(CsvSource.KEEPASS, keepass2.source)
        assertEquals(listOf("Shop", "ann", "pw4", "https://shop.vn", "c"), keepass2.entries.single().let { listOf(it.title, it.username, it.password, it.url, it.notes) })

        try { import("title,foo\nA,b\n"); fail("không có cột mật khẩu / ghi chú") } catch (e: FormatException) {}
        val g = import("Title,Email,Pass,Website\nZalo,0909,pw5,zalo.me\n")
        assertEquals(CsvSource.GENERIC, g.source)
        assertEquals(listOf("Zalo", "0909", "pw5", "zalo.me"), g.entries.single().let { listOf(it.title, it.username, it.password, it.url) })
    }

    @Test
    fun duplicatesAndEmptyRowsAreSkipped() {
        val csv = "name,url,username,password\nA,https://a.com,u,p\nA,https://a.com,u,p\n,,,\nB,https://b.com,u,p\n"
        val first = import(csv)
        assertEquals(2, first.entries.size)
        assertEquals(1, first.duplicates)   // trùng trong file
        assertEquals(1, first.skippedRows)
        // Nhập lại cùng file vào vault đã có 2 mục đó: không thêm gì
        val again = import(csv, existing = first.entries)
        assertEquals(0, again.entries.size)
        assertEquals(3, again.duplicates)
        assertEquals(first.entries.size, first.entries.map { it.id }.toSet().size)
        assertFalse(first.toString().contains("u,p"))
    }

    @Test
    fun hugeOrMalformedFilesFailCleanly() {
        try { import(""); fail() } catch (e: FormatException) {}
        val many = buildString { append("name,url,username,password\n"); repeat(Csv.MAX_ROWS + 5) { append("n$it,,u,p$it\n") } }
        try { import(many); fail() } catch (e: FormatException) {}
    }

    @Test
    fun noDataIsSilentlyLost() {
        // Dashlane: otpSecret là TOTP; username2 thành trường tùy chỉnh
        val dash = import("username,username2,username3,title,password,note,url,category,otpSecret\nme,alt,,Site,pw,,https://s.com,Work,JBSWY3DPEHPK3PXP\n").entries.single()
        assertEquals("JBSWY3DPEHPK3PXP", dash.totp)
        assertEquals(listOf("username2" to "alt"), dash.customFields.map { it.name to it.value })
        assertEquals(listOf("Work"), dash.tags)

        // Proton Pass: có cả email lẫn username => email thành trường tùy chỉnh, không mất
        val proton = import("type,name,url,email,username,password,note,totp\nlogin,Mail,https://m.com,a@b.c,,pw,,\n").entries.single()
        assertTrue(proton.customFields.any { it.name == "email" && it.value == "a@b.c" })

        // 1Password: Tags thành tag
        val op = import("Title,Url,Username,Password,OTPAuth,Favorite,Archived,Tags,Notes\nX,https://x.com,u,p,,true,false,\"a,b\",\n").entries.single()
        assertEquals(listOf("a", "b"), op.tags)
        assertTrue(op.favorite)

        // Chrome: URL có dấu phẩy thật không bị cắt
        val chrome = import("name,url,username,password\nx,https://x.com/?ids=1,2,u,p\n".replace("https://x.com/?ids=1,2", "\"https://x.com/?ids=1,2\"")).entries.single()
        assertEquals("https://x.com/?ids=1,2", chrome.url)

        // Dòng chỉ có TOTP vẫn được nhập
        val onlyTotp = import("name,url,username,password,totp\n,,,,JBSWY3DPEHPK3PXP\n")
        assertEquals(1, onlyTotp.entries.size)

        // Khác nhau chỉ ở TOTP => không phải trùng
        val twoTotp = import("name,url,username,password,totp\nA,,u,p,JBSWY3DPEHPK3PXP\nA,,u,p,GEZDGNBVGY3TQOJQ\n")
        assertEquals(2, twoTotp.entries.size)

        // KeePass: mục trong Thùng rác bị bỏ; LastPass: ghi chú bảo mật (url http://sn)
        val kp = import("\"Group\",\"Title\",\"Username\",\"Password\",\"URL\",\"Notes\"\n\"Root/Recycle Bin\",\"Old\",\"u\",\"p\",\"\",\"\"\n\"Root\",\"Live\",\"u\",\"p\",\"\",\"\"\n")
        assertEquals(listOf("Live"), kp.entries.map { it.title })
        val lp = import("url,username,password,totp,extra,name,grouping,fav\nhttp://sn,,,,secret text,My note,,0\n").entries.single()
        assertEquals(EntryType.NOTE, lp.type)
        assertEquals("", lp.url)
        assertTrue(lp.notes.contains("secret text"))
    }

    @Test
    fun decode_isStrictAboutEncoding() {
        assertEquals("Café1", CsvImporter.decode("Café1".toByteArray(Charsets.UTF_8)))
        assertEquals("name", CsvImporter.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "name".toByteArray(Charsets.UTF_16LE)))
        assertEquals("name", CsvImporter.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "name".toByteArray(Charsets.UTF_16BE)))
        // Windows-1252 "Café1": byte 0xE9 không hợp lệ trong UTF-8 => báo lỗi, không nhập sai
        try { CsvImporter.decode("Caf".toByteArray() + byteArrayOf(0xE9.toByte()) + "1".toByteArray()); fail() } catch (e: FormatException) {}
        // Quá nhiều cột trên một dòng
        try { Csv.parse(",".repeat(Csv.MAX_COLUMNS + 1)); fail() } catch (e: FormatException) {}
    }
}
