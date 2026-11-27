package com.samuelcruz.matkhau

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.samuelcruz.matkhau.ui.AppRoot
import com.samuelcruz.matkhau.ui.VaultViewModel
import com.samuelcruz.matkhau.ui.theme.MatKhauTheme

/**
 * FragmentActivity (thay vì ComponentActivity) vì BiometricPrompt cần nó.
 */
class MainActivity : FragmentActivity() {
    private val vm: VaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Chặn chụp màn hình, quay màn hình, và ảnh xem trước trong danh sách app gần đây (spec 2.17).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        // Chống tapjacking: app khác không phủ cửa sổ nổi lên màn hình của vault (Android 12+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MatKhauTheme {
                AppRoot(vm)
            }
        }
    }

    /** Activity không bị tạo lại khi đổi chế độ sáng/tối (configChanges), nên tự cập nhật màu icon thanh hệ thống. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        (application as MatKhauApp).autoLock.onUserInteraction()
    }
}
