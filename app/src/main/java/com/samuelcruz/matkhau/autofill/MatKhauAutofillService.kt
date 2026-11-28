package com.samuelcruz.matkhau.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import com.samuelcruz.matkhau.MatKhauApp
import com.samuelcruz.matkhau.R
import com.samuelcruz.matkhau.core.autofill.AutofillMatcher
import com.samuelcruz.matkhau.core.autofill.AutofillTarget
import com.samuelcruz.matkhau.core.autofill.TrustedBrowsers
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dịch vụ tự động điền (spec 3.1). Hệ thống gọi khi người dùng chạm vào một ô trên app / trang web khác.
 *
 * Mật khẩu KHÔNG được gửi cho hệ thống lúc đề xuất: mỗi dòng đề xuất là một "dataset cần xác thực" trỏ tới
 * [AutofillActivity]; chỉ khi người dùng chọn một dòng, activity mới (mở khóa nếu cần rồi) trả về giá trị.
 *  - Vault đang mở: các mục KHỚP (đúng tên miền / đúng app + chứng chỉ, xem AutofillMatcher) + "Chọn mục khác…".
 *  - Vault đang khóa: một dòng "Mở khóa Mật Khẩu" (tên mục cũng đang được mã hóa nên không liệt kê được).
 * Có ô mật khẩu => kèm SaveInfo để hệ thống đề nghị LƯU đăng nhập mới sau khi người dùng gửi form.
 */
class MatKhauAutofillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val response = try {
            buildResponse(request)
        } catch (e: Throwable) {
            null   // không bao giờ làm app kia lỗi vì chúng ta (kể cả cây giao diện bất thường)
        }
        callback.onSuccess(response)
    }

    private fun buildResponse(request: FillRequest): FillResponse? {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return null
        // Cấu trúc này do HỆ THỐNG đưa: tên gói đã được kiểm tra. Kết quả được "đóng băng" vào từng PendingIntent.
        val screen = StructureParser.parse(this, structure)
        if (screen.packageName == packageName || screen.isEmpty) return null
        // Giao diện riêng của trình duyệt / trang không có tên miền (data:, file:): không điền, không cho liên kết.
        if (screen.target is AutofillTarget.App && TrustedBrowsers.isTrusted(screen.packageName)) return null
        val ids = screen.fillIds
        if (ids.isEmpty()) return null

        val app = application as MatKhauApp
        val builder = FillResponse.Builder()
        val targetLabel = when (val t = screen.target) {
            is AutofillTarget.Web -> t.host
            is AutofillTarget.UnverifiedBrowser -> t.host
            is AutofillTarget.App -> AppIdentity.label(this, t.packageName)
        }

        if (screen.card != null) {
            // Form thanh toán: chỉ đề xuất thẻ, không lưu gì. Trang http / app không rõ chứng chỉ: không đề xuất thẻ.
            if (screen.target !is AutofillTarget.UnverifiedBrowser && !AutofillMatcher.cardAllowed(screen.target)) return null
            if (app.repository.isUnlocked()) {
                val offer = if (AutofillMatcher.cardAllowed(screen.target)) AutofillMatcher.cards(app.repository.entries.value) else emptyList()
                offer.take(MAX_SUGGESTIONS).forEach { e ->
                    val c = e.card ?: return@forEach
                    builder.addDataset(
                        authDataset(ids, presentation(e.title, "Thẻ ${com.samuelcruz.matkhau.core.card.PaymentCard.masked(c.number)}"), AutofillActivity.fillIntent(this, screen, e.id)),
                    )
                }
                builder.addDataset(authDataset(ids, presentation("Chọn thẻ khác…", "Mật Khẩu"), AutofillActivity.pickIntent(this, screen)))
            } else {
                builder.addDataset(authDataset(ids, presentation("Mở khóa Mật Khẩu", "để điền thẻ vào $targetLabel"), AutofillActivity.pickIntent(this, screen)))
            }
            return builder.build()
        }

        if (app.repository.isUnlocked()) {
            AutofillMatcher.matching(app.repository.entries.value, screen.target).take(MAX_SUGGESTIONS).forEach { e ->
                builder.addDataset(
                    authDataset(ids, presentation(e.title, e.username.ifEmpty { targetLabel }), AutofillActivity.fillIntent(this, screen, e.id)),
                )
            }
            builder.addDataset(authDataset(ids, presentation("Chọn mục khác…", "Mật Khẩu"), AutofillActivity.pickIntent(this, screen)))
        } else {
            builder.addDataset(authDataset(ids, presentation("Mở khóa Mật Khẩu", "để điền vào $targetLabel"), AutofillActivity.pickIntent(this, screen)))
        }

        val saveIds = screen.passwordIds + screen.newPasswordIds
        if (saveIds.isNotEmpty()) {
            val type = SaveInfo.SAVE_DATA_TYPE_PASSWORD or (if (screen.usernameId != null) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0)
            val save = SaveInfo.Builder(type, saveIds.toTypedArray())
                // App chỉ có một activity: màn đăng nhập biến mất (không chuyển activity) vẫn hỏi lưu.
                .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            screen.usernameId?.let { save.setOptionalIds(arrayOf(it)) }
            builder.setSaveInfo(save.build())
        }
        return builder.build()
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        try {
            val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess()
            val screen = StructureParser.parse(this, structure, withValues = true)
            val password = screen.passwordValue
            if (screen.packageName == packageName || password.isEmpty()) return callback.onSuccess()
            if (screen.target is AutofillTarget.App && TrustedBrowsers.isTrusted(screen.packageName)) return callback.onSuccess()
            // Giữ trong RAM của process (không đưa mật khẩu vào Intent), activity lấy ra sau khi mở khóa;
            // mã ngẫu nhiên gắn Intent với ĐÚNG yêu cầu lưu này (Intent cũ không mở được yêu cầu mới hơn).
            val pending = PendingSave(screen.target, screen.usernameValue, password)
            (application as MatKhauApp).pendingAutofillSave = pending
            callback.onSuccess(AutofillActivity.saveIntent(this, pending.nonce))
        } catch (e: Throwable) {
            callback.onSuccess()
        }
    }

    @Suppress("DEPRECATION") // API mới (Presentations, Field) chỉ có từ Android 13; API cũ vẫn chạy trên 16
    private fun authDataset(ids: List<AutofillId>, presentation: RemoteViews, auth: IntentSender): Dataset {
        val b = Dataset.Builder(presentation)
        ids.forEach { b.setValue(it, null) }   // giá trị thật chỉ có sau khi xác thực
        b.setAuthentication(auth)
        return b.build()
    }

    private fun presentation(title: String, subtitle: String) = presentation(this, title, subtitle)

    companion object {
        private const val MAX_SUGGESTIONS = 5
        private val requestCodes = AtomicInteger(1)

        fun presentation(context: Context, title: String, subtitle: String): RemoteViews =
            RemoteViews(context.packageName, R.layout.autofill_item).apply {
                setTextViewText(R.id.autofill_title, title)
                setTextViewText(R.id.autofill_subtitle, subtitle)
            }

        /**
         * PendingIntent cho dataset cần xác thực: MUTABLE vì hệ thống gắn thêm dữ liệu vào Intent (chỉ THÊM được,
         * không ghi đè extra đã có, nên nơi xin điền đã "đóng băng" không bị sửa). Intent chỉ đích danh activity của
         * app (explicit) nên không app nào khác nhận được.
         */
        fun sender(context: Context, intent: Intent): IntentSender {
            // URI ngẫu nhiên riêng cho mỗi Intent: hệ thống không bao giờ trả lại một PendingIntent cũ (cùng mã
            // request sau khi app khởi động lại) mang nơi xin điền của lượt khác; chạm lại đề xuất vẫn chạy.
            intent.data = android.net.Uri.parse("matkhau-autofill://request/" + java.util.UUID.randomUUID())
            return PendingIntent.getActivity(context, requestCodes.getAndIncrement(), intent, PendingIntent.FLAG_MUTABLE).intentSender
        }
    }
}

/** Đăng nhập vừa gửi, chờ người dùng xác nhận lưu. Không bao giờ ghi xuống đĩa ở dạng này; hết hạn sau 10 phút. */
class PendingSave(val target: AutofillTarget, val username: String, val password: String) {
    val nonce: String = java.util.UUID.randomUUID().toString()
    private val createdAt = android.os.SystemClock.elapsedRealtime()
    val isExpired: Boolean get() = android.os.SystemClock.elapsedRealtime() - createdAt > 10 * 60_000L
    override fun toString(): String = "PendingSave(target=$target)"
}
