package com.samuelcruz.matkhau.autofill

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.samuelcruz.matkhau.MatKhauApp
import com.samuelcruz.matkhau.core.autofill.AutofillMatcher
import com.samuelcruz.matkhau.core.autofill.AutofillTarget
import com.samuelcruz.matkhau.model.Entry
import com.samuelcruz.matkhau.model.LinkedApp
import com.samuelcruz.matkhau.ui.VaultViewModel
import com.samuelcruz.matkhau.ui.theme.MatKhauTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import androidx.lifecycle.lifecycleScope
import com.samuelcruz.matkhau.core.autofill.TrustedBrowsers
import com.samuelcruz.matkhau.security.AccessibilityGuard
import com.samuelcruz.matkhau.security.SecretGuard

/**
 * Màn hình của tự động điền, hệ thống mở khi người dùng chọn một dòng đề xuất (hoặc khi lưu đăng nhập):
 *  - FILL: điền đúng một mục (đã khớp lúc đề xuất). Vault đang mở thì điền ngay, không hiện gì.
 *  - PICK: mở khóa nếu cần, rồi chọn mục. Mục không khớp: app => hỏi liên kết (ghim chứng chỉ ký);
 *    web => CHẶN (chống phishing), trừ khi mục chưa có URL và người dùng xác nhận gán tên miền này.
 *    Trình duyệt chưa xác nhận => hỏi tin trình duyệt (ghim chứng chỉ) trước khi tin tên miền nó báo.
 *  - SAVE: lưu đăng nhập mới hoặc cập nhật mật khẩu của mục đã có (mật khẩu cũ vào lịch sử).
 *
 * Nơi xin điền và các ô được ĐÓNG BĂNG trong Intent lúc dịch vụ tạo đề xuất (ParsedScreen.writeTo), không đọc lại
 * cấu trúc màn hình app kia gửi kèm. App gọi activity phải đúng là app đã xin điền.
 * Cùng bảo vệ như app chính: FLAG_SECURE, tự khóa, không đưa bí mật vào Intent hay savedInstanceState.
 */
class AutofillActivity : FragmentActivity() {
    private val vm: VaultViewModel by viewModels()
    private val app get() = application as MatKhauApp
    private var screen by mutableStateOf<ParsedScreen?>(null)
    private var save: PendingSave? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        // Chống tapjacking: ẩn cửa sổ nổi của app khác khi đang hỏi "Tin trình duyệt" / "Liên kết & điền".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AccessibilityGuard.protectWindow(this)
        vm.hostIsAutofill = true

        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_SAVE -> {
                val pending = app.pendingAutofillSave
                    ?.takeIf { !it.isExpired && it.nonce == intent.getStringExtra(EXTRA_NONCE) } ?: return finish()
                save = pending
                // Vault bị khóa (tắt màn hình, khóa khẩn cấp) => đóng màn lưu, bỏ mật khẩu đang chờ.
                lifecycleScope.launch {
                    vm.repository.lockCount.drop(1).collect {
                        if (app.pendingAutofillSave === pending) app.pendingAutofillSave = null
                        finish()
                    }
                }
                setContent { MatKhauTheme { AutofillSaveRoot(vm, pending, onDone = { finish() }) } }
            }
            MODE_FILL, MODE_PICK -> {
                val baked = ParsedScreen.readFrom(intent) ?: return finish()
                // Trình duyệt không bao giờ là "app thường" để điền / liên kết (trang không tên miền, giao diện riêng).
                if (baked.target is AutofillTarget.App && TrustedBrowsers.isTrusted(baked.packageName)) return finish()
                // Kết quả (mật khẩu) trả về cho activity đã gọi: nó phải là app đã xin điền lúc đầu.
                val caller = callingActivity?.packageName
                if (caller != null && caller != baked.packageName) return finish()
                screen = baked
                val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)
                // Đường nhanh: vault đang mở + mục vẫn khớp => điền luôn, không hiện màn hình.
                if (entryId != null && vm.repository.isUnlocked()) {
                    vm.repository.entries.value.firstOrNull { it.id == entryId }
                        ?.takeIf { AutofillMatcher.matches(it, baked.target) }
                        ?.let { if (fill(it)) return }
                }
                setContent {
                    MatKhauTheme {
                        screen?.let { current ->
                            AutofillPickRoot(
                                vm = vm,
                                screen = current,
                                preferredEntryId = entryId,
                                onFill = { entry -> fill(entry) },
                                onLinkAndFill = { entry -> linkAndFill(entry) },
                                onSetUrlAndFill = { entry, url -> setUrlAndFill(entry, url) },
                                onTrustBrowser = { trustBrowser() },
                                onCancel = { finish() },
                            )
                        }
                    }
                }
            }
            else -> finish()
        }
    }

    override fun onDestroy() {
        // Đăng nhập chờ lưu chứa mật khẩu dạng chữ: bỏ khi màn hình lưu ĐÓ đóng (không xóa nhầm yêu cầu mới hơn).
        if (isFinishing && save != null && app.pendingAutofillSave === save) app.pendingAutofillSave = null
        super.onDestroy()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        app.autoLock.onUserInteraction()
    }

    /**
     * Trả giá trị về cho hệ thống để điền vào app / trang kia. false nếu mục không có gì để điền vào form này.
     * Tùy chọn "xác thực mỗi lần" bật => hỏi vân tay trước (hủy => không điền, đóng màn hình).
     */
    private fun fill(entry: Entry, afterAuth: () -> Unit = {}): Boolean {
        val s = screen ?: return false
        val dataset = buildDataset(this, s, entry) ?: return false
        SecretGuard.run(this, "Điền “${entry.title}”", onDenied = { finish() }) {
            afterAuth()   // vd. lưu liên kết app / URL: chỉ khi người dùng đã xác thực xong
            setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset))
            finish()
        }
        return true
    }

    override fun onContentChanged() {
        super.onContentChanged()
        // Chỉ công cụ Trợ năng thật đọc / thao tác được màn hình này (Android 14+).
        AccessibilityGuard.protectWindow(this)
    }

    private fun linkAndFill(entry: Entry): Boolean {
        val target = screen?.target as? AutofillTarget.App ?: return false
        val cert = target.certSha256 ?: return false
        val updated = entry.copy(
            linkedApps = entry.linkedApps.filterNot { it.packageName == target.packageName } + LinkedApp(target.packageName, cert),
        )
        return fill(updated) { saveInBackground(updated, "Chưa lưu được liên kết (vault đã khóa?). Lần sau hãy liên kết lại.") }
    }

    private fun setUrlAndFill(entry: Entry, url: String): Boolean {
        val updated = entry.copy(url = url)
        return fill(updated) { saveInBackground(updated, "Chưa lưu được URL cho mục (vault đã khóa?)") }
    }

    /** Người dùng xác nhận trình duyệt: ghim chứng chỉ, từ giờ tin tên miền nó báo. */
    private fun trustBrowser() {
        val s = screen ?: return
        val t = s.target as? AutofillTarget.UnverifiedBrowser ?: return
        val cert = t.certSha256 ?: return
        app.settings.trustBrowser(t.packageName, cert)
        screen = ParsedScreen(s.packageName, AutofillTarget.Web(t.host, t.scheme, t.packageName), s.usernameId, s.passwordIds, s.newPasswordIds)
    }

    /** Activity đóng ngay sau khi điền, nên lưu ở scope của app; lỗi thì báo bằng Toast. */
    private fun saveInBackground(entry: Entry, failure: String) {
        val context = applicationContext
        app.appScope.launch {
            runCatching { app.repository.save(entry) }.onFailure {
                Toast.makeText(context, failure, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ENTRY_ID = "entry_id"
        private const val EXTRA_NONCE = "save_nonce"
        private const val MODE_FILL = "fill"
        private const val MODE_PICK = "pick"
        private const val MODE_SAVE = "save"

        fun fillIntent(context: Context, screen: ParsedScreen, entryId: String): IntentSender =
            MatKhauAutofillService.sender(context, screen.writeTo(intent(context, MODE_FILL)).putExtra(EXTRA_ENTRY_ID, entryId))

        fun pickIntent(context: Context, screen: ParsedScreen): IntentSender =
            MatKhauAutofillService.sender(context, screen.writeTo(intent(context, MODE_PICK)))

        fun saveIntent(context: Context, nonce: String): IntentSender =
            MatKhauAutofillService.sender(context, intent(context, MODE_SAVE).putExtra(EXTRA_NONCE, nonce))

        private fun intent(context: Context, mode: String) =
            Intent(context, AutofillActivity::class.java).putExtra(EXTRA_MODE, mode)

        /**
         * Dataset chứa giá trị thật của mục (chỉ tạo SAU khi người dùng đã chọn mục). Mật khẩu chỉ vào ô mật khẩu
         * HIỆN TẠI, không bao giờ vào ô mật khẩu mới / nhập lại.
         */
        @Suppress("DEPRECATION")
        fun buildDataset(context: Context, screen: ParsedScreen, entry: Entry): Dataset? {
            val b = Dataset.Builder(MatKhauAutofillService.presentation(context, entry.title, entry.username))
            var any = false
            screen.usernameId?.let { id ->
                if (entry.username.isNotEmpty()) { b.setValue(id, AutofillValue.forText(entry.username)); any = true }
            }
            if (entry.password.isNotEmpty()) {
                screen.passwordIds.forEach { id -> b.setValue(id, AutofillValue.forText(entry.password)); any = true }
            }
            return if (any) b.build() else null
        }
    }
}
