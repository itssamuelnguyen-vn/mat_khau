package com.samuelcruz.matkhau.core.autofill

import java.net.IDN

/**
 * Chống phishing khi tự động điền (spec 4.2): chỉ điền mục đã lưu cho ĐÚNG website đang mở.
 *
 * Khớp CHẶT: trang phải là đúng tên miền đã lưu hoặc một tên miền con của nó ("www." ở URL đã lưu được bỏ).
 * Lưu "https://github.com" thì gist.github.com khớp; lưu "https://mail.google.com" thì accounts.google.com
 * KHÔNG khớp (muốn dùng cho mọi trang con, lưu URL là tên miền gốc "google.com"). Không so "tên miền gốc"
 * tự đoán vì app offline không có danh sách Public Suffix đầy đủ: đoán sai đuôi (id.vn, duckdns.org,
 * ts.net...) sẽ cho hai website khác chủ trùng nhau. Chữ giống nhau khác bảng mã (Cyrillic "о") được đổi sang
 * punycode (xn--…) nên không bao giờ trùng tên miền thật. Không chắc chắn => không khớp.
 */
object DomainMatcher {
    /**
     * Đuôi nhiều phần hay gặp (không có danh sách Public Suffix đầy đủ vì app offline): với các đuôi này,
     * tên miền gốc gồm thêm một nhãn. Gồm cả nền tảng hosting nơi mỗi tên miền con là một website khác chủ.
     */
    private val MULTI_PART_SUFFIXES = setOf(
        "com.vn", "net.vn", "org.vn", "edu.vn", "gov.vn", "info.vn", "biz.vn", "name.vn", "pro.vn", "health.vn",
        "ac.vn", "int.vn", "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "com.au", "net.au", "org.au", "edu.au",
        "co.jp", "ne.jp", "or.jp", "com.br", "com.cn", "com.sg", "com.my", "co.kr", "co.in", "co.th", "com.tw",
        "com.hk", "co.id", "com.ph", "co.nz", "com.mx", "com.ar", "com.tr", "co.za",
        "github.io", "gitlab.io", "blogspot.com", "vercel.app", "netlify.app", "pages.dev", "workers.dev",
        "web.app", "firebaseapp.com", "herokuapp.com", "azurewebsites.net", "appspot.com", "cloudfront.net",
        "s3.amazonaws.com", "glitch.me", "repl.co", "onrender.com", "fly.dev",
        "id.vn", "io.vn", "ts.net", "duckdns.org", "ddns.net", "no-ip.org", "ngrok-free.app", "ngrok.io",
        "trycloudflare.com", "r2.dev", "surge.sh", "webflow.io", "amplifyapp.com", "wixsite.com", "wordpress.com",
        "myshopify.com", "carrd.co", "notion.site", "sites.google.com", "translate.goog",
    )

    /**
     * Các URL có thể lưu cho trang [host] (để người dùng chọn): đúng tên miền này, rồi từng tên miền cha còn ít nhất
     * 2 nhãn và không phải một đuôi dùng chung (com.vn, github.io...). "m.facebook.com" → [m.facebook.com,
     * facebook.com]; "ebank.x.com.vn" → [ebank.x.com.vn, x.com.vn].
     */
    fun saveCandidates(host: String): List<String> {
        val h = hostOf(host)?.removePrefix("www.") ?: return emptyList()
        if (isIpAddress(h) || !h.contains('.')) return listOf(h)
        val labels = h.split('.')
        val out = mutableListOf(h)
        var drop = 1
        while (labels.size - drop >= 2) {
            val parent = labels.drop(drop).joinToString(".")
            if (parent in MULTI_PART_SUFFIXES) break   // không bao giờ đề nghị chính một đuôi dùng chung
            out += parent
            drop++
        }
        return out
    }

    /** Tên miền (chữ thường, punycode) của một URL hoặc tên miền trần; null nếu không đọc được. */
    fun hostOf(url: String): String? {
        var s = url.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("androidapp://", ignoreCase = true)) return null
        s = s.substringAfter("://", s)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@')
        s = if (s.startsWith('[')) s.substringBefore(']').removePrefix("[") else s.substringBefore(':')
        s = s.trimEnd('.').lowercase()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        return try {
            IDN.toASCII(s, IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun schemeOf(url: String): String? =
        url.trim().substringBefore("://", "").lowercase().ifEmpty { null }

    /** Tên miền gốc: "mail.google.com" → "google.com", "shop.tiki.com.vn" → "tiki.com.vn". */
    fun baseDomain(host: String): String {
        if (isIpAddress(host)) return host
        val labels = host.split('.')
        if (labels.size <= 2) return host
        for (take in 3 downTo 2) {
            if (labels.size >= take + 1 && labels.takeLast(take).joinToString(".") in MULTI_PART_SUFFIXES) {
                return labels.takeLast(take + 1).joinToString(".")
            }
        }
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in MULTI_PART_SUFFIXES) labels.takeLast(3).joinToString(".") else lastTwo
    }

    /**
     * Mục có [savedUrl] có được điền vào trang [requestHost] (scheme [requestScheme]) không?
     * Mục lưu cho https mà trang đang là http => KHÔNG (mật khẩu sẽ đi qua kết nối không mã hóa).
     */
    fun matches(savedUrl: String, requestHost: String, requestScheme: String?): Boolean =
        hostMatches(savedUrl, requestHost) && !isInsecureDowngrade(savedUrl, requestScheme)

    /** Chỉ phần tên miền: đúng tên miền đã lưu hoặc tên miền con của nó. */
    fun hostMatches(savedUrl: String, requestHost: String): Boolean {
        val saved = hostOf(savedUrl)?.removePrefix("www.") ?: return false
        val request = hostOf(requestHost) ?: return false
        if (isIpAddress(saved) || isIpAddress(request)) return saved == request
        if (!saved.contains('.')) return request == saved   // "localhost", "router"
        return request == saved || request.removePrefix("www.") == saved || request.endsWith(".$saved")
    }

    /** Đã lưu cho https nhưng trang đang mở bằng http. */
    fun isInsecureDowngrade(savedUrl: String, requestScheme: String?): Boolean =
        requestScheme.equals("http", ignoreCase = true) && schemeOf(savedUrl) == "https"

    private fun isIpAddress(host: String): Boolean =
        host.contains(':') || host.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } }
}
