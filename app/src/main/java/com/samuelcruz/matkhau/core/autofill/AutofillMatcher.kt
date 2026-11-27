package com.samuelcruz.matkhau.core.autofill

import com.samuelcruz.matkhau.model.Entry
import com.samuelcruz.matkhau.model.EntryType

/** Nơi đang xin tự động điền. */
sealed interface AutofillTarget {
    /** Trang web trong một trình duyệt ĐÁNG TIN ([TrustedBrowsers]): tên miền do trình duyệt báo. */
    data class Web(val host: String, val scheme: String?, val browserPackage: String) : AutofillTarget

    /** Ứng dụng Android: tên gói + SHA-256 chứng chỉ ký (null nếu không đọc được => không bao giờ tự khớp). */
    data class App(val packageName: String, val certSha256: String?) : AutofillTarget
}

/**
 * Chọn mục được phép điền (spec 3.1 + 4.2). Nguyên tắc: không chắc => không điền.
 *  - Web: tên miền gốc của URL đã lưu phải trùng tên miền gốc của trang ([DomainMatcher]).
 *  - App: chỉ mục đã được NGƯỜI DÙNG liên kết với đúng tên gói VÀ đúng chứng chỉ ký.
 */
object AutofillMatcher {
    fun matches(entry: Entry, target: AutofillTarget): Boolean {
        if (entry.type != EntryType.LOGIN) return false
        return when (target) {
            is AutofillTarget.Web -> DomainMatcher.matches(entry.url, target.host, target.scheme)
            is AutofillTarget.App -> target.certSha256 != null && entry.linkedApps.any {
                it.packageName == target.packageName && it.certSha256.equals(target.certSha256, ignoreCase = true)
            }
        }
    }

    fun matching(entries: List<Entry>, target: AutofillTarget): List<Entry> =
        entries.filter { matches(it, target) }.sortedWith(compareByDescending<Entry> { it.favorite }.thenBy { it.title.lowercase() })

    /**
     * Gợi ý (KHÔNG tự điền) khi chọn tay cho một app: mục nhập từ Bitwarden có URL "androidapp://<gói>",
     * hoặc mục mà tên / URL chứa một phần tên gói (vd. "vietcombank" trong com.vietcombank.app).
     */
    fun suggestedForApp(entry: Entry, packageName: String): Boolean {
        if (entry.type != EntryType.LOGIN) return false
        if (entry.url.trim().equals("androidapp://$packageName", ignoreCase = true)) return true
        val words = packageName.lowercase().split('.').filter { it.length >= 4 && it !in COMMON_PACKAGE_WORDS }
        val haystack = (entry.title + " " + entry.url).lowercase()
        return words.any { haystack.contains(it) }
    }

    private val COMMON_PACKAGE_WORDS = setOf("android", "mobile", "app", "apps", "client", "prod", "release", "google")
}

/**
 * Trình duyệt được tin để báo đúng tên miền của trang. App khác tự báo "webDomain" thì bị bỏ qua
 * (coi như app thường): một app độc có thể nói dối là đang mở bank.com để xin mật khẩu ngân hàng.
 */
object TrustedBrowsers {
    val PACKAGES = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.focus", "org.mozilla.klar",
        "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta",
        "com.microsoft.emmx", "com.brave.browser", "com.brave.browser_beta", "com.opera.browser", "com.opera.mini.native",
        "com.vivaldi.browser", "com.duckduckgo.mobile.android", "com.kiwibrowser.browser", "com.coccoc.trinhduyet",
        "org.chromium.chrome", "com.google.android.apps.chrome", "app.vanadium.browser", "org.bromite.bromite",
        "us.spotco.fennec_dos", "org.torproject.torbrowser", "com.ecosia.android", "mark.via.gp",
    )

    fun isTrusted(packageName: String): Boolean = packageName in PACKAGES
}
