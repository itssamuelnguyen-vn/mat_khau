package com.samuelcruz.matkhau.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.samuelcruz.matkhau.core.totp.OtpMigration
import com.samuelcruz.matkhau.ui.VaultViewModel
import com.samuelcruz.matkhau.ui.components.AppScaffold
import com.samuelcruz.matkhau.ui.components.ErrorText
import com.samuelcruz.matkhau.ui.components.Hint
import com.samuelcruz.matkhau.ui.components.SectionTitle
import com.samuelcruz.matkhau.ui.components.TrackedTextField
import com.samuelcruz.matkhau.ui.components.sensitive

/**
 * Nhập nhiều mã 2FA một lúc từ Google Authenticator (⋮ → Chuyển tài khoản → Xuất tài khoản). Mỗi mã QR chứa vài tài
 * khoản; nhiều tài khoản thì quét lần lượt từng mã. Secret chỉ nằm trong RAM tới khi nhập (một transaction).
 */
@Composable
fun ImportTotpScreen(vm: VaultViewModel) {
    val state = vm.totpImport ?: run {
        AppScaffold(title = "Nhập mã 2FA", onBack = { vm.back() }) { Text("Phiên nhập đã kết thúc.") }
        return
    }
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pasted by remember { mutableStateOf("") }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.resumeAutoLock()
        if (granted) scanning = true else message = "Chưa có quyền Camera. Bạn vẫn có thể dán URI otpauth-migration:// bên dưới."
    }

    if (scanning) {
        QrScanScreen(
            onResult = { text -> scanning = false; message = vm.addMigrationCode(text) },
            onCancel = { scanning = false },
            title = "Đưa mã QR \"Chuyển tài khoản\" vào khung",
            accept = { text ->
                if (OtpMigration.isMigrationUri(text)) null
                else "Đây không phải mã chuyển tài khoản (otpauth-migration://). Mã 2FA lẻ thì thêm ở màn Sửa mục."
            },
        )
        return
    }

    AppScaffold(title = "Nhập mã 2FA", onBack = { vm.back() }) {
        Hint("Trên Google Authenticator: ⋮ → Chuyển tài khoản → Xuất tài khoản → chọn tài khoản → mã QR hiện ra. Có nhiều mã QR thì quét lần lượt từng mã.")
        Button(
            onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    scanning = true
                } else {
                    vm.suspendAutoLock()
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.QrCodeScanner, null)
            Text(if (state.batches.isEmpty()) "  Quét mã QR" else "  Quét mã QR tiếp theo")
        }
        message?.let { Hint(it) }

        TrackedTextField(
            pasted, { pasted = it },
            label = { Text("Hoặc dán URI otpauth-migration://…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (pasted.isNotBlank()) {
            OutlinedButton(onClick = { message = vm.addMigrationCode(pasted); pasted = "" }, modifier = Modifier.fillMaxWidth()) { Text("Thêm") }
        }

        if (state.batches.isNotEmpty()) {
            val scanned = state.batches.size
            Hint("Đã quét $scanned mã QR" + if (state.batchSize > 1) " (lần xuất này có ${state.batchSize} mã)" else "")
            if (state.batchSize > 1 && scanned < state.batchSize) ErrorText("Còn ${state.batchSize - scanned} mã QR chưa quét")
        }
        if (state.duplicates > 0) Hint("${state.duplicates} tài khoản đã có trong vault: bỏ qua")
        if (state.skipped.isNotEmpty()) {
            SectionTitle("Không nhập được")
            state.skipped.forEach { Hint("• $it") }
        }

        if (state.accounts.isNotEmpty()) {
            SectionTitle("Sẽ nhập (${state.accounts.size})")
            state.accounts.forEachIndexed { i, a ->
                ListItem(
                    headlineContent = { Text(a.issuer.ifBlank { a.account.ifBlank { "Mã 2FA" } }) },
                    supportingContent = { Text(a.account.ifBlank { "—" }) },
                    trailingContent = { IconButton(onClick = { vm.removeTotpAccount(i) }) { Icon(Icons.Filled.Close, "Bỏ") } },
                    modifier = Modifier.sensitive(),
                )
            }
            Button(onClick = { vm.confirmTotpImport() }, enabled = vm.busyText == null, modifier = Modifier.fillMaxWidth()) {
                Text("Nhập ${state.accounts.size} mã 2FA")
            }
            Hint("Mỗi tài khoản thành một mục đăng nhập có mã 2FA. Kiểm tra mã trong Mật Khẩu khớp rồi mới xóa tài khoản trong Google Authenticator.")
        }
    }
}
