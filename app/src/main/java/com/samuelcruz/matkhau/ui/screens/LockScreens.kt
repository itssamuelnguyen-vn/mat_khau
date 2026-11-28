package com.samuelcruz.matkhau.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import com.samuelcruz.matkhau.ui.components.SensitiveWindow
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.samuelcruz.matkhau.ui.LockedScreen
import com.samuelcruz.matkhau.ui.VaultViewModel
import com.samuelcruz.matkhau.ui.components.AppScaffold
import com.samuelcruz.matkhau.ui.components.TrackedTextField
import com.samuelcruz.matkhau.ui.components.ErrorText
import com.samuelcruz.matkhau.ui.components.Hint
import com.samuelcruz.matkhau.ui.components.SecretField
import com.samuelcruz.matkhau.ui.components.StrengthMeter
import com.samuelcruz.matkhau.ui.components.sensitive
import com.samuelcruz.matkhau.ui.components.RecoveryKeyFont
import com.samuelcruz.matkhau.ui.components.recoveryKeyText
import com.samuelcruz.matkhau.security.AccessibilityGuard
import androidx.lifecycle.compose.LifecycleResumeEffect

const val MIN_MASTER_PASSWORD_LENGTH = 12

/**
 * Thời gian còn phải chờ sau nhiều lần nhập sai (đếm ngược mỗi giây). Đọc lại sau mỗi lần thử (formError đổi) và
 * khi quay lại màn hình.
 */
@Composable
private fun rememberUnlockWait(vm: VaultViewModel): Long {
    var wait by remember { mutableLongStateOf(vm.unlockWaitMs()) }
    LaunchedEffect(vm.formError, vm.busyText) {
        wait = vm.unlockWaitMs()
        while (wait > 0) {
            kotlinx.coroutines.delay(1000)
            wait = vm.unlockWaitMs()
        }
    }
    return wait
}

@Composable
private fun UnlockWaitNotice(vm: VaultViewModel, wait: Long) {
    if (vm.busyText != null) return   // đang kiểm tra: bộ đếm đã tính trước lần này, chưa biết đúng hay sai
    if (wait > 0) {
        val s = (wait + 999) / 1000
        ErrorText("Đã nhập sai ${vm.unlockFailures} lần. Thử lại sau %d:%02d".format(s / 60, s % 60))
    } else if (vm.unlockFailures >= 2) {
        Hint("Đã nhập sai ${vm.unlockFailures} lần. Từ lần sai thứ ${com.samuelcruz.matkhau.core.vault.UnlockThrottle.FREE_ATTEMPTS + 1} phải chờ lâu dần (vân tay không bị giới hạn).")
    }
}

@Composable
private fun AppLogo() {
    Icon(
        Icons.Filled.Lock, contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(64.dp),
    )
}

@Composable
fun WelcomeScreen(vm: VaultViewModel) {
    val context = LocalContext.current
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        vm.openBackupFile(context, uri)
    }
    AppScaffold(title = "Mật Khẩu") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { AppLogo() }
        val problem by vm.repository.loadProblem.collectAsState()
        problem?.let { ErrorText("⚠ $it") }
        Text("Trình quản lý mật khẩu chạy hoàn toàn offline", style = MaterialTheme.typography.titleMedium)
        Hint("Không server, không tài khoản, không Internet. Dữ liệu được mã hóa trên máy này bằng mật khẩu chính của bạn.")
        Button(onClick = { vm.showLocked(LockedScreen.Create) }, modifier = Modifier.fillMaxWidth()) {
            Text("Tạo vault mới")
        }
        OutlinedButton(
            onClick = {
                vm.suspendAutoLock()
                openLauncher.launch(arrayOf("*/*"))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Khôi phục từ file backup") }
    }
}

@Composable
fun CreateVaultScreen(vm: VaultViewModel) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var understood by remember { mutableStateOf(false) }
    val tooShort = password.length < MIN_MASTER_PASSWORD_LENGTH
    val mismatch = confirm.isNotEmpty() && confirm != password

    AppScaffold(title = "Tạo mật khẩu chính", onBack = { vm.showLocked(LockedScreen.Welcome) }) {
        Hint("Mật khẩu chính là chìa khóa duy nhất của vault. App không lưu nó ở đâu cả, nên không ai (kể cả app) lấy lại được nếu bạn quên.")
        Hint("Gợi ý: dùng cụm từ dài, dễ nhớ với bạn nhưng khó đoán, ví dụ 4–6 từ ngẫu nhiên nối nhau.")
        SecretField(password, { password = it }, "Mật khẩu chính (tối thiểu $MIN_MASTER_PASSWORD_LENGTH ký tự)")
        StrengthMeter(password)
        SecretField(confirm, { confirm = it }, "Nhập lại mật khẩu chính")
        if (mismatch) ErrorText("Hai mật khẩu không khớp")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = understood, onCheckedChange = { understood = it })
            Text("Tôi hiểu: quên mật khẩu chính mà mất Recovery Key thì dữ liệu mất vĩnh viễn.")
        }
        ErrorText(vm.formError)
        Button(
            onClick = { vm.createVault(password) },
            enabled = !tooShort && password == confirm && understood && vm.busyText == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Tạo vault") }
    }
}

@Composable
fun UnlockScreen(vm: VaultViewModel) {
    val activity = LocalContext.current.findFragmentActivity()
    var password by remember { mutableStateOf("") }
    val bioAvailable = vm.biometricEnabled && vm.canUseBiometrics()

    // Tự bật hộp thoại vân tay một lần khi vào màn hình (hoặc ngay khi đọc xong trạng thái vân tay từ DB)
    LaunchedEffect(bioAvailable) {
        when {
            // Vừa bấm "Khóa ngay": không bật ngay hộp thoại vân tay (người dùng muốn khóa, không muốn mở)
            vm.skipNextBiometricPrompt -> vm.skipNextBiometricPrompt = false
            bioAvailable && activity != null -> vm.unlockWithBiometric(activity)
        }
    }

    AppScaffold(title = "Mật Khẩu") {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            AppLogo()
            Text("Vault đang khóa", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        }
        // Cảnh báo ngay nơi gõ mật khẩu chính: app Trợ năng lạ / bàn phím lạ.
        val context = LocalContext.current
        var risky by remember { mutableStateOf(AccessibilityGuard.riskyServices(context)) }
        var keyboard by remember { mutableStateOf(AccessibilityGuard.keyboard(context)) }
        LifecycleResumeEffect(Unit) {   // đọc lại khi quay về từ Cài đặt máy
            risky = AccessibilityGuard.riskyServices(context)
            keyboard = AccessibilityGuard.keyboard(context)
            onPauseOrDispose { }
        }
        if (risky.isNotEmpty()) {
            // Ghi cả tên gói: trojan có thể tự đặt tên hiển thị là "TalkBack".
            ErrorText("⚠ App đang có quyền Trợ năng: ${risky.joinToString { "${it.label} (${it.packageName})" }}. Nếu không phải bạn bật, đừng nhập mật khẩu chính; hãy tắt nó trong Cài đặt → Trợ năng.")
        }
        keyboard?.takeIf { !it.trusted }?.let { kb ->
            ErrorText("⚠ Bàn phím \"${kb.label}\" (${kb.packageName}) không phải bàn phím hệ thống: nó thấy được mật khẩu bạn gõ. Nên mở bằng vân tay.")
        }
        SecretField(password, { password = it }, "Mật khẩu chính")
        val wait = rememberUnlockWait(vm)
        if (wait <= 0) ErrorText(vm.formError)
        UnlockWaitNotice(vm, wait)
        Button(
            onClick = { vm.unlock(password); password = "" },
            enabled = password.isNotEmpty() && vm.busyText == null && wait <= 0,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Mở khóa") }
        if (bioAvailable && activity != null) {
            OutlinedButton(onClick = { vm.unlockWithBiometric(activity) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null)
                Text("  Mở bằng vân tay")
            }
        }
        TextButton(onClick = { vm.showLocked(LockedScreen.RecoveryUnlock) }, modifier = Modifier.fillMaxWidth()) {
            Text("Quên mật khẩu? Dùng Recovery Key")
        }
    }
}

@Composable
fun RecoveryUnlockScreen(vm: VaultViewModel) {
    var key by remember { mutableStateOf("") }
    AppScaffold(title = "Mở bằng Recovery Key", onBack = { vm.showLocked(LockedScreen.Unlock) }) {
        Hint("Nhập Recovery Key 32 ký tự bạn đã ghi lại khi tạo vault (có hoặc không có dấu gạch đều được). Key chỉ gồm A–Z và 2–7; gõ nhầm 0 / 1 / 8 thì app tự hiểu là O / I / B. Sau khi mở, bạn sẽ đặt mật khẩu chính mới.")
        TrackedTextField(
            value = key,
            onValueChange = { key = it.uppercase() },
            label = { Text("XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX") },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = RecoveryKeyFont),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        val wait = rememberUnlockWait(vm)
        if (wait <= 0) ErrorText(vm.formError)
        UnlockWaitNotice(vm, wait)
        Button(
            onClick = { vm.unlockWithRecoveryKey(key) },
            enabled = key.count { it.isLetterOrDigit() } == 32 && vm.busyText == null && wait <= 0,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Mở khóa") }
    }
}

/**
 * Hiển thị Recovery Key MỘT lần. Không có nút copy (clipboard có lịch sử / đồng bộ mây): chép tay hoặc in ra giấy,
 * rồi gõ lại 2 nhóm ngẫu nhiên để chắc là đã chép đúng.
 */
@Composable
fun ShowRecoveryKeyScreen(vm: VaultViewModel, recoveryKey: String, firstTime: Boolean, replacedUnconfirmed: Boolean) {
    var saved by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var confirmPrint by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Hai nhóm ngẫu nhiên (trong 8) để kiểm tra lại, cố định trong suốt màn hình này.
    val checkGroups = remember(recoveryKey) { (0 until 8).shuffled(java.security.SecureRandom()).take(2).sorted() }
    // Chép tay mất thời gian: giữ màn hình sáng và tạm dừng hẹn giờ tự khóa khi không thao tác.
    // Tắt màn hình hay rời app thì vẫn khóa; lần mở khóa sau app sẽ tạo key mới (recoveryKeyPending).
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        vm.pauseInactivityLock(true)
        onDispose {
            view.keepScreenOn = false
            vm.pauseInactivityLock(false)
        }
    }

    if (verifying) {
        VerifyRecoveryKey(recoveryKey, checkGroups, onBack = { verifying = false }, onVerified = { vm.finishRecoveryKeyScreen() })
        return
    }

    AppScaffold(title = "Recovery Key") {
        Text(
            when {
                firstTime -> "Vault đã được tạo. Đây là Recovery Key của bạn:"
                replacedUnconfirmed -> "Lần trước vault bị khóa khi bạn chưa xác nhận đã chép Recovery Key, nên app đã tạo key MỚI (key cũ không còn dùng được):"
                else -> "Recovery Key MỚI (key cũ đã bị vô hiệu):"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Card(Modifier.fillMaxWidth().sensitive()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                recoveryKey.split("-").chunked(2).forEach { pair ->
                    Text(
                        recoveryKeyText(pair.joinToString("  -  ")),
                        fontFamily = RecoveryKeyFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        letterSpacing = 1.sp,
                    )
                }
            }
        }
        Hint("Key chỉ có chữ in hoa A–Z và số 2–7 (số được tô màu). KHÔNG có số 0, 1, 8, 9: thấy hình tròn là chữ O, nét thẳng là chữ I. Viết rõ các cặp dễ nhầm: Z / 2, S / 5, G / 6, T / 7.")
        Hint("• Chép tay ra giấy (hoặc in), cất nơi an toàn (két, ngăn kéo khóa). Không chụp màn hình (app cũng chặn chụp).")
        Hint("• Ai có Recovery Key + file vault/backup là mở được vault. Hãy giữ nó như giữ chìa khóa nhà.")
        Hint("• Nếu quên mật khẩu chính, đây là cách DUY NHẤT để lấy lại dữ liệu.")
        OutlinedButton(onClick = { confirmPrint = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Print, contentDescription = null)
            Text("  In phiếu khẩn cấp")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = saved, onCheckedChange = { saved = it })
            Text("Tôi đã chép (hoặc in) Recovery Key ra giấy")
        }
        Button(onClick = { verifying = true }, enabled = saved, modifier = Modifier.fillMaxWidth()) {
            Text("Tiếp tục: kiểm tra lại")
        }
    }

    if (confirmPrint) {
        AlertDialog(
            onDismissRequest = { confirmPrint = false },
            title = { Text("In Recovery Key?") },
            text = {
                SensitiveWindow()
                Text(
                    "• Chọn thẳng máy in trong nhà (Wi-Fi / USB). Dịch vụ in qua đám mây sẽ gửi tờ này ra ngoài.\n" +
                        "• ĐỪNG chọn \"Lưu dưới dạng PDF\": file PDF lại là một bản Recovery Key nằm trong máy.\n" +
                        "• Màn hình xem trước của trình in là app hệ thống, không được chặn chụp màn hình như app này.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPrint = false
                    // Trình in là app khác: hoãn "khóa khi rời app", nếu không vault khóa giữa chừng và key bị thay.
                    vm.suspendAutoLock()
                    com.samuelcruz.matkhau.ui.RecoveryKeyPrinter.print(context, recoveryKey) { vm.resumeAutoLock() }
                }) { Text("In") }
            },
            dismissButton = { TextButton(onClick = { confirmPrint = false }) { Text("Hủy") } },
        )
    }
}

/** Gõ lại 2 nhóm của key từ tờ giấy (không hiện key): bắt lỗi chép nhầm ngay bây giờ, không phải lúc cần dùng. */
@Composable
private fun VerifyRecoveryKey(recoveryKey: String, groups: List<Int>, onBack: () -> Unit, onVerified: () -> Unit) {
    val typed = remember { mutableStateListOf(*Array(groups.size) { "" }) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(onBack = onBack)
    AppScaffold(title = "Kiểm tra Recovery Key", onBack = onBack) {
        Text("Nhìn vào tờ giấy bạn vừa chép (không phải màn hình) và gõ lại các nhóm sau:", style = MaterialTheme.typography.titleMedium)
        groups.forEachIndexed { i, g ->
            TrackedTextField(
                value = typed[i],
                onValueChange = { typed[i] = it.uppercase().take(9); error = null },
                label = { Text("Nhóm thứ ${g + 1} (trong 8 nhóm)") },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = RecoveryKeyFont),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ErrorText(error)
        Button(
            onClick = {
                val wrong = groups.indices.filterNot { com.samuelcruz.matkhau.core.vault.RecoveryKey.groupMatches(recoveryKey, groups[it], typed[it]) }
                if (wrong.isEmpty()) onVerified()
                else error = "Nhóm thứ ${wrong.joinToString(" và ") { "${groups[it] + 1}" }} không khớp. Bấm \"Xem lại key\" và sửa tờ giấy (chú ý Z / 2, S / 5, G / 6, T / 7)."
            },
            enabled = typed.all { com.samuelcruz.matkhau.core.vault.RecoveryKey.normalize(it).length == 4 },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Xác nhận") }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Xem lại key") }
    }
}

fun android.content.Context.findFragmentActivity(): FragmentActivity? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
