package com.samuelcruz.matkhau.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.samuelcruz.matkhau.ui.components.LocalUserActivity
import com.samuelcruz.matkhau.ui.screens.ChangePasswordScreen
import com.samuelcruz.matkhau.ui.screens.CreateVaultScreen
import com.samuelcruz.matkhau.ui.screens.EntryDetailScreen
import com.samuelcruz.matkhau.ui.screens.EntryEditScreen
import com.samuelcruz.matkhau.ui.screens.GeneratorScreen
import com.samuelcruz.matkhau.ui.screens.HealthScreen
import com.samuelcruz.matkhau.ui.screens.RecoveryUnlockScreen
import com.samuelcruz.matkhau.ui.screens.RestoreScreen
import com.samuelcruz.matkhau.ui.screens.SecurityScreen
import com.samuelcruz.matkhau.ui.screens.SettingsScreen
import com.samuelcruz.matkhau.ui.screens.ShowRecoveryKeyScreen
import com.samuelcruz.matkhau.ui.screens.UnlockScreen
import com.samuelcruz.matkhau.ui.screens.VaultListScreen
import com.samuelcruz.matkhau.ui.screens.WelcomeScreen

/**
 * "Router" của app. Không dùng thư viện navigation: trạng thái màn hình nằm trong ViewModel
 * (giống một stack), giúp kiểm soát chặt việc xóa màn hình chứa dữ liệu nhạy cảm khi khóa.
 */
@Composable
fun AppRoot(vm: VaultViewModel) {
    val unlocked by vm.repository.unlocked.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    // Lấy thông báo từ hàng đợi của ViewModel và hiện lần lượt.
    LaunchedEffect(vm) {
        vm.messages.collect { snackbarHost.showSnackbar(it) }
    }

    CompositionLocalProvider(LocalUserActivity provides vm::userActivity) {
    Box(Modifier.fillMaxSize()) {
        if (unlocked) {
            // Đang chạy tác vụ (lưu, đổi mật khẩu...): chặn Back để stack không bị đổi giữa chừng.
            BackHandler(enabled = vm.stack.size > 1 || vm.busyText != null) { if (vm.busyText == null) vm.back() }
            when (val screen = vm.stack.lastOrNull() ?: Screen.VaultList) {
                Screen.VaultList -> VaultListScreen(vm)
                is Screen.Detail -> EntryDetailScreen(vm, screen.id)
                is Screen.Edit -> EntryEditScreen(vm, screen.id, screen.type)
                Screen.Generator -> GeneratorScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
                Screen.Security -> SecurityScreen(vm)
                Screen.Health -> HealthScreen(vm)
                is Screen.ChangePassword -> ChangePasswordScreen(vm, screen.forced)
                is Screen.ShowRecoveryKey -> ShowRecoveryKeyScreen(vm, screen.key, screen.firstTime, screen.replacedUnconfirmed)
                Screen.Restore -> RestoreScreen(vm)
            }
        } else {
            val loaded by vm.repository.initialized.collectAsState()
            val initialized = loaded
            // initialized == null: DB chưa đọc xong (vài ms lúc mở app), để màn hình trống.
            if (initialized != null) {
                BackHandler(enabled = vm.lockedScreen != LockedScreen.Unlock && vm.lockedScreen != LockedScreen.Welcome) {
                    vm.showLocked(if (initialized) LockedScreen.Unlock else LockedScreen.Welcome)
                }
                when (vm.lockedScreen) {
                    LockedScreen.Welcome, LockedScreen.Unlock -> if (initialized) UnlockScreen(vm) else WelcomeScreen(vm)
                    LockedScreen.Create -> CreateVaultScreen(vm)
                    LockedScreen.RecoveryUnlock -> RecoveryUnlockScreen(vm)
                    LockedScreen.Restore -> RestoreScreen(vm)
                }
            }
        }

        // Lớp phủ khi đang chạy tác vụ nặng (Argon2id mất ~1 giây)
        val busy = vm.busyText
        if (busy != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    if (busy.isNotEmpty()) Text(busy, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
        )
    }
    }
}
