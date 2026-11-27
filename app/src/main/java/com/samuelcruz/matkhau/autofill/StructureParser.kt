package com.samuelcruz.matkhau.autofill

import android.app.assist.AssistStructure
import android.content.Context
import android.view.View
import android.view.autofill.AutofillId
import com.samuelcruz.matkhau.core.autofill.AutofillTarget
import com.samuelcruz.matkhau.core.autofill.FormClassifier
import com.samuelcruz.matkhau.core.autofill.FormField
import com.samuelcruz.matkhau.core.autofill.LoginForm
import com.samuelcruz.matkhau.core.autofill.TrustedBrowsers

/** Màn hình đang xin tự động điền, đã phân tích. Không giữ tham chiếu tới AssistStructure. */
class ParsedScreen(
    val packageName: String,
    val target: AutofillTarget,
    val form: LoginForm,
    private val ids: Map<Int, AutofillId>,
    /** Giá trị đang có trong ô (chỉ dùng khi LƯU đăng nhập mới). */
    private val values: Map<Int, String>,
) {
    val usernameId: AutofillId? get() = form.username?.let { ids[it] }
    val passwordIds: List<AutofillId> get() = form.passwords.mapNotNull { ids[it] }
    val allIds: List<AutofillId> get() = listOfNotNull(usernameId) + passwordIds
    val usernameValue: String get() = form.username?.let { values[it] }.orEmpty()
    val passwordValue: String get() = form.passwords.firstNotNullOfOrNull { values[it]?.takeIf { v -> v.isNotEmpty() } }.orEmpty()
}

/** Đổi AssistStructure (cây giao diện của app đang mở) thành dữ liệu cho FormClassifier / AutofillMatcher. */
object StructureParser {
    private val HTML_TEXT_ATTRS = setOf("name", "id", "autocomplete", "placeholder", "aria-label", "label", "title")

    fun parse(context: Context, structure: AssistStructure): ParsedScreen {
        val packageName = structure.activityComponent?.packageName.orEmpty()
        val fields = ArrayList<FormField>()
        val ids = HashMap<Int, AutofillId>()
        val values = HashMap<Int, String>()
        var webDomain: String? = null
        var webScheme: String? = null

        fun visit(node: AssistStructure.ViewNode) {
            node.webDomain?.takeIf { it.isNotBlank() && webDomain == null }?.let {
                webDomain = it
                webScheme = node.webScheme
            }
            val id = node.autofillId
            if (id != null && node.autofillType == View.AUTOFILL_TYPE_TEXT && node.visibility == View.VISIBLE) {
                val index = fields.size
                val attrs = node.htmlInfo?.attributes.orEmpty()
                val htmlType = attrs.firstOrNull { it.first.equals("type", ignoreCase = true) }?.second?.lowercase()
                val htmlTexts = attrs.filter { it.first.lowercase() in HTML_TEXT_ATTRS }.mapNotNull { it.second?.lowercase() }
                val autocomplete = attrs.filter { it.first.equals("autocomplete", ignoreCase = true) }.mapNotNull { it.second?.lowercase() }
                fields += FormField(
                    index = index,
                    hints = node.autofillHints.orEmpty().map { it.lowercase() } + autocomplete,
                    htmlType = htmlType,
                    htmlAttrs = htmlTexts,
                    inputType = node.inputType,
                    texts = listOfNotNull(node.idEntry, node.hint, node.contentDescription?.toString()),
                )
                ids[index] = id
                node.autofillValue?.takeIf { it.isText }?.textValue?.toString()?.let { values[index] = it }
            }
            for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }
        for (w in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(w).rootViewNode)

        // Chỉ tin tên miền do trình duyệt đã biết báo; app khác tự xưng tên miền thì coi như app thường.
        val target = webDomain.takeIf { it != null && TrustedBrowsers.isTrusted(packageName) }
            ?.let { AutofillTarget.Web(it, webScheme, packageName) }
            ?: AutofillTarget.App(packageName, AppIdentity.certSha256(context, packageName))
        return ParsedScreen(packageName, target, FormClassifier.classify(fields), ids, values)
    }
}
