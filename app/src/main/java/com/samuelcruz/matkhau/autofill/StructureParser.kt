package com.samuelcruz.matkhau.autofill

import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.autofill.AutofillId
import androidx.core.content.IntentCompat
import com.samuelcruz.matkhau.MatKhauApp
import com.samuelcruz.matkhau.core.autofill.AutofillTarget
import com.samuelcruz.matkhau.core.autofill.FormClassifier
import com.samuelcruz.matkhau.core.autofill.FormField
import com.samuelcruz.matkhau.core.autofill.CardForm
import com.samuelcruz.matkhau.core.autofill.DomainMatcher
import com.samuelcruz.matkhau.core.autofill.TrustedBrowsers

/** Các ô của form thanh toán thẻ (xem [CardForm]). */
class CardFields(
    val number: AutofillId,
    val holder: AutofillId? = null,
    val expiry: AutofillId? = null,
    val expMonth: AutofillId? = null,
    val expYear: AutofillId? = null,
    val cvc: AutofillId? = null,
    val expiryPattern: String = "MM/YY",
    val fourDigitYear: Boolean = false,
) {
    val ids: List<AutofillId> get() = listOfNotNull(number, holder, expiry, expMonth, expYear, cvc)
}

/**
 * Màn hình đang xin tự động điền, đã phân tích. Được tạo trong dịch vụ từ AssistStructure DO HỆ THỐNG đưa
 * (tên gói đã được hệ thống kiểm tra) và "đóng băng" vào Intent của PendingIntent ([writeTo] / [readFrom]):
 * màn hình tự động điền KHÔNG đọc lại cấu trúc màn hình mà app kia gửi kèm (có thể bị giả).
 */
class ParsedScreen(
    val packageName: String,
    val target: AutofillTarget,
    val usernameId: AutofillId?,
    /** Ô mật khẩu hiện tại: nơi điền mật khẩu đã lưu. */
    val passwordIds: List<AutofillId>,
    /** Ô mật khẩu mới / nhập lại: không điền mật khẩu cũ vào; khi LƯU thì ưu tiên giá trị ở đây. */
    val newPasswordIds: List<AutofillId>,
    /** Giá trị đang có trong ô (chỉ có khi phân tích cho yêu cầu LƯU; không bao giờ ghi vào Intent). */
    val usernameValue: String = "",
    val passwordValue: String = "",
    /** Form thanh toán thẻ (khi đó các ô đăng nhập bị bỏ qua). */
    val card: CardFields? = null,
) {
    val fillIds: List<AutofillId> get() = card?.ids ?: (listOfNotNull(usernameId) + passwordIds + newPasswordIds)
    val isEmpty: Boolean get() = card == null && usernameId == null && passwordIds.isEmpty() && newPasswordIds.isEmpty()

    fun writeTo(intent: Intent): Intent = intent.apply {
        putExtra(X_PACKAGE, packageName)
        putExtra(X_USERNAME_ID, usernameId)
        putParcelableArrayListExtra(X_PASSWORD_IDS, ArrayList(passwordIds))
        putParcelableArrayListExtra(X_NEW_PASSWORD_IDS, ArrayList(newPasswordIds))
        // Ghi ĐỦ mọi khóa (null nếu không có): hệ thống chỉ cho app kia THÊM extra chưa có, nên khóa vắng mặt là chỗ
        // nó chèn được (vd. biến form đăng nhập thành form thẻ). Khóa đã ghi (kể cả null) thì không ghi đè được.
        val c = card
        putExtra(X_CARD_NUMBER, c?.number); putExtra(X_CARD_HOLDER, c?.holder); putExtra(X_CARD_EXPIRY, c?.expiry)
        putExtra(X_CARD_MONTH, c?.expMonth); putExtra(X_CARD_YEAR, c?.expYear); putExtra(X_CARD_CVC, c?.cvc)
        putExtra(X_CARD_PATTERN, c?.expiryPattern); putExtra(X_CARD_YEAR4, c?.fourDigitYear ?: false)
        if (target !is AutofillTarget.App) putExtra(X_WEB_HOST, null as String?)
        when (val t = target) {
            is AutofillTarget.Web -> {
                putExtra(X_KIND, "web"); putExtra(X_HOST, t.host); putExtra(X_SCHEME, t.scheme); putExtra(X_TARGET_PKG, t.browserPackage)
            }
            is AutofillTarget.App -> {
                putExtra(X_KIND, "app"); putExtra(X_TARGET_PKG, t.packageName); putExtra(X_CERT, t.certSha256)
                putExtra(X_CERTS, t.acceptedCerts.toTypedArray()); putExtra(X_FROM_STORE, t.fromStore)
                putExtra(X_WEB_HOST, t.webHost)
            }
            is AutofillTarget.UnverifiedBrowser -> {
                putExtra(X_KIND, "browser?"); putExtra(X_HOST, t.host); putExtra(X_SCHEME, t.scheme)
                putExtra(X_TARGET_PKG, t.packageName); putExtra(X_CERT, t.certSha256)
                putExtra(X_FROM_STORE, t.fromStore); putExtra(X_CERT_CHANGED, t.certChanged)
            }
        }
    }

    companion object {
        private const val X_PACKAGE = "af.package"
        private const val X_USERNAME_ID = "af.username_id"
        private const val X_PASSWORD_IDS = "af.password_ids"
        private const val X_NEW_PASSWORD_IDS = "af.new_password_ids"
        private const val X_KIND = "af.kind"
        private const val X_HOST = "af.host"
        private const val X_SCHEME = "af.scheme"
        private const val X_TARGET_PKG = "af.target_pkg"
        private const val X_CERT = "af.cert"
        private const val X_CERTS = "af.certs"
        private const val X_FROM_STORE = "af.from_store"
        private const val X_CERT_CHANGED = "af.cert_changed"
        private const val X_WEB_HOST = "af.web_host"
        private const val X_CARD_NUMBER = "af.card_number"
        private const val X_CARD_HOLDER = "af.card_holder"
        private const val X_CARD_EXPIRY = "af.card_expiry"
        private const val X_CARD_MONTH = "af.card_month"
        private const val X_CARD_YEAR = "af.card_year"
        private const val X_CARD_CVC = "af.card_cvc"
        private const val X_CARD_PATTERN = "af.card_pattern"
        private const val X_CARD_YEAR4 = "af.card_year4"

        private fun id(intent: Intent, key: String) = IntentCompat.getParcelableExtra(intent, key, AutofillId::class.java)

        fun readFrom(intent: Intent): ParsedScreen? {
            val pkg = intent.getStringExtra(X_PACKAGE) ?: return null
            val targetPkg = intent.getStringExtra(X_TARGET_PKG) ?: return null
            val target = when (intent.getStringExtra(X_KIND)) {
                "web" -> AutofillTarget.Web(intent.getStringExtra(X_HOST) ?: return null, intent.getStringExtra(X_SCHEME), targetPkg)
                "app" -> AutofillTarget.App(
                    targetPkg, intent.getStringExtra(X_CERT),
                    intent.getStringArrayExtra(X_CERTS)?.toSet().orEmpty(), intent.getBooleanExtra(X_FROM_STORE, false),
                    intent.getStringExtra(X_WEB_HOST),
                )
                "browser?" -> AutofillTarget.UnverifiedBrowser(
                    intent.getStringExtra(X_HOST) ?: return null, intent.getStringExtra(X_SCHEME), targetPkg,
                    intent.getStringExtra(X_CERT), intent.getBooleanExtra(X_FROM_STORE, false), intent.getBooleanExtra(X_CERT_CHANGED, false),
                )
                else -> return null
            }
            val card = id(intent, X_CARD_NUMBER)?.let { number ->
                CardFields(
                    number, id(intent, X_CARD_HOLDER), id(intent, X_CARD_EXPIRY), id(intent, X_CARD_MONTH), id(intent, X_CARD_YEAR),
                    id(intent, X_CARD_CVC), intent.getStringExtra(X_CARD_PATTERN) ?: "MM/YY", intent.getBooleanExtra(X_CARD_YEAR4, false),
                )
            }
            return ParsedScreen(
                pkg, target,
                IntentCompat.getParcelableExtra(intent, X_USERNAME_ID, AutofillId::class.java),
                IntentCompat.getParcelableArrayListExtra(intent, X_PASSWORD_IDS, AutofillId::class.java).orEmpty(),
                IntentCompat.getParcelableArrayListExtra(intent, X_NEW_PASSWORD_IDS, AutofillId::class.java).orEmpty(),
                card = card,
            )
        }
    }
}

/** Đổi AssistStructure (cây giao diện của app đang mở) thành dữ liệu cho FormClassifier / AutofillMatcher. */
object StructureParser {
    private val HTML_TEXT_ATTRS = setOf("name", "id", "autocomplete", "placeholder", "aria-label", "label", "title")

    /** @param withValues true chỉ cho yêu cầu LƯU (đọc username / mật khẩu người dùng vừa gõ). */
    fun parse(context: Context, structure: AssistStructure, withValues: Boolean = false): ParsedScreen {
        val packageName = structure.activityComponent?.packageName.orEmpty()
        val fields = ArrayList<FormField>()
        val ids = HashMap<Int, AutofillId>()
        val values = HashMap<Int, String>()
        // Tên miền của từng ô = tên miền gần nhất phía trên nó (khung / iframe chứa nó), không phải node đầu tiên.
        val fieldDomain = HashMap<Int, Pair<String, String?>>()
        var firstDomain: Pair<String, String?>? = null
        var focused: Int? = null

        // Duyệt KHÔNG đệ quy (cây giao diện rất sâu không làm tràn stack); ô nằm trong khung ẩn cũng là ẩn.
        class Item(val node: AssistStructure.ViewNode, val parentVisible: Boolean, val domain: Pair<String, String?>?)
        val stack = ArrayDeque<Item>()
        for (w in structure.windowNodeCount - 1 downTo 0) stack.addLast(Item(structure.getWindowNodeAt(w).rootViewNode, true, null))
        while (stack.isNotEmpty()) {
            val item = stack.removeLast()
            val node = item.node
            val visible = item.parentVisible && node.visibility == View.VISIBLE
            val own = node.webDomain?.takeIf { it.isNotBlank() }?.let { it to node.webScheme }
            val domain = own ?: item.domain
            if (own != null && firstDomain == null) firstDomain = own
            val id = node.autofillId
            // Thanh địa chỉ của trình duyệt (compat mode): không phải ô của trang.
            val isAddressBar = own != null || node.idEntry?.lowercase() in ADDRESS_BAR_IDS
            if (visible && id != null && node.autofillType == View.AUTOFILL_TYPE_TEXT && !isAddressBar) {
                val index = fields.size
                val attrs = node.htmlInfo?.attributes.orEmpty().mapNotNull { p ->
                    val name = p.first?.lowercase() ?: return@mapNotNull null
                    name to p.second?.lowercase()
                }
                val htmlType = attrs.firstOrNull { it.first == "type" }?.second
                fields += FormField(
                    index = index,
                    hints = node.autofillHints.orEmpty().map { it.lowercase() } +
                        attrs.filter { it.first == "autocomplete" }.mapNotNull { it.second },
                    htmlType = htmlType,
                    htmlAttrs = attrs.filter { it.first in HTML_TEXT_ATTRS }.mapNotNull { it.second },
                    inputType = node.inputType,
                    texts = listOfNotNull(node.idEntry, node.hint, node.contentDescription?.toString()),
                    maxLength = attrs.firstOrNull { it.first == "maxlength" }?.second?.toIntOrNull()
                        ?: node.maxTextLength.takeIf { it > 0 },
                )
                ids[index] = id
                domain?.let { fieldDomain[index] = it }
                if (node.isFocused) focused = index
                if (withValues) node.autofillValue?.takeIf { it.isText }?.textValue?.toString()?.let { values[index] = it }
            }
            for (i in node.childCount - 1 downTo 0) stack.addLast(Item(node.getChildAt(i), visible, domain))
        }

        // Tên miền của FORM = của ô đang được chạm (hoặc ô đầu tiên có tên miền); bỏ ô thuộc khung tên miền khác
        // (iframe quảng cáo / chèn vào trang không được nhận mật khẩu của trang chính).
        val formDomain = focused?.let { fieldDomain[it] } ?: fields.firstNotNullOfOrNull { fieldDomain[it.index] } ?: firstDomain
        val webDomain = formDomain?.first
        val webScheme = formDomain?.second
        val usable = if (formDomain == null) fields else fields.filter { f -> fieldDomain[f.index].let { it == null || it.first == formDomain.first } }

        // Form thanh toán thẻ: các ô thẻ (CVV kiểu password...) không bị coi là ô đăng nhập.
        val cardForm = FormClassifier.classifyCard(usable)
        val form = FormClassifier.classify(usable, exclude = cardForm.indices)
        val card = cardFields(cardForm, ids)
        val target = targetOf(context, packageName, webDomain, webScheme)
        val newValue = form.newPasswords.firstNotNullOfOrNull { values[it]?.takeIf { v -> v.isNotEmpty() } }
        val currentValue = form.passwords.firstNotNullOfOrNull { values[it]?.takeIf { v -> v.isNotEmpty() } }
        return ParsedScreen(
            packageName = packageName,
            target = target,
            usernameId = form.username?.let { ids[it] },
            passwordIds = form.passwords.mapNotNull { ids[it] },
            newPasswordIds = form.newPasswords.mapNotNull { ids[it] },
            usernameValue = form.username?.let { values[it] }.orEmpty(),
            // Form đổi mật khẩu: lưu mật khẩu MỚI.
            passwordValue = newValue ?: currentValue.orEmpty(),
            card = card,
        )
    }

    private fun cardFields(form: CardForm, ids: Map<Int, AutofillId>): CardFields? {
        val number = form.number?.let { ids[it] } ?: return null
        return CardFields(
            number = number,
            holder = form.holder?.let { ids[it] },
            expiry = form.expiry?.let { ids[it] },
            expMonth = form.expMonth?.let { ids[it] },
            expYear = form.expYear?.let { ids[it] },
            cvc = form.cvc?.let { ids[it] },
            expiryPattern = form.expiryPattern,
            fourDigitYear = form.fourDigitYear,
        )
    }

    /**
     * Tên miền chỉ được tin khi app báo nó là trình duyệt có tên gói đã biết VÀ người dùng đã xác nhận trình duyệt
     * đó với đúng chứng chỉ ký hiện tại. App khác tự xưng tên miền => coi như app thường.
     */
    private fun targetOf(context: Context, packageName: String, webDomain: String?, webScheme: String?): AutofillTarget {
        val identity = AppIdentity.of(context, packageName)
        if (webDomain != null && TrustedBrowsers.isTrusted(packageName)) {
            val pinned = (context.applicationContext as MatKhauApp).settings.trustedBrowserCert(packageName)
            // Chấp nhận cả chứng chỉ cũ trong lịch sử xoay vòng khóa ký (trình duyệt đổi khóa ký hợp lệ).
            return if (pinned != null && identity.current != null && identity.all.any { it.equals(pinned, ignoreCase = true) }) {
                AutofillTarget.Web(webDomain, webScheme, packageName)
            } else {
                AutofillTarget.UnverifiedBrowser(webDomain, webScheme, packageName, identity.current, identity.fromStore, certChanged = pinned != null)
            }
        }
        // App thường tự báo tên miền (trang web trong trình duyệt tích hợp / WebView): giữ lại để thu hẹp, không để tin.
        return AutofillTarget.App(packageName, identity.current, identity.all, identity.fromStore, webHost = webDomain?.let { DomainMatcher.hostOf(it) ?: it })
    }

    private val ADDRESS_BAR_IDS = setOf("url_bar", "omnibox", "location_bar", "search_box", "url_field", "address_bar", "mozac_browser_toolbar_url_view")
}
