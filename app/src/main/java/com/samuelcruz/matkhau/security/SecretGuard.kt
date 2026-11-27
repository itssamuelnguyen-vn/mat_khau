package com.samuelcruz.matkhau.security

import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.samuelcruz.matkhau.MatKhauApp

/**
 * Tùy chọn "xác thực mỗi lần hiện / copy / tự điền bí mật". Trojan Trợ năng có thể bấm thay người dùng, nhưng
 * không giả được vân tay. Xác thực xong được miễn hỏi lại [GRACE_MS] (copy username rồi mật khẩu chỉ hỏi một lần).
 * Khóa vault => hết miễn.
 */
object SecretGuard {
    private const val GRACE_MS = 30_000L

    @Volatile
    private var authenticatedAt = 0L

    fun reset() {
        authenticatedAt = 0L
    }

    /**
     * Ưu tiên vân tay / khuôn mặt (trojan không giả được). Chỉ dùng mã PIN / mật khẩu màn hình khóa khi máy không có
     * sinh trắc học (trojan ngân hàng hay ghi lại mã PIN màn hình khóa).
     */
    private fun authenticators(activity: FragmentActivity): Int {
        val bm = BiometricManager.from(activity)
        return when {
            bm.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS -> BIOMETRIC_STRONG
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> DEVICE_CREDENTIAL
            else -> BIOMETRIC_STRONG
        }
    }

    /** Máy có cách xác thực phù hợp không (để bật được tùy chọn). */
    fun isAvailable(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(authenticators(activity)) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Có cần hỏi không: người dùng bật tùy chọn, HOẶC đang có app Trợ năng đáng ngờ (tự bật bảo vệ, không cần chờ
     * người dùng nhớ ra).
     */
    fun isRequired(activity: FragmentActivity): Boolean {
        val app = activity.application as MatKhauApp
        return app.settings.requireAuthForSecrets || AccessibilityGuard.riskyServices(activity).isNotEmpty()
    }

    /**
     * Chạy [action] sau khi xác thực (nếu cần và chưa trong thời gian miễn). [force] = luôn hỏi (vd. tắt tùy chọn).
     * Vault bị khóa trong lúc hộp thoại đang mở => bỏ hành động (giá trị đã chụp trước khi khóa).
     * Máy không còn cách xác thực nào => chạy luôn, để không khóa người dùng ngoài dữ liệu của chính họ (khi đó máy
     * vốn đã không được bảo vệ).
     */
    fun run(activity: FragmentActivity, reason: String, force: Boolean = false, onDenied: () -> Unit = {}, action: () -> Unit) {
        val app = activity.application as MatKhauApp
        val now = SystemClock.elapsedRealtime()
        val inGrace = authenticatedAt != 0L && now - authenticatedAt < GRACE_MS
        if (!force && (!isRequired(activity) || inGrace)) {
            action(); return
        }
        if (!isAvailable(activity)) {
            action(); return
        }
        val lockGeneration = app.repository.currentGeneration()
        val authenticators = authenticators(activity)
        // Mã PIN / mật khẩu màn hình khóa mở một màn hình hệ thống riêng: đừng để chế độ "khóa ngay khi rời app"
        // khóa vault trong lúc đó (hết hoãn khi quay lại app).
        if (authenticators and DEVICE_CREDENTIAL != 0) app.autoLock.suspendForSystemPicker()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (app.repository.currentGeneration() != lockGeneration) { onDenied(); return }
                authenticatedAt = SystemClock.elapsedRealtime()
                action()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onDenied()
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Xác thực để tiếp tục")
            .setSubtitle(reason)
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(false)
            .apply { if (authenticators and DEVICE_CREDENTIAL == 0) setNegativeButtonText("Hủy") }
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }
}
