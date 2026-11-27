package com.samuelcruz.matkhau.autofill

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * Danh tính của app đang xin tự động điền: tên hiển thị và SHA-256 chứng chỉ ký. Chứng chỉ do hệ thống
 * xác minh lúc cài app, app giả mạo cùng tên gói (cài ngoài) không thể có cùng chứng chỉ.
 * Cần quyền QUERY_ALL_PACKAGES (Android 11+) để đọc thông tin của app bất kỳ.
 */
object AppIdentity {
    /** SHA-256 (hex) chứng chỉ ký HIỆN TẠI; nhiều người ký => nối các SHA-256 đã sắp xếp. null nếu không đọc được. */
    fun certSha256(context: Context, packageName: String): String? = try {
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo ?: return null
        if (signing.hasMultipleSigners()) {
            signing.apkContentsSigners.map { sha256(it.toByteArray()) }.sorted().joinToString(",")
        } else {
            // Lịch sử xoay vòng chứng chỉ: phần tử cuối là chứng chỉ đang dùng.
            signing.signingCertificateHistory.lastOrNull()?.let { sha256(it.toByteArray()) }
        }
    } catch (e: Exception) {
        null
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
