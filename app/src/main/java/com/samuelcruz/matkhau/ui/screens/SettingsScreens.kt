package com.samuelcruz.matkhau.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.samuelcruz.matkhau.core.crypto.KdfParams
import com.samuelcruz.matkhau.security.AppSettings
import com.samuelcruz.matkhau.security.CheckStatus
import com.samuelcruz.matkhau.security.SecurityAudit
import com.samuelcruz.matkhau.ui.LockedScreen
import com.samuelcruz.matkhau.ui.Screen
import com.samuelcruz.matkhau.ui.VaultViewModel
import com.samuelcruz.matkhau.ui.components.AppScaffold
import com.samuelcruz.matkhau.ui.components.TrackedTextField
import com.samuelcruz.matkhau.ui.components.ErrorText
import com.samuelcruz.matkhau.ui.components.Hint
import com.samuelcruz.matkhau.ui.components.LocalUserActivity
import com.samuelcruz.matkhau.ui.components.trackUserActivity
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.samuelcruz.matkhau.ui.components.SecretField
import com.samuelcruz.matkhau.ui.components.SectionTitle
import com.samuelcruz.matkhau.ui.components.StrengthMeter
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(vm: VaultViewModel) {
    val context = LocalContext.current
    val activity = context.findFragmentActivity()
    var autoLockDialog by remember { mutableStateOf(false) }
    var clipboardDialog by remember { mutableStateOf(false) }
    var confirmRegenerate by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        vm.exportBackup(context, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vm.openBackupFile(context, uri)
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vm.openCsvFile(context, uri)
    }

    AppScaffold(title = "Cài đặt", onBack = { vm.back() }) {
        SectionTitle("Khóa")
        SettingRow("Tự khóa sau", AppSettings.autoLockLabel(vm.autoLockMillis)) { autoLockDialog = true }
        SettingRow("Khóa ngay", "Xóa khóa giải mã khỏi bộ nhớ") { vm.lock() }
        Hint("Khóa khẩn cấp từ bất kỳ đâu: kéo thanh thông báo xuống → Sửa (✏) → thêm ô \"Khóa Mật Khẩu\" vào Cài đặt nhanh, hoặc giữ biểu tượng app → \"Khóa ngay\".")

        val bioSupported = vm.canUseBiometrics()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Mở khóa bằng vân tay / khuôn mặt")
                Hint(if (bioSupported) "Khóa vault được bọc bởi Android Keystore" else "Máy chưa đăng ký sinh trắc học loại mạnh")
            }
            Switch(
                checked = vm.biometricEnabled,
                // Đang bật thì luôn tắt được, kể cả khi máy tạm không dùng được sinh trắc học
                enabled = activity != null && (bioSupported || vm.biometricEnabled),
                onCheckedChange = { on ->
                    if (on && activity != null) vm.enableBiometric(activity) else vm.disableBiometric()
                },
            )
        }

        SectionTitle("Tự động điền")
        // Đọc lại mỗi lần quay về màn hình (người dùng vừa bật / tắt trong Cài đặt hệ thống).
        var autofillOn by remember { mutableStateOf(false) }
        LifecycleResumeEffect(Unit) {
            autofillOn = context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true
            onPauseOrDispose { }
        }
        SettingRow(
            "Dịch vụ tự động điền",
            if (autofillOn) "Đang dùng Mật Khẩu. Bấm để đổi trong Cài đặt hệ thống" else "Đang tắt. Bấm để chọn Mật Khẩu làm dịch vụ tự động điền",
        ) {
            vm.suspendAutoLock()
            val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(intent) }.onFailure {
                runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
        }
        Hint(
            "Chỉ đề xuất mục khớp đúng tên miền (trang web) hoặc đúng ứng dụng đã liên kết, kiểm tra cả chứng chỉ ký. " +
                "Chrome: Cài đặt của Chrome → Mật khẩu và tự động điền → chọn \"Tự động điền bằng dịch vụ khác\".",
        )

        // Trình duyệt đã xác nhận cho tự động điền (ghim chứng chỉ): xem và bỏ tin.
        var trusted by remember { mutableStateOf(vm.settings.trustedBrowsers()) }
        LifecycleResumeEffect(Unit) {
            trusted = vm.settings.trustedBrowsers()
            onPauseOrDispose { }
        }
        trusted.forEach { pkg ->
            ListItem(
                headlineContent = { Text("Tin: " + com.samuelcruz.matkhau.autofill.AppIdentity.label(context, pkg)) },
                supportingContent = { Text(pkg) },
                trailingContent = {
                    TextButton(onClick = { vm.settings.untrustBrowser(pkg); trusted = vm.settings.trustedBrowsers() }) { Text("Bỏ tin") }
                },
            )
        }

        SectionTitle("Clipboard")
        SettingRow("Tự xóa clipboard sau", AppSettings.clipboardLabel(vm.clipboardSeconds)) { clipboardDialog = true }

        SectionTitle("Mật khẩu chính & khôi phục")
        SettingRow("Đổi mật khẩu chính", "Không cần mã hóa lại dữ liệu") { vm.navigate(Screen.ChangePassword(forced = false)) }
        SettingRow("Tạo Recovery Key mới", "Key cũ sẽ bị vô hiệu ngay") { confirmRegenerate = true }

        SectionTitle("Backup")
        SettingRow("Xuất backup mã hóa", "Lưu file .lvbackup ra bộ nhớ máy, USB, máy tính…") {
            vm.suspendAutoLock()
            exportLauncher.launch(vm.backupFileName())
        }
        SettingRow("Khôi phục từ backup", "Thay thế toàn bộ vault hiện tại") {
            vm.suspendAutoLock()
            importLauncher.launch(arrayOf("*/*"))
        }
        Hint("Backup mở được bằng mật khẩu chính (hoặc Recovery Key) TẠI THỜI ĐIỂM xuất file. Hãy xuất backup định kỳ.")

        SectionTitle("Chuyển từ app khác")
        SettingRow("Nhập từ file CSV", "Bitwarden, Chrome / Google, Firefox, KeePass / KeePassXC…") {
            vm.suspendAutoLock()
            csvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "*/*"))
        }

        SectionTitle("Thông tin")
        SettingRow("Sức khỏe mật khẩu", "Mật khẩu yếu, dùng chung, lâu chưa đổi, thiếu 2FA") { vm.navigate(Screen.Health) }
        SettingRow("Bảo mật & quyền riêng tư", "Kiểm tra trạng thái bảo mật của app") { vm.navigate(Screen.Security) }
    }

    if (autoLockDialog) {
        ChoiceDialog(
            title = "Tự khóa sau",
            options = AppSettings.AUTO_LOCK_OPTIONS,
            selected = vm.autoLockMillis,
            onSelect = { vm.changeAutoLock(it); autoLockDialog = false },
            onDismiss = { autoLockDialog = false },
        )
    }
    if (clipboardDialog) {
        ChoiceDialog(
            title = "Tự xóa clipboard sau",
            options = AppSettings.CLIPBOARD_OPTIONS,
            selected = vm.clipboardSeconds,
            onSelect = { vm.changeClipboardSeconds(it); clipboardDialog = false },
            onDismiss = { clipboardDialog = false },
        )
    }
    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("Tạo Recovery Key mới?") },
            text = { Text("Recovery Key cũ sẽ không dùng được nữa với vault này. (Các file backup cũ vẫn mở được bằng key cũ.)") },
            confirmButton = { TextButton(onClick = { confirmRegenerate = false; vm.regenerateRecoveryKey() }) { Text("Tạo mới") } },
            dismissButton = { TextButton(onClick = { confirmRegenerate = false }) { Text("Hủy") } },
        )
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.trackUserActivity(LocalUserActivity.current)) {
                options.forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = value == selected, onClick = { onSelect(value) }),
                    ) {
                        RadioButton(selected = value == selected, onClick = { onSelect(value) })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
    )
}

@Composable
fun ChangePasswordScreen(vm: VaultViewModel, forced: Boolean) {
    var current by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = newPassword.length >= MIN_MASTER_PASSWORD_LENGTH && newPassword == confirm && (forced || current.isNotEmpty())

    AppScaffold(
        title = if (forced) "Đặt mật khẩu chính mới" else "Đổi mật khẩu chính",
        onBack = if (forced) null else fun() { vm.back() },
    ) {
        if (forced) {
            Hint("Bạn vừa mở vault bằng Recovery Key. Hãy đặt mật khẩu chính mới trước khi tiếp tục.")
        } else {
            SecretField(current, { current = it }, "Mật khẩu chính hiện tại")
        }
        SecretField(newPassword, { newPassword = it }, "Mật khẩu chính mới (tối thiểu $MIN_MASTER_PASSWORD_LENGTH ký tự)")
        StrengthMeter(newPassword)
        SecretField(confirm, { confirm = it }, "Nhập lại mật khẩu mới")
        if (confirm.isNotEmpty() && confirm != newPassword) ErrorText("Hai mật khẩu không khớp")
        ErrorText(vm.formError)
        Hint("Chỉ VEK được bọc lại bằng mật khẩu mới; dữ liệu các mục không đổi. File backup cũ vẫn dùng mật khẩu cũ.")
        Button(
            onClick = { vm.changePassword(if (forced) null else current, newPassword) },
            enabled = valid && vm.busyText == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Lưu mật khẩu mới") }
    }
}

@Composable
fun SecurityScreen(vm: VaultViewModel) {
    val activity = LocalContext.current as? Activity ?: LocalContext.current.findFragmentActivity()
    // Kiểm tra lại mỗi lần mở màn hình / đổi cài đặt; phần đọc DB và Keystore chạy ngoài main thread.
    val checks by produceState(emptyList<com.samuelcruz.matkhau.security.AuditCheck>(), vm.biometricEnabled, vm.autoLockMillis, vm.clipboardSeconds) {
        value = activity?.let { SecurityAudit.run(it) }.orEmpty()
    }
    AppScaffold(title = "Bảo mật & quyền riêng tư", onBack = { vm.back() }) {
        Hint("Mỗi dòng dưới đây được kiểm tra trực tiếp trên máy lúc bạn mở màn hình này.")
        checks.forEach { c ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when (c.status) {
                        CheckStatus.OK -> Icons.Filled.CheckCircle to Color(0xFF2E7D32)
                        CheckStatus.WARNING -> Icons.Filled.Warning to Color(0xFFF9A825)
                        CheckStatus.INFO -> Icons.Filled.Info to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(icon, contentDescription = null, tint = tint)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(c.title, style = MaterialTheme.typography.titleSmall)
                        Text(c.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        HorizontalDivider()
        Hint("Mặc định Argon2id: ${KdfParams.DEFAULT.memoryKiB / 1024} MiB · ${KdfParams.DEFAULT.iterations} vòng. Mã hóa: AES-256-GCM, nonce ngẫu nhiên 96-bit.")
    }
}

/** Dùng cho cả khi vault đang khóa (từ màn hình Welcome) lẫn đang mở (từ Cài đặt). */
@Composable
fun RestoreScreen(vm: VaultViewModel) {
    val parsed = vm.pendingBackup
    val unlocked = vm.repository.isUnlocked()
    val onBack: () -> Unit = {
        if (unlocked) vm.back() else vm.showLocked(if (vm.repository.initialized.value == true) LockedScreen.Unlock else LockedScreen.Welcome)
    }

    AppScaffold(title = "Khôi phục backup", onBack = onBack) {
        if (parsed == null) {
            Text("Chưa chọn file backup.")
        } else {
            RestoreBody(vm, parsed, onBack)
        }
    }
}

@Composable
private fun RestoreBody(vm: VaultViewModel, parsed: com.samuelcruz.matkhau.core.vault.BackupFormat.Parsed, onBack: () -> Unit) {
    val decrypted = vm.decryptedBackup
    var useRecovery by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    val unlocked = vm.repository.isUnlocked()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("✓ Định dạng file hợp lệ", style = MaterialTheme.typography.titleSmall)
                Hint("Argon2id ${parsed.header.kdf.memoryKiB / 1024} MiB · ${parsed.header.kdf.iterations} vòng · Recovery Key: ${if (parsed.header.recoverySlot != null) "có" else "không"}")
            }
        }

        if (decrypted == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !useRecovery, onClick = { useRecovery = false; secret = "" }, label = { Text("Mật khẩu") })
                FilterChip(selected = useRecovery, onClick = { useRecovery = true; secret = "" }, label = { Text("Recovery Key") })
            }
            if (useRecovery) {
                TrackedTextField(
                    secret, { secret = it.uppercase() },
                    label = { Text("Recovery Key của bản backup") },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    // Kiểu "password": bàn phím không học / gợi ý Recovery Key
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                SecretField(secret, { secret = it }, "Mật khẩu chính lúc xuất backup")
            }
            ErrorText(vm.formError)
            Button(
                onClick = { vm.decryptBackup(secret, useRecovery) },
                enabled = secret.isNotEmpty() && vm.busyText == null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Giải mã & kiểm tra") }
        } else {
            val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("✓ Giải mã thành công, dữ liệu toàn vẹn", style = MaterialTheme.typography.titleSmall)
                    Text("${decrypted.entries.size} mục · xuất lúc ${fmt.format(Date(decrypted.exportedAt))}")
                }
            }
            val currentCount = vm.repository.entries.value.size
            Text(
                if (vm.repository.initialized.value == true) {
                    "Khôi phục sẽ THAY THẾ toàn bộ vault hiện tại" + (if (unlocked) " ($currentCount mục)" else "") +
                        ". Mật khẩu chính sẽ là mật khẩu của bản backup. Vân tay cần bật lại."
                } else {
                    "Vault sẽ được tạo từ bản backup. Mật khẩu chính là mật khẩu của bản backup."
                },
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = { vm.confirmRestore() }, enabled = vm.busyText == null, modifier = Modifier.fillMaxWidth()) {
                Text("Khôi phục")
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Hủy") }
        }
    }
}
