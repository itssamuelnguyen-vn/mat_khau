package com.samuelcruz.matkhau.security

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.samuelcruz.matkhau.MatKhauApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class CheckStatus { OK, WARNING, INFO }

class AuditCheck(val title: String, val detail: String, val status: CheckStatus)

/**
 * Màn hình "Security & Privacy" (spec mục 6). Mọi dòng đều được KIỂM TRA THẬT lúc chạy,
 * không phải dấu ✓ viết cứng.
 *
 * Hàm suspend: phần đọc DB / Keystore chạy trên Dispatchers.IO (trong repository),
 * phần đọc Activity (cờ window) chạy trên main thread của coroutine gọi nó.
 */
object SecurityAudit {

    suspend fun run(activity: Activity): List<AuditCheck> {
        val app = activity.application as MatKhauApp
        val pm = activity.packageManager
        val checks = mutableListOf<AuditCheck>()

        val requested = pm.getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toList().orEmpty()
        val networkPerms = requested.filter {
            it == Manifest.permission.INTERNET ||
                it == Manifest.permission.ACCESS_NETWORK_STATE ||
                it == Manifest.permission.ACCESS_WIFI_STATE
        }
        checks += AuditCheck(
            "Không có quyền Internet",
            if (networkPerms.isNotEmpty()) {
                "APK đang khai báo: ${networkPerms.joinToString { it.substringAfterLast('.') }}!"
            } else {
                "Hệ điều hành chặn mọi kết nối mạng của app. Quyền đang khai báo: " +
                    requested.joinToString { it.substringAfterLast('.') }.ifEmpty { "không có" }
            },
            if (networkPerms.isNotEmpty()) CheckStatus.WARNING else CheckStatus.OK,
        )

        val cameraGranted = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        checks += AuditCheck(
            "Quyền Camera",
            if (cameraGranted) {
                "Đã cấp — chỉ dùng khi bạn bấm quét mã QR 2FA; ảnh chỉ xử lý trong RAM, không lưu. " +
                    "Có thể thu hồi trong Cài đặt hệ thống → Ứng dụng → Mật Khẩu → Quyền."
            } else {
                "Chưa cấp. Chỉ được hỏi khi bạn bấm quét mã QR 2FA."
            },
            if (cameraGranted) CheckStatus.INFO else CheckStatus.OK,
        )

        val allowBackup = (activity.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
        checks += AuditCheck(
            "Không sao lưu lên cloud",
            if (allowBackup) "allowBackup đang BẬT" else "Đã tắt Google Backup và chuyển dữ liệu tự động",
            if (allowBackup) CheckStatus.WARNING else CheckStatus.OK,
        )

        val header = app.repository.headerInfo()
        checks += AuditCheck(
            "Vault được mã hóa",
            if (header != null) {
                "AES-256-GCM cho từng mục. Argon2id: ${header.kdf.memoryKiB / 1024} MiB, ${header.kdf.iterations} vòng, ${header.kdf.parallelism} luồng"
            } else {
                "Chưa có vault"
            },
            if (header != null) CheckStatus.OK else CheckStatus.INFO,
        )

        // Đọc thật cấu trúc DB: chỉ được có 2 bảng với đúng các cột/khóa đã biết, không có cột plaintext.
        val layout = app.repository.storageLayout()
        val knownMeta = setOf("vault_header", "biometric_wrapped_vek")
        val metaKeys = layout.metaKeys
        val metaCols = layout.metaColumns
        val itemCols = layout.itemColumns
        val layoutOk = layout.tables == listOf("items", "meta") && metaKeys.all { it in knownMeta } &&
            metaCols == listOf("k", "v") && itemCols == listOf("id", "fmt", "nonce", "ct")
        checks += AuditCheck(
            "Mật khẩu chính không được lưu, DB không có plaintext",
            if (layoutOk) {
                "DB chỉ có meta(k, v) = {${metaKeys.joinToString()}} và items(id, fmt, nonce, ct). " +
                    "Chỉ lưu salt và VEK đã bọc, không có hash mật khẩu nào để dò ngược."
            } else {
                "Cấu trúc DB khác dự kiến: bảng ${layout.tables}, meta$metaCols = $metaKeys, items$itemCols"
            },
            if (layoutOk) CheckStatus.OK else CheckStatus.WARNING,
        )

        checks += AuditCheck(
            "Recovery Key",
            if (header?.recoverySlot != null) "Đã thiết lập — hãy chắc chắn bạn cất bản giấy an toàn" else "Chưa có Recovery Key",
            if (header?.recoverySlot != null) CheckStatus.OK else CheckStatus.WARNING,
        )

        val bioEnabled = app.repository.biometricBlob() != null && withContext(Dispatchers.IO) { app.biometric.hasKey() }
        val hw = if (bioEnabled) withContext(Dispatchers.IO) { app.biometric.isHardwareBacked() } else null
        checks += AuditCheck(
            "Mở khóa bằng vân tay (Android Keystore)",
            when {
                !bioEnabled -> "Đang tắt"
                hw == true -> "Bật — khóa nằm trong phần cứng bảo mật (TEE/StrongBox)"
                else -> "Bật — nhưng không xác nhận được khóa nằm trong phần cứng bảo mật"
            },
            when {
                !bioEnabled -> CheckStatus.INFO
                hw == true -> CheckStatus.OK
                else -> CheckStatus.WARNING
            },
        )

        val secure = (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        checks += AuditCheck(
            "Chặn chụp màn hình",
            if (secure) "FLAG_SECURE đang bật: chặn screenshot, quay màn hình, ảnh xem trước đa nhiệm" else "Đang TẮT",
            if (secure) CheckStatus.OK else CheckStatus.WARNING,
        )

        checks += AuditCheck(
            "Tự xóa clipboard",
            "Sau ${AppSettings.clipboardLabel(app.settings.clipboardSeconds)}",
            CheckStatus.OK,
        )

        checks += AuditCheck(
            "Tự khóa vault",
            "${AppSettings.autoLockLabel(app.settings.autoLockMillis)}; luôn khóa khi tắt màn hình",
            CheckStatus.OK,
        )

        val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        checks += AuditCheck(
            "Khóa màn hình điện thoại",
            if (keyguard.isDeviceSecure) "Đã đặt PIN/mật khẩu/vân tay cho máy" else "Máy chưa có khóa màn hình — rất nên bật",
            if (keyguard.isDeviceSecure) CheckStatus.OK else CheckStatus.WARNING,
        )

        val debuggable = (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        checks += AuditCheck(
            "Loại bản build",
            if (debuggable) "Bản DEBUG: tiện thử nghiệm, nhưng nên dùng bản release cho dữ liệu thật" else "Bản release",
            if (debuggable) CheckStatus.WARNING else CheckStatus.OK,
        )

        checks += AuditCheck(
            "Không analytics, quảng cáo, đồng bộ cloud",
            if (networkPerms.isEmpty()) {
                "Không có quyền mạng nên không thư viện nào gửi được dữ liệu ra ngoài; không server, không tài khoản"
            } else {
                "APK có quyền mạng — không đảm bảo được điều này"
            },
            if (networkPerms.isEmpty()) CheckStatus.OK else CheckStatus.WARNING,
        )
        return checks
    }
}
