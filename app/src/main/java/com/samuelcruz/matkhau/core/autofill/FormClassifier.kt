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
)

/**
 * Kết quả: ô username (nếu có), ô mật khẩu HIỆN TẠI (để điền) và ô mật khẩu MỚI (form đổi mật khẩu / đăng ký:
 * không điền mật khẩu cũ vào, nhưng khi lưu thì lấy giá trị ở đây).
 */
data class LoginForm(val username: Int?, val passwords: List<Int>, val newPasswords: List<Int> = emptyList()) {
    val isEmpty: Boolean get() = username == null && passwords.isEmpty() && newPasswords.isEmpty()
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

    fun classify(fields: List<FormField>): LoginForm {
        val candidates = fields.filter { it.isTextField && !isIgnored(it) }
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
