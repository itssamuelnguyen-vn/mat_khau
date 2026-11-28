package com.samuelcruz.matkhau.core.autofill

/**
 * Một ô nhập trên màn hình đăng nhập, rút gọn từ ViewNode của Android để phân loại được bằng Kotlin thuần
 * (test được trên JVM). [index] là vị trí theo thứ tự duyệt cây giao diện.
 */
data class FormField(
    val index: Int,
    /** android:autofillHints / HTML autocomplete, viết thường. */
    val hints: List<String> = emptyList(),
    /** HTML: type, name, id, autocomplete, placeholder… của thẻ input (viết thường). */
    val htmlType: String? = null,
    val htmlAttrs: List<String> = emptyList(),
    /** android:inputType (0 = không rõ). */
    val inputType: Int = 0,
    /** Tên resource id ("et_password"), gợi ý (hint) và nhãn — để đoán bằng chữ. */
    val texts: List<String> = emptyList(),
    val isTextField: Boolean = true,
    /** Số ký tự tối đa (HTML maxlength / android:maxLength), null = không rõ. */
    val maxLength: Int? = null,
)

/**
 * Kết quả: ô username (nếu có), ô mật khẩu HIỆN TẠI (để điền) và ô mật khẩu MỚI (form đổi mật khẩu / đăng ký:
 * không điền mật khẩu cũ vào, nhưng khi lưu thì lấy giá trị ở đây).
 */
data class LoginForm(val username: Int?, val passwords: List<Int>, val newPasswords: List<Int> = emptyList()) {
    val isEmpty: Boolean get() = username == null && passwords.isEmpty() && newPasswords.isEmpty()
}

/**
 * Form thanh toán thẻ: chỉ có nghĩa khi tìm thấy ô số thẻ ([number]). Hết hạn có thể là một ô ([expiry], "MM/YY")
 * hoặc hai ô tháng / năm.
 */
data class CardForm(
    val number: Int?,
    val holder: Int? = null,
    val expiry: Int? = null,
    val expMonth: Int? = null,
    val expYear: Int? = null,
    val cvc: Int? = null,
    /** Cách viết hết hạn cho ô gộp: "MM/YY" (mặc định), "MM/YYYY" hoặc "MMYY". */
    val expiryPattern: String = "MM/YY",
    /** Ô năm riêng cần 4 chữ số. */
    val fourDigitYear: Boolean = false,
) {
    val isEmpty: Boolean get() = number == null
    val indices: List<Int> get() = listOfNotNull(number, holder, expiry, expMonth, expYear, cvc)
}

object FormClassifier {
    // android.text.InputType (chép hằng số để không phụ thuộc Android)
    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0
    private const val TYPE_CLASS_TEXT = 0x00000001
    private const val TYPE_CLASS_NUMBER = 0x00000002
    private const val TEXT_PASSWORD = 0x00000080
    private const val TEXT_VISIBLE_PASSWORD = 0x00000090
    private const val TEXT_WEB_PASSWORD = 0x000000e0
    private const val TEXT_EMAIL = 0x00000020
    private const val TEXT_WEB_EMAIL = 0x000000d0
    private const val NUMBER_PASSWORD = 0x00000010

    /** Ô nhập mật khẩu MỚI / nhập lại (form đổi mật khẩu, đăng ký). */
    private val NEW_PASSWORD_WORDS = listOf(
        "new-password", "new password", "newpassword", "new_password", "confirm", "repeat", "retype", "re-enter",
        "mật khẩu mới", "mat khau moi", "nhập lại", "nhap lai", "xác nhận", "xac nhan",
    )
    private val PASSWORD_WORDS = listOf("password", "passwd", "passcode", "pwd", "mật khẩu", "mat khau", "matkhau", "mật mã")
    private val USERNAME_WORDS = listOf(
        "username", "user name", "userid", "user_id", "login", "email", "e-mail", "account", "phone", "mobile",
        "tài khoản", "tai khoan", "tên đăng nhập", "ten dang nhap", "số điện thoại", "so dien thoai", "sđt", "user",
    )
    /** Ô không phải đăng nhập: tìm kiếm, mã OTP / captcha, ... */
    private val IGNORE_WORDS = listOf("search", "tìm kiếm", "otp", "captcha", "verification code", "mã xác", "one-time", "promo", "coupon")

    fun classify(fields: List<FormField>, exclude: Collection<Int> = emptyList()): LoginForm {
        val candidates = fields.filter { it.isTextField && !isIgnored(it) && it.index !in exclude }
        val allPasswords = candidates.filter { isPassword(it) }
        val newOnes = allPasswords.filter { isNewPassword(it) }.map { it.index }
        var current = allPasswords.map { it.index }.filterNot { it in newOnes }
        var new = newOnes
        // Form đăng ký không có gợi ý: "Mật khẩu" + "Nhập lại" => ô đầu là mật khẩu hiện tại để điền / lưu.
        if (current.isEmpty() && new.size > 1) { current = new.take(1); new = new.drop(1) }
        val passwordIdx = current + new
        var username = candidates.firstOrNull { it.index !in passwordIdx && isUsername(it) }?.index
        if (username == null && passwordIdx.isNotEmpty()) {
            // Không ô nào tự nhận là username: lấy ô chữ ngay TRƯỚC ô mật khẩu đầu tiên (form đăng nhập thường gặp).
            username = candidates.lastOrNull { it.index < passwordIdx.min() && it.index !in passwordIdx }?.index
        }
        return LoginForm(username, current.take(2), new.take(2))
    }

    // ------------------------------------------------------------------ thẻ thanh toán

    private val CARD_NUMBER_WORDS = listOf(
        "card number", "cardnumber", "card_number", "card-number", "cc-number", "ccnumber", "cc_number", "cc number",
        "creditcard", "credit card", "số thẻ", "sothe", "card no", "cardno", "pan",
    )
    private val CARD_HOLDER_WORDS = listOf(
        "cardholder", "card holder", "card_holder", "name on card", "nameoncard", "cc-name", "ccname", "chủ thẻ", "chu the", "tên in trên thẻ",
    )
    private val CARD_EXPIRY_WORDS = listOf(
        "expiry", "expiration", "exp date", "expdate", "exp-date", "exp_date", "mm/yy", "mm / yy", "mmyy", "valid thru",
        "hết hạn", "het han", "cc-exp",
    )
    private val CARD_MONTH_WORDS = listOf("exp-month", "expmonth", "exp_month", "expiry month", "expiration month", "cc-exp-month", "tháng", "month", "mm")
    private val CARD_YEAR_WORDS = listOf("exp-year", "expyear", "exp_year", "expiry year", "expiration year", "cc-exp-year", "năm", "year", "yy")
    /** Ô số của thẻ KHÔNG phải thẻ thanh toán (CCCD, thẻ thư viện, bảo hiểm, thành viên...). */
    private val NOT_PAYMENT_WORDS = listOf(
        "căn cước", "can cuoc", "cccd", "cmnd", "chứng minh", "thư viện", "thu vien", "library", "bảo hiểm", "bao hiem",
        "insurance", "thành viên", "thanh vien", "member", "sinh viên", "sinh vien", "student", "id card", "identity", "loyalty",
    )
    private val COMBINED_EXPIRY = listOf("mm/yy", "mm / yy", "mmyy", "mm-yy", "mm/aa", "tháng/năm")
    private val CARD_CVC_WORDS = listOf("cvv", "cvc", "cvn", "csc", "cid", "security code", "securitycode", "card code", "mã bảo mật", "ma bao mat", "cc-csc")

    /**
     * Tìm các ô thẻ. Ưu tiên gợi ý chuẩn (autofillHints của Android, autocomplete="cc-*" của HTML), rồi mới đoán theo
     * tên / nhãn. Tháng / năm chỉ được đoán theo chữ khi form đã có ô số thẻ (chữ "tháng", "năm" quá chung chung).
     */
    fun classifyCard(fields: List<FormField>): CardForm {
        val candidates = fields.filter { it.isTextField && it.htmlType !in setOf("hidden", "submit", "button", "checkbox", "radio", "search") }
        val taken = HashSet<Int>()
        fun hinted(vararg hints: String) = candidates.firstOrNull { f -> f.index !in taken && f.hints.any { h -> hints.any { h == it } } }?.index
        fun texts(f: FormField) = (f.htmlAttrs + f.texts).map { it.lowercase() }
        fun has(f: FormField, words: List<String>) = texts(f).any { t -> words.any { w -> matchesWord(t, w) } }
        // "dd/mm/yyyy" là ô ngày (sinh, cấp...), không phải hạn thẻ.
        fun combined(f: FormField) = texts(f).any { t -> !t.contains("dd") && COMBINED_EXPIRY.any { t.contains(it) } }
        fun dateField(f: FormField) = texts(f).any { it.contains("dd") }
        fun worded(predicate: (FormField) -> Boolean) = candidates.firstOrNull { f -> f.index !in taken && predicate(f) }?.index
        fun pick(i: Int?) = i?.also { taken += it }

        val numberByHint = pick(hinted("creditcardnumber", "cc-number"))
        val number = numberByHint ?: pick(worded { has(it, CARD_NUMBER_WORDS) && !has(it, NOT_PAYMENT_WORDS) }) ?: return CardForm(number = null)
        val cvc = pick(hinted("creditcardsecuritycode", "cc-csc")) ?: pick(worded { has(it, CARD_CVC_WORDS) })
        // Ô tháng / năm riêng trước, rồi mới tới ô gộp: "Expiration month" không bị coi là ô "MM/YY".
        var month = pick(hinted("creditcardexpirationmonth", "cc-exp-month"))
        var year = pick(hinted("creditcardexpirationyear", "cc-exp-year"))
        val expiryByHint = pick(hinted("creditcardexpirationdate", "cc-exp"))
        var expiryByPattern = false
        val expiry = expiryByHint ?: if (month == null && year == null) {
            pick(worded { f ->
                !dateField(f) && (combined(f) || (has(f, CARD_EXPIRY_WORDS) && !has(f, CARD_MONTH_WORDS) && !has(f, CARD_YEAR_WORDS)))
            })?.also { i -> expiryByPattern = fields.first { it.index == i }.let(::combined) }
        } else null
        if (expiry == null) {
            if (month == null) month = pick(worded { !combined(it) && !dateField(it) && has(it, CARD_MONTH_WORDS) })
            if (year == null) year = pick(worded { !combined(it) && !dateField(it) && has(it, CARD_YEAR_WORDS) })
        }
        val holder = pick(hinted("cc-name", "creditcardholdername")) ?: pick(worded { has(it, CARD_HOLDER_WORDS) })

        // Chỉ đoán theo chữ (không có gợi ý chuẩn): phải có thêm ô CVV hoặc hết hạn mới là form thẻ, để "Số thẻ thư
        // viện", "ID card number" của form đăng nhập không bị cướp mất.
        // Tháng / chữ "hết hạn" đoán theo chữ chưa đủ: cần CVV, hoặc ô hết hạn có gợi ý chuẩn / mẫu "MM/YY".
        if (numberByHint == null && cvc == null && expiryByHint == null && !expiryByPattern) return CardForm(number = null)

        val expiryField = expiry?.let { i -> fields.first { it.index == i } }
        // Theo chữ hiện trên ô trước ("MM / YY" dù maxlength 7), độ dài tối đa chỉ dùng khi ô không có chữ gợi ý.
        val expiryText = expiryField?.let { words(it) }.orEmpty()
        val pattern = when {
            expiryField == null -> "MM/YY"
            expiryText.any { it.contains("yyyy") } -> "MM/YYYY"
            expiryText.any { it.contains("yy") || it.contains("/aa") } -> if (expiryField.maxLength == 4) "MMYY" else "MM/YY"
            expiryField.maxLength == 4 -> "MMYY"
            (expiryField.maxLength ?: 0) >= 7 -> "MM/YYYY"
            else -> "MM/YY"
        }
        val yearField = year?.let { i -> fields.first { it.index == i } }
        val fourDigitYear = yearField != null && (yearField.maxLength == 4 || words(yearField).any { it.contains("yyyy") })
        return CardForm(number, holder, expiry, month, year, cvc, pattern, fourDigitYear)
    }

    /** "pan", "mm", "yy", "cid" ngắn: chỉ khớp nguyên từ, không khớp một phần ("company", "summary"). */
    private fun matchesWord(text: String, word: String): Boolean {
        if (word.length > 3) return text.contains(word)
        return text.split(' ', '-', '_', '/', '.', ':', '[', ']', '(', ')').any { it == word }
    }

    private fun isNewPassword(f: FormField): Boolean =
        words(f).any { w -> NEW_PASSWORD_WORDS.any { w.contains(it) } }

    private fun words(f: FormField) = (f.hints + f.htmlAttrs + f.texts + listOfNotNull(f.htmlType)).map { it.lowercase() }

    private fun isIgnored(f: FormField): Boolean {
        if (f.hints.any { it.contains("otp") || it.contains("one-time-code") }) return true
        if (f.htmlType in setOf("search", "hidden", "submit", "button", "checkbox", "radio")) return true
        return words(f).any { w -> IGNORE_WORDS.any { w.contains(it) } }
    }

    private fun isPassword(f: FormField): Boolean {
        if (f.hints.any { it.contains("password") }) return true
        if (f.htmlType == "password") return true
        val cls = f.inputType and TYPE_MASK_CLASS
        val variation = f.inputType and TYPE_MASK_VARIATION
        if (cls == TYPE_CLASS_TEXT && variation in setOf(TEXT_PASSWORD, TEXT_VISIBLE_PASSWORD, TEXT_WEB_PASSWORD)) return true
        // Ô số dạng mật khẩu (NUMBER_PASSWORD) thường là mã PIN / OTP: chỉ coi là mật khẩu khi tên ô nói vậy.
        return (f.htmlAttrs + f.texts).any { t -> PASSWORD_WORDS.any { t.lowercase().contains(it) } }
    }

    private fun isUsername(f: FormField): Boolean {
        if (f.hints.any { it == "username" || it.contains("email") || it == "phone" || it.contains("tel") }) return true
        if (f.htmlType == "email" || f.htmlType == "tel") return true
        val variation = f.inputType and TYPE_MASK_VARIATION
        if ((f.inputType and TYPE_MASK_CLASS) == TYPE_CLASS_TEXT && (variation == TEXT_EMAIL || variation == TEXT_WEB_EMAIL)) return true
        return (f.htmlAttrs + f.texts).any { t -> USERNAME_WORDS.any { t.lowercase().contains(it) } }
    }
}
