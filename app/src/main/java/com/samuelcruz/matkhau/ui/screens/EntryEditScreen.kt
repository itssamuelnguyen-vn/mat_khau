package com.samuelcruz.matkhau.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.samuelcruz.matkhau.core.totp.Totp
import com.samuelcruz.matkhau.model.Categories
import com.samuelcruz.matkhau.model.CustomField
import com.samuelcruz.matkhau.model.Entry
import com.samuelcruz.matkhau.model.EntryType
import com.samuelcruz.matkhau.ui.VaultViewModel
import com.samuelcruz.matkhau.ui.components.AppScaffold
import com.samuelcruz.matkhau.ui.components.ErrorText
import com.samuelcruz.matkhau.ui.components.Hint
import com.samuelcruz.matkhau.ui.components.SecretField
import com.samuelcruz.matkhau.ui.components.SectionTitle

/** Dữ liệu form chỉ nằm trong `remember` (RAM), không ghi vào savedInstanceState. */
private class FieldDraft(name: String, value: String, hidden: Boolean) {
    var name by mutableStateOf(name)
    var value by mutableStateOf(value)
    var hidden by mutableStateOf(hidden)
}

@Composable
fun EntryEditScreen(vm: VaultViewModel, id: String?, initialType: EntryType) {
    val existing = remember(id) { id?.let { vm.entry(it) } }
    val isNew = existing == null

    var type by remember { mutableStateOf(existing?.type ?: initialType) }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var totp by remember { mutableStateOf(existing?.totp ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: Categories.PERSONAL) }
    var tagsText by remember { mutableStateOf(existing?.tags?.joinToString(", ") ?: "") }
    val fields = remember {
        mutableStateListOf<FieldDraft>().apply {
            existing?.customFields?.forEach { add(FieldDraft(it.name, it.value, it.hidden)) }
        }
    }
    var showGenerator by remember { mutableStateOf(false) }

    // Quét QR 2FA: chỉ xin quyền CAMERA khi bấm nút quét.
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.resumeAutoLock()
        if (granted) {
            scanMessage = null
            scanning = true
        } else {
            scanMessage = "Chưa có quyền Camera. Bạn vẫn có thể dán secret hoặc URI otpauth:// bằng tay."
        }
    }
    fun startScan() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanMessage = null
            scanning = true
        } else {
            vm.suspendAutoLock()   // hộp thoại xin quyền của hệ thống có thể đưa app xuống nền
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    if (scanning) {
        // Hiện đè lên form (cùng composable) để dữ liệu đang nhập không bị mất.
        QrScanScreen(
            onResult = { text ->
                totp = text
                runCatching { Totp.parse(text) }.getOrNull()?.let { cfg ->
                    if (title.isBlank()) cfg.issuer?.let { title = it }
                    if (username.isBlank()) cfg.account?.let { username = it }
                }
                scanMessage = "Đã đọc mã QR 2FA"
                scanning = false
            },
            onCancel = { scanning = false },
        )
        return
    }

    val totpInvalid = totp.isNotBlank() && !Totp.isValid(totp)
    val canSave = title.isNotBlank() && !totpInvalid && vm.busyText == null

    fun save() {
        val tags = tagsText.split(',', ' ', '#').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val draft = Entry(
            id = existing?.id ?: "",
            type = type,
            title = title.trim(),
            username = if (type == EntryType.LOGIN) username.trim() else "",
            password = if (type == EntryType.LOGIN) password else "",
            url = if (type == EntryType.LOGIN) url.trim() else "",
            totp = if (type == EntryType.LOGIN) totp.trim() else "",
            notes = notes,
            category = category,
            tags = tags,
            customFields = fields.filter { it.name.isNotBlank() || it.value.isNotEmpty() }
                .map { CustomField(it.name.trim(), it.value, it.hidden) },
            createdAt = existing?.createdAt ?: 0L,
            updatedAt = existing?.updatedAt ?: 0L,
        )
        vm.saveEntry(draft, isNew)
    }

    AppScaffold(
        title = if (isNew) "Thêm mục" else "Sửa mục",
        onBack = { vm.back() },
        actions = {
            IconButton(onClick = { save() }, enabled = canSave) { Icon(Icons.Filled.Check, "Lưu") }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = type == EntryType.LOGIN, onClick = { type = EntryType.LOGIN }, label = { Text("Đăng nhập") })
            FilterChip(selected = type == EntryType.NOTE, onClick = { type = EntryType.NOTE }, label = { Text("Ghi chú bảo mật") })
        }

        OutlinedTextField(title, { title = it }, label = { Text("Tên *") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        if (type == EntryType.LOGIN) {
            OutlinedTextField(
                username, { username = it }, label = { Text("Username / email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            SecretField(password, { password = it }, "Mật khẩu", monospace = true, trailingExtra = {
                IconButton(onClick = { showGenerator = true }) { Icon(Icons.Filled.AutoAwesome, "Tạo mật khẩu") }
            })
            OutlinedTextField(
                url, { url = it }, label = { Text("Website URL") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            SecretField(totp, { totp = it }, "Secret TOTP (2FA)", monospace = true, trailingExtra = {
                IconButton(onClick = { startScan() }) { Icon(Icons.Filled.QrCodeScanner, "Quét mã QR") }
            })
            if (totpInvalid) {
                ErrorText("Secret không hợp lệ. Nhập chuỗi Base32 (vd. JBSWY3DPEHPK3PXP) hoặc URI otpauth://totp/...")
            } else {
                Hint(scanMessage ?: "Bấm biểu tượng QR để quét mã trên trang bật 2FA, hoặc dán secret dạng chữ / URI otpauth://.")
            }
        }

        OutlinedTextField(
            notes, { notes = it },
            label = { Text(if (type == EntryType.NOTE) "Nội dung (recovery code, API key, Wi-Fi...)" else "Ghi chú") },
            minLines = if (type == EntryType.NOTE) 6 else 2,
            textStyle = if (type == EntryType.NOTE) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("Phân loại")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Categories.all.forEach { c ->
                FilterChip(selected = category == c, onClick = { category = c }, label = { Text(Categories.label(c)) })
            }
        }
        OutlinedTextField(
            tagsText, { tagsText = it }, label = { Text("Tag (cách nhau bằng dấu phẩy)") },
            placeholder = { Text("work, github") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("Trường tùy chỉnh")
        fields.forEachIndexed { index, f ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(f.name, { f.name = it }, label = { Text("Tên trường") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (f.hidden) {
                        SecretField(f.value, { f.value = it }, "Giá trị")
                    } else {
                        OutlinedTextField(f.value, { f.value = it }, label = { Text("Giá trị") }, modifier = Modifier.fillMaxWidth())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = f.hidden, onCheckedChange = { f.hidden = it })
                        Text("  Che giấu", modifier = Modifier.weight(1f))
                        IconButton(onClick = { fields.removeAt(index) }) { Icon(Icons.Filled.Delete, "Xóa trường") }
                    }
                }
            }
        }
        OutlinedButton(onClick = { fields.add(FieldDraft("", "", true)) }) {
            Icon(Icons.Filled.Add, null)
            Text("  Thêm trường")
        }
    }

    if (showGenerator) {
        GeneratorDialog(vm, onDismiss = { showGenerator = false }, onUse = { password = it; showGenerator = false })
    }
}
