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
import com.samuelcruz.matkhau.core.autofill.TrustedBrowsers

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
) {
    val fillIds: List<AutofillId> get() = listOfNotNull(usernameId) + passwordIds + newPasswordIds
    val isEmpty: Boolean get() = usernameId == null && passwordIds.isEmpty() && newPasswordIds.isEmpty()

    fun writeTo(intent: Intent): Intent = intent.apply {
        putExtra(X_PACKAGE, packageName)
        putExtra(X_USERNAME_ID, usernameId)
        putParcelableArrayListExtra(X_PASSWORD_IDS, ArrayList(passwordIds))
        putParcelableArrayListExtra(X_NEW_PASSWORD_IDS, ArrayList(newPasswordIds))
        when (val t = target) {
            is AutofillTarget.Web -> {
                putExtra(X_KIND, "web"); putExtra(X_HOST, t.host); putExtra(X_SCHEME, t.scheme); putExtra(X_TARGET_PKG, t.browserPackage)
            }
            is AutofillTarget.App -> {
                putExtra(X_KIND, "app"); putExtra(X_TARGET_PKG, t.packageName); putExtra(X_CERT, t.certSha256)
                putExtra(X_CERTS, t.acceptedCerts.toTypedArray()); putExtra(X_FROM_STORE, t.fromStore)
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

        fun readFrom(intent: Intent): ParsedScreen? {
            val pkg = intent.getStringExtra(X_PACKAGE) ?: return null
            val targetPkg = intent.getStringExtra(X_TARGET_PKG) ?: return null
            val target = when (intent.getStringExtra(X_KIND)) {
                "web" -> AutofillTarget.Web(intent.getStringExtra(X_HOST) ?: return null, intent.getStringExtra(X_SCHEME), targetPkg)
                "app" -> AutofillTarget.App(
                    targetPkg, intent.getStringExtra(X_CERT),
                    intent.getStringArrayExtra(X_CERTS)?.toSet().orEmpty(), intent.getBooleanExtra(X_FROM_STORE, false),
                )
                "browser?" -> AutofillTarget.UnverifiedBrowser(
                    intent.getStringExtra(X_HOST) ?: return null, intent.getStringExtra(X_SCHEME), targetPkg,
                    intent.getStringExtra(X_CERT), intent.getBooleanExtra(X_FROM_STORE, false), intent.getBooleanExtra(X_CERT_CHANGED, false),
                )
                else -> return null
            }
            return ParsedScreen(
                pkg, target,
                IntentCompat.getParcelableExtra(intent, X_USERNAME_ID, AutofillId::class.java),
                IntentCompat.getParcelableArrayListExtra(intent, X_PASSWORD_IDS, AutofillId::class.java).orEmpty(),
                IntentCompat.getParcelableArrayListExtra(intent, X_NEW_PASSWORD_IDS, AutofillId::class.java).orEmpty(),
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
        var webDomain: String? = null
        var webScheme: String? = null

        // Duyệt KHÔNG đệ quy (cây giao diện rất sâu không làm tràn stack); ô nằm trong khung ẩn cũng là ẩn.
        val stack = ArrayDeque<Pair<AssistStructure.ViewNode, Boolean>>()
        for (w in structure.windowNodeCount - 1 downTo 0) stack.addLast(structure.getWindowNodeAt(w).rootViewNode to true)
        while (stack.isNotEmpty()) {
            val (node, parentVisible) = stack.removeLast()
            val visible = parentVisible && node.visibility == View.VISIBLE
            node.webDomain?.takeIf { it.isNotBlank() && webDomain == null }?.let {
                webDomain = it
                webScheme = node.webScheme
            }
            val id = node.autofillId
            if (visible && id != null && node.autofillType == View.AUTOFILL_TYPE_TEXT) {
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
                )
                ids[index] = id
                if (withValues) node.autofillValue?.takeIf { it.isText }?.textValue?.toString()?.let { values[index] = it }
            }
            for (i in node.childCount - 1 downTo 0) stack.addLast(node.getChildAt(i) to visible)
        }

        val form = FormClassifier.classify(fields)
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
        return AutofillTarget.App(packageName, identity.current, identity.all, identity.fromStore)
    }
}
