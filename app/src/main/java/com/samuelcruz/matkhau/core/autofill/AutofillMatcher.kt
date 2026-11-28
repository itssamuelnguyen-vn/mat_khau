package com.samuelcruz.matkhau.core.autofill

import com.samuelcruz.matkhau.model.Entry
import com.samuelcruz.matkhau.model.EntryType

/** Nơi đang xin tự động điền. */
sealed interface AutofillTarget {
    /** Trang web trong một trình duyệt ĐÁNG TIN ([TrustedBrowsers]): tên miền do trình duyệt báo. */
    data class Web(val host: String, val scheme: String?, val browserPackage: String) : AutofillTarget

    /**
     * Ứng dụng Android: tên gói + SHA-256 chứng chỉ ký hiện tại ([certSha256], null nếu không đọc được => không
     * bao giờ tự khớp) và mọi chứng chỉ trong lịch sử xoay vòng khóa ký ([acceptedCerts]) để app đổi khóa ký hợp lệ
     * vẫn khớp liên kết cũ. [fromStore] = cài từ cửa hàng đã biết (Google Play...), false = cài ngoài / không rõ.
     */
    data class App(
        val packageName: String,
        val certSha256: String?,
        val acceptedCerts: Set<String> = setOfNotNull(certSha256),
        val fromStore: Boolean = false,
        /**
         * Tên miền một app KHÔNG phải trình duyệt tự báo (trang web mở trong trình duyệt tích hợp / WebView của Zalo,
         * Facebook...). Không đủ tin để cho khớp, nhưng dùng để THU HẸP: mục liên kết với app chỉ khớp nếu URL của nó
         * cũng đúng tên miền này; không liên kết, không điền thẻ.
         */
        val webHost: String? = null,
    ) : AutofillTarget

    /**
     * Trang web trong một app CÓ TÊN GÓI của trình duyệt đã biết nhưng chưa được người dùng xác nhận (hoặc chứng
     * chỉ khác lần xác nhận trước): chưa tin tên miền nó báo, không tự khớp mục nào. [certChanged] = đã từng tin
     * gói này với chứng chỉ khác => rất có thể là app giả mạo.
     */
    data class UnverifiedBrowser(
        val host: String,
        val scheme: String?,
        val packageName: String,
        val certSha256: String?,
        val fromStore: Boolean,
        val certChanged: Boolean,
    ) : AutofillTarget
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
            is AutofillTarget.App -> target.certSha256 != null && entry.linkedApps.any { linked ->
                linked.packageName == target.packageName && target.acceptedCerts.any { it.equals(linked.certSha256, ignoreCase = true) }
            } && (target.webHost == null || DomainMatcher.matches(entry.url, target.webHost, null))
            is AutofillTarget.UnverifiedBrowser -> false
        }
    }

    /**
     * Thẻ không gắn với một trang / app cụ thể (như trình duyệt vẫn làm), nhưng chỉ đề xuất cho: trang web trong trình
     * duyệt đã xác nhận mà không phải http (không mã hóa), hoặc app đọc được chứng chỉ ký. Lần điền nào cũng hỏi vân tay
     * và hiện rõ nơi nhận thẻ.
     */
    fun cardAllowed(target: AutofillTarget): Boolean = when (target) {
        is AutofillTarget.Web -> !target.scheme.equals("http", ignoreCase = true)
        is AutofillTarget.App -> target.certSha256 != null && target.webHost == null
        is AutofillTarget.UnverifiedBrowser -> false
    }

    /** Trình duyệt báo rõ trang là https. null = không rõ (vài trình duyệt không báo): vẫn cho điền nhưng cảnh báo. */
    fun httpsConfirmed(target: AutofillTarget): Boolean =
        target is AutofillTarget.Web && target.scheme.equals("https", ignoreCase = true)

    fun cards(entries: List<Entry>): List<Entry> =
        entries.filter { it.type == EntryType.CARD && it.card?.number?.isNotEmpty() == true }
            .sortedWith(compareByDescending<Entry> { it.favorite }.thenBy { it.title.lowercase() })

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
 * Tên gói của các trình duyệt được phép báo tên miền của trang. App khác tự báo "webDomain" thì bị bỏ qua (coi như
 * app thường): một app độc có thể nói dối là đang mở bank.com để xin mật khẩu ngân hàng. Tên gói thôi CHƯA đủ (app
 * cài ngoài có thể mạo danh tên gói của một trình duyệt máy không cài): người dùng còn phải xác nhận lần đầu, và
 * chứng chỉ ký của trình duyệt được ghim (AppSettings.trustedBrowsers).
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
