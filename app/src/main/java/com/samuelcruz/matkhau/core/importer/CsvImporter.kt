package com.samuelcruz.matkhau.core.importer

import com.samuelcruz.matkhau.core.format.FormatException
import com.samuelcruz.matkhau.core.totp.Totp
import com.samuelcruz.matkhau.model.Categories
import com.samuelcruz.matkhau.model.CustomField
import com.samuelcruz.matkhau.model.Entry
import com.samuelcruz.matkhau.model.EntryType

/** Đọc CSV theo RFC 4180: dấu phẩy, ô trong "nháy kép" (có thể chứa dấu phẩy / xuống dòng, "" = một dấu "). */
object Csv {
    /** Giới hạn để file lạ không làm treo app. */
    const val MAX_ROWS = 20_000
    const val MAX_FIELD_LENGTH = 100_000
    const val MAX_COLUMNS = 200

    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = if (text.startsWith('﻿')) 1 else 0   // bỏ BOM của Excel / Windows
        var rowHasContent = false

        fun endField() {
            if (field.length > MAX_FIELD_LENGTH) throw FormatException("Có ô quá dài trong file CSV")
            if (row.size >= MAX_COLUMNS) throw FormatException("Dòng có quá nhiều cột (tối đa $MAX_COLUMNS)")
            row.add(field.toString())
            field.setLength(0)
        }
        fun endRow() {
            endField()
            if (rowHasContent || row.size > 1 || row[0].isNotEmpty()) rows.add(row)
            if (rows.size > MAX_ROWS) throw FormatException("File CSV có quá nhiều dòng (tối đa $MAX_ROWS)")
            row = ArrayList()
            rowHasContent = false
        }

        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"'); i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> { inQuotes = true; rowHasContent = true }
                    ',' -> { endField(); rowHasContent = true }
                    '\r' -> { if (i + 1 < text.length && text[i + 1] == '\n') i++; endRow() }
                    '\n' -> endRow()
                    else -> field.append(c)
                }
            }
            i++
        }
        if (inQuotes) throw FormatException("File CSV bị cắt cụt (thiếu dấu \" đóng)")
        if (field.isNotEmpty() || row.isNotEmpty() || rowHasContent) endRow()
        return rows
    }
}

enum class CsvSource(val label: String) {
    BITWARDEN("Bitwarden"),
    CHROME("Chrome / Google Password Manager"),
    FIREFOX("Firefox"),
    KEEPASS("KeePass / KeePassXC"),
    GENERIC("CSV (đoán theo tên cột)"),
}

/** Kết quả đọc file: các mục sẽ nhập (đã bỏ mục trùng) + thống kê để hiện cho người dùng xem trước. */
class CsvImportResult(
    val source: CsvSource,
    val entries: List<Entry>,
    /** Trùng hoàn toàn với mục đã có trong vault (hoặc trùng nhau trong file): bỏ qua. */
    val duplicates: Int,
    /** Dòng không có gì để nhập (không tên, không username, không mật khẩu, không ghi chú). */
    val skippedRows: Int,
) {
    override fun toString(): String = "CsvImportResult(source=$source, entries=${entries.size})"
}

/**
 * Chuyển file CSV xuất từ trình quản lý mật khẩu khác thành các [Entry]. Kotlin thuần, không phụ thuộc Android.
 * Không làm mất dữ liệu: cột không có chỗ riêng (thư mục, trường tùy chỉnh, TOTP không đọc được) được đưa vào
 * tag / trường tùy chỉnh / ghi chú.
 */
object CsvImporter {

    /**
     * Giải mã byte của file: UTF-8 (có hoặc không BOM), UTF-16 nếu có BOM. Byte không hợp lệ => báo lỗi thay vì
     * lặng lẽ thay bằng "�" (mật khẩu "Café1" lưu bằng Excel kiểu Windows-1252 sẽ bị nhập sai mà không ai biết).
     */
    fun decode(bytes: ByteArray): String {
        val (charset, offset) = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }
        return try {
            charset.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                .toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw FormatException("File không phải UTF-8. Hãy xuất lại từ app cũ (đừng mở và lưu lại bằng Excel)")
        }
    }

    fun import(
        text: String,
        existing: List<Entry>,
        now: Long,
        newId: () -> String,
    ): CsvImportResult {
        val rows = Csv.parse(text)
        if (rows.isEmpty()) throw FormatException("File CSV trống")
        val header = rows.first().map { it.trim().lowercase() }
        val source = detect(header)
        val col = Columns(header, source)
        if (col.password < 0 && col.notes < 0) {
            throw FormatException("Không nhận ra file: cần ít nhất cột mật khẩu (password) hoặc ghi chú (notes)")
        }

        val seen = existing.map { fingerprint(it) }.toMutableSet()
        val out = ArrayList<Entry>()
        var duplicates = 0
        var skipped = 0
        for (cells in rows.drop(1)) {
            val entry = toEntry(cells, col, source, now, newId)
            if (entry == null) {
                skipped++
                continue
            }
            if (!seen.add(fingerprint(entry))) {
                duplicates++
                continue
            }
            out.add(entry)
        }
        return CsvImportResult(source, out, duplicates, skipped)
    }

    // ------------------------------------------------------------------------------------------

    private fun detect(h: List<String>): CsvSource = when {
        "login_password" in h && "login_username" in h -> CsvSource.BITWARDEN
        "httprealm" in h || "formactionorigin" in h -> CsvSource.FIREFOX
        ("title" in h && "group" in h) || "login name" in h || "web site" in h -> CsvSource.KEEPASS
        h.take(4) == listOf("name", "url", "username", "password") -> CsvSource.CHROME
        else -> CsvSource.GENERIC
    }

    /** Vị trí từng cột (-1 = không có), theo tên cột của từng nguồn + các tên thường gặp. */
    private class Columns(val h: List<String>, source: CsvSource) {
        private fun find(vararg names: String) = names.firstNotNullOfOrNull { n -> h.indexOf(n).takeIf { it >= 0 } } ?: -1

        val title = find("name", "title", "account", "item name", "entry")
        val username = find("login_username", "username", "login name", "login", "user", "email", "user name")
        val password = find("login_password", "password", "pass")
        val url = find("login_uri", "url", "web site", "website", "uri", "hostname")
        val notes = find("notes", "note", "comments", "comment", "extra")
        val totp = find("login_totp", "totp", "otp", "otpauth", "otpsecret", "otp secret", "2fa")
        val folder = find("folder", "group", "grouping", "category")
        val tags = find("tags", "collections", "labels")
        val favorite = find("favorite", "favourite", "fav")
        val type = find("type")
        val fields = if (source == CsvSource.BITWARDEN) find("fields") else -1
        val created = find("created", "timecreated")
        val changed = find("timepasswordchanged", "last modified", "modified")

        /** Cột không có chỗ riêng, vô nghĩa khi giữ lại (biểu tượng, id nội bộ, thời gian dùng gần nhất...). */
        private val noise = setOf("reprompt", "icon", "guid", "timelastused", "httprealm", "formactionorigin", "uuid", "id")

        /** Cột còn lại: giá trị khác rỗng được giữ thành trường tùy chỉnh (không mất dữ liệu). */
        val extra: List<Int> = h.indices.filter { i ->
            i !in setOf(title, username, password, url, notes, totp, folder, tags, favorite, type, fields, created, changed) &&
                h[i].isNotBlank() && h[i] !in noise
        }
    }

    private fun toEntry(cells: List<String>, c: Columns, source: CsvSource, now: Long, newId: () -> String): Entry? {
        fun cell(i: Int) = if (i in cells.indices) cells[i] else ""

        val folder = cell(c.folder).trim()
        // Mục đã xóa (Thùng rác của KeePass) không được "sống lại"
        if (folder.split('/', '\\').any { it.trim().equals("Recycle Bin", ignoreCase = true) || it.trim().equals("Thùng rác", ignoreCase = true) }) {
            return null
        }

        val username = cell(c.username).trim()
        val password = cell(c.password)
        val rawUrl = cell(c.url).trim()
        // Chỉ Bitwarden ghi nhiều URI cách nhau bằng dấu phẩy; nguồn khác: URL có thể chứa dấu phẩy thật (?ids=1,2).
        val urls = if (source == CsvSource.BITWARDEN) rawUrl.split(',').map { it.trim() }.filter { it.isNotEmpty() } else listOfNotNull(rawUrl.ifEmpty { null })
        var notes = cell(c.notes)
        val typeCell = cell(c.type).trim().lowercase()
        // Bitwarden: type=note; Proton Pass: type=note; LastPass: ghi chú bảo mật có URL "http://sn".
        val isNote = typeCell == "note" || typeCell == "securenote" || urls.firstOrNull() == "http://sn"
        val url = if (urls.firstOrNull() == "http://sn") "" else urls.firstOrNull().orEmpty()

        val extraNotes = mutableListOf<String>()
        if (urls.size > 1) extraNotes += "URL khác: " + urls.drop(1).joinToString(", ")
        val totpRaw = cell(c.totp).trim()
        val totp = when {
            totpRaw.isEmpty() -> ""
            Totp.isValid(totpRaw) -> totpRaw
            else -> { extraNotes += "TOTP (không đọc được, giữ nguyên): $totpRaw"; "" }
        }
        if (extraNotes.isNotEmpty()) notes = (listOf(notes) + extraNotes).filter { it.isNotBlank() }.joinToString("\n")

        val tags = (folder.split('/', '\\') + cell(c.tags).split(',', ';'))
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("root", ignoreCase = true) && !it.equals("Database", ignoreCase = true) }
            .map { it.replace(' ', '-') }
            .distinct()

        val customFields = (if (c.fields >= 0) parseBitwardenFields(cell(c.fields)) else emptyList()) +
            c.extra.mapNotNull { i ->
                cell(i).takeIf { it.isNotBlank() }?.let { CustomField(name = c.h[i], value = it, hidden = true) }
            }

        val title = cell(c.title).trim().ifEmpty { hostOf(url) }.ifEmpty { username }
        // Dòng trống: kiểm tra SAU khi gom TOTP / trường tùy chỉnh, để dòng chỉ có những thứ đó không bị bỏ.
        if (title.isEmpty() && password.isEmpty() && notes.isBlank() && totp.isEmpty() && customFields.isEmpty()) return null

        val created = parseTime(cell(c.created)) ?: now
        val changed = parseTime(cell(c.changed))

        return Entry(
            id = newId(),
            type = if (isNote) EntryType.NOTE else EntryType.LOGIN,
            title = title.ifEmpty { "(không tên)" },
            username = if (isNote) "" else username,
            password = if (isNote) "" else password,
            url = if (isNote) "" else url,
            totp = if (isNote) "" else totp,
            // Ghi chú bảo mật mà file vẫn có username / mật khẩu: giữ trong nội dung, không bỏ.
            notes = if (isNote) {
                (listOf(notes) + listOfNotNull(
                    username.takeIf { it.isNotEmpty() }?.let { "Username: $it" },
                    password.takeIf { it.isNotEmpty() }?.let { "Mật khẩu: $it" },
                )).filter { it.isNotBlank() }.joinToString("\n")
            } else notes,
            category = Categories.PERSONAL,
            tags = tags,
            customFields = customFields,
            favorite = cell(c.favorite).trim().let { it == "1" || it.equals("true", ignoreCase = true) },
            createdAt = created,
            updatedAt = now,
            passwordUpdatedAt = if (password.isNotEmpty() && !isNote) (changed ?: created) else 0L,
        )
    }

    /** Bitwarden: mỗi dòng "tên: giá trị". */
    private fun parseBitwardenFields(text: String): List<CustomField> =
        text.lines().filter { it.isNotBlank() }.map { line ->
            val name = line.substringBefore(':').trim()
            val value = if (':' in line) line.substringAfter(':').trim() else ""
            CustomField(name = name, value = value, hidden = true)
        }

    /** Firefox ghi mili-giây epoch; KeePassXC ghi ISO 8601. Không đọc được => null. */
    private fun parseTime(s: String): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        t.toLongOrNull()?.let { return if (it in 1_000_000_000_000L..4_102_444_800_000L) it else null }
        return try {
            java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.LocalDateTime.parse(t).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringAfter('@').removePrefix("www.")

    /** Hai mục coi là trùng khi tên, username, mật khẩu và URL giống nhau (không phân biệt hoa thường ở tên / URL). */
    private fun fingerprint(e: Entry): String =
        (listOf(e.type.name, e.title.trim().lowercase(), e.username.trim(), e.password, e.url.trim().lowercase(), e.notes.trim(), e.totp.trim()) +
            e.customFields.map { "${it.name}=${it.value}" }).joinToString("\u0000")
}
