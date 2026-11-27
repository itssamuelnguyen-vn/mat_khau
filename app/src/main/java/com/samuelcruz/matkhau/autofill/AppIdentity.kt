package com.samuelcruz.matkhau.autofill

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Danh tính của app đang xin tự động điền: tên hiển thị, SHA-256 chứng chỉ ký và nguồn cài. Chứng chỉ do hệ thống
 * xác minh lúc cài app, app giả mạo cùng tên gói (cài ngoài) không thể có cùng chứng chỉ.
 * Cần quyền QUERY_ALL_PACKAGES (Android 11+) để đọc thông tin của app bất kỳ.
 */
object AppIdentity {
    /** Cửa hàng đã biết; app cài từ nơi khác (file APK, nguồn lạ) bị cảnh báo và không được gợi ý mục. */
    private val STORES = setOf(
        "com.android.vending", "com.google.android.feedback", "com.sec.android.app.samsungapps",
        "com.huawei.appmarket", "com.xiaomi.market", "com.xiaomi.mipicks", "com.oppo.market", "com.heytap.market",
        "com.bbk.appstore", "com.amazon.venezia", "org.fdroid.fdroid", "com.aurora.store",
    )

    /** [fromStore]: cài bởi một cửa hàng đã biết VÀ cửa hàng đó là app hệ thống (app thường mạo danh tên gói thì không tính). */
    class Identity(val current: String?, val all: Set<String>, val installer: String?, val fromStore: Boolean)

    fun of(context: Context, packageName: String): Identity {
        val (current, all) = certs(context, packageName)
        val installer = installer(context, packageName)
        return Identity(current, all, installer, installer != null && installer in STORES && isSystemApp(context, installer))
    }

    private fun isSystemApp(context: Context, packageName: String): Boolean = try {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } catch (e: Exception) {
        false
    }

    /**
     * Chứng chỉ HIỆN TẠI (nhiều người ký => nối các SHA-256 đã sắp xếp) và mọi chứng chỉ trong lịch sử xoay vòng
     * khóa ký (để app đổi khóa ký hợp lệ vẫn khớp liên kết cũ). (null, rỗng) nếu không đọc được.
     */
    private fun certs(context: Context, packageName: String): Pair<String?, Set<String>> = try {
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo
        when {
            signing == null -> null to emptySet()
            signing.hasMultipleSigners() -> {
                val joined = signing.apkContentsSigners.map { sha256(it.toByteArray()) }.sorted().joinToString(",")
                joined to setOf(joined)
            }
            else -> {
                // Lịch sử xoay vòng: phần tử cuối là chứng chỉ đang dùng.
                val history = signing.signingCertificateHistory.map { sha256(it.toByteArray()) }
                history.lastOrNull() to history.toSet()
            }
        }
    } catch (e: Exception) {
        null to emptySet()
    }

    private fun installer(context: Context, packageName: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(packageName)
        }
    } catch (e: Exception) {
        null
    }

    fun installerLabel(context: Context, installer: String?): String = when (installer) {
        null -> "không rõ (cài từ file APK?)"
        "com.android.vending" -> "Google Play"
        else -> label(context, installer)
    }

    fun label(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
