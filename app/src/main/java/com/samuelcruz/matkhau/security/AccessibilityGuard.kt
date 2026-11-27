package com.samuelcruz.matkhau.security

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import com.samuelcruz.matkhau.autofill.AppIdentity

/**
 * Chống trojan dùng quyền Trợ năng (Accessibility) — kiểu lừa đảo phổ biến: app giả xin bật Trợ năng rồi đọc màn
 * hình và tự bấm thay người dùng. FLAG_SECURE chỉ chặn chụp ảnh, không chặn Trợ năng.
 *
 * Android 14+: đánh dấu cả cửa sổ là "dữ liệu nhạy cảm" => chỉ dịch vụ Trợ năng khai báo isAccessibilityTool
 * (TalkBack, Switch Access... được Google Play kiểm duyệt) mới đọc / thao tác được; trojan thì không. Compose còn được
 * đánh dấu từng nút (Modifier.sensitive) vì nút ảo của Compose không đi qua hết các cổng kiểm tra của View.
 */
object AccessibilityGuard {
    class Service(val label: String, val packageName: String)
    class Keyboard(val label: String, val packageName: String, val trusted: Boolean)

    /** Bàn phím phổ biến; chỉ được coi là đáng tin khi là app hệ thống hoặc cài từ cửa hàng (không mạo danh). */
    private val KNOWN_KEYBOARDS = setOf(
        "com.google.android.inputmethod.latin", "com.samsung.android.honeyboard", "com.sec.android.inputmethod",
        "com.android.inputmethod.latin", "com.touchtype.swiftkey", "com.vng.inputmethod.labankey",
        "com.google.android.apps.inputmethod.zhuyin", "com.miui.securityinputmethod",
    )

    fun protectWindow(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val yes = View.ACCESSIBILITY_DATA_SENSITIVE_YES
        activity.window.decorView.setAccessibilityDataSensitive(yes)
        activity.findViewById<ViewGroup>(android.R.id.content)?.let { content ->
            content.setAccessibilityDataSensitive(yes)
            for (i in 0 until content.childCount) content.getChildAt(i).setAccessibilityDataSensitive(yes)
        }
    }

    /** Có được bảo vệ ở mức hệ điều hành không (Android 14+). */
    val isWindowProtectionSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * Dịch vụ Trợ năng đang bật mà KHÔNG đáng tin. "isAccessibilityTool" do chính app tự khai báo (hệ điều hành không
     * kiểm tra, chỉ Google Play kiểm duyệt), nên trojan cài từ file APK có thể tự nhận là công cụ để vượt qua lớp chặn
     * của hệ thống. Chỉ tin: app hệ thống (TalkBack, Switch Access… của máy), hoặc app cài từ cửa hàng đã biết VÀ
     * khai báo là công cụ Trợ năng.
     */
    fun riskyServices(context: Context): List<Service> = try {
        val am = context.getSystemService(AccessibilityManager::class.java)
        am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty()
            .mapNotNull { info ->
                val si = info.resolveInfo?.serviceInfo ?: return@mapNotNull null
                val pkg = si.packageName
                if (pkg == context.packageName) return@mapNotNull null
                val declaredTool = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.isAccessibilityTool
                val trusted = isSystemApp(context, pkg) || (declaredTool && AppIdentity.of(context, pkg).fromStore)
                if (trusted) null else Service(info.resolveInfo.loadLabel(context.packageManager).toString(), pkg)
            }
            .distinctBy { it.packageName }
    } catch (e: Exception) {
        emptyList()
    }

    /** Cửa sổ đã được đánh dấu nhạy cảm thật chưa (kiểm tra lúc chạy, cho màn hình Bảo mật). */
    fun isWindowProtected(activity: Activity): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && activity.window.decorView.isAccessibilityDataSensitive

    /** Gọi trong nội dung của Dialog / Popup (là CỬA SỔ RIÊNG, không thừa hưởng đánh dấu của activity). */
    fun protectView(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        view.rootView.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    }

    private fun isSystemApp(context: Context, pkg: String): Boolean = try {
        val flags = context.packageManager.getApplicationInfo(pkg, 0).flags
        flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } catch (e: Exception) {
        false
    }

    /** Bàn phím đang dùng. Bàn phím lạ ghi lại được mọi thứ bạn gõ, kể cả mật khẩu chính. */
    fun keyboard(context: Context): Keyboard? = try {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val pkg = id?.substringBefore('/')?.takeIf { it.isNotBlank() }
        pkg?.let {
            val trusted = isSystemApp(context, it) || (it in KNOWN_KEYBOARDS && AppIdentity.of(context, it).fromStore)
            Keyboard(AppIdentity.label(context, it), it, trusted)
        }
    } catch (e: Exception) {
        null
    }
}
