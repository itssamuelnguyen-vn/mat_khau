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
import com.samuelcruz.matkhau.core.card.PaymentCard
import com.samuelcruz.matkhau.model.CardInfo
import com.samuelcruz.matkhau.model.IdentityInfo
import com.samuelcruz.matkhau.model.IdentityKinds
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.samuelcruz.matkhau.model.Categories
import com.samuelcruz.matkhau.model.CustomField
import com.samuelcruz.matkhau.model.Entry
import com.samuelcruz.matkhau.model.EntryType
import com.samuelcruz.matkhau.ui.VaultViewModel
import com.samuelcruz.matkhau.ui.components.AppScaffold
import com.samuelcruz.matkhau.ui.components.TrackedTextField
import com.samuelcruz.matkhau.ui.components.ErrorText
import com.samuelcruz.matkhau.ui.components.Hint
import com.samuelcruz.matkhau.ui.components.SecretField
import com.samuelcruz.matkhau.ui.components.SectionTitle
import com.samuelcruz.matkhau.ui.components.LocalSecretGuard

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
    // Thẻ ngân hàng
    val oldCard = existing?.card
    var cardHolder by remember { mutableStateOf(oldCard?.holder ?: "") }
    var cardNumber by remember { mutableStateOf(oldCard?.number ?: "") }
    var cardExpiry by remember { mutableStateOf(oldCard?.expiry ?: "") }
    var cardCvv by remember { mutableStateOf(oldCard?.cvv ?: "") }
    var cardPin by remember { mutableStateOf(oldCard?.pin ?: "") }
    // Giấy tờ tùy thân
    val oldId = existing?.identity
    var idKind by remember { mutableStateOf(oldId?.kind ?: IdentityKinds.CCCD) }
    var idName by remember { mutableStateOf(oldId?.fullName ?: "") }
    var idNumber by remember { mutableStateOf(oldId?.number ?: "") }
    var idBirth by remember { mutableStateOf(oldId?.dateOfBirth ?: "") }
    var idGender by remember { mutableStateOf(oldId?.gender ?: "") }
    var idNationality by remember { mutableStateOf(oldId?.nationality ?: "") }
    var idIssued by remember { mutableStateOf(oldId?.issueDate ?: "") }
    var idExpiry by remember { mutableStateOf(oldId?.expiryDate ?: "") }
    var idIssuedBy by remember { mutableStateOf(oldId?.issuedBy ?: "") }
    var idAddress by remember { mutableStateOf(oldId?.address ?: "") }
    val fields = remember {
        mutableStateListOf<FieldDraft>().apply {
            existing?.customFields?.forEach { add(FieldDraft(it.name, it.value, it.hidden)) }
        }
    }
    var showGenerator by remember { mutableStateOf(false) }
    val guard = LocalSecretGuard.current

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
                Totp.parseOrNull(text)?.let { cfg ->
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

    val totpInvalid = type == EntryType.LOGIN && totp.isNotBlank() && !Totp.isValid(totp)
    val expiryInvalid = type == EntryType.CARD && cardExpiry.isNotBlank() && PaymentCard.parseExpiry(cardExpiry) == null
    // Thẻ / giấy tờ không bắt buộc đặt tên: tự đặt theo hãng thẻ + 4 số cuối, hoặc loại giấy tờ + họ tên.
    val autoTitle = when (type) {
        EntryType.CARD -> PaymentCard.digits(cardNumber).takeIf { it.length >= 4 }?.let { "${PaymentCard.brand(it).label} ${PaymentCard.masked(it)}" }
        EntryType.IDENTITY -> idName.trim().takeIf { it.isNotEmpty() }?.let { "${IdentityKinds.label(idKind)} – $it" }
        else -> null
    }
    val canSave = (title.isNotBlank() || autoTitle != null) && !totpInvalid && !expiryInvalid && vm.busyText == null

    fun save() {
        val tags = tagsText.split(',', ' ', '#').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val draft = Entry(
            id = existing?.id ?: "",
            type = type,
            title = title.trim().ifEmpty { autoTitle.orEmpty() },
            card = if (type == EntryType.CARD) CardInfo(
                holder = cardHolder.trim(),
                number = PaymentCard.digits(cardNumber),
                expiry = PaymentCard.parseExpiry(cardExpiry)?.mmyy ?: "",
                cvv = cardCvv.trim(),
                pin = cardPin.trim(),
            ) else null,
            identity = if (type == EntryType.IDENTITY) IdentityInfo(
                kind = idKind, fullName = idName.trim(), number = idNumber.trim(), dateOfBirth = idBirth.trim(),
                gender = idGender.trim(), nationality = idNationality.trim(), issueDate = idIssued.trim(),
                expiryDate = idExpiry.trim(), issuedBy = idIssuedBy.trim(), address = idAddress.trim(),
            ) else null,
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(selected = type == EntryType.LOGIN, onClick = { type = EntryType.LOGIN }, label = { Text("Đăng nhập") })
            FilterChip(selected = type == EntryType.NOTE, onClick = { type = EntryType.NOTE }, label = { Text("Ghi chú bảo mật") })
            FilterChip(selected = type == EntryType.CARD, onClick = { type = EntryType.CARD }, label = { Text("Thẻ") })
            FilterChip(selected = type == EntryType.IDENTITY, onClick = { type = EntryType.IDENTITY }, label = { Text("Giấy tờ") })
        }

        TrackedTextField(
            title, { title = it },
            label = { Text(if (autoTitle != null || type == EntryType.CARD || type == EntryType.IDENTITY) "Tên (để trống: tự đặt)" else "Tên *") },
            placeholder = if (autoTitle != null) ({ Text(autoTitle) }) else null,
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        if (type == EntryType.CARD) {
            TrackedTextField(
                cardHolder, { cardHolder = it }, label = { Text("Tên in trên thẻ") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            SecretField(cardNumber, { cardNumber = it.filter { c -> c.isDigit() || c == ' ' }.take(23) }, "Số thẻ", monospace = true, guarded = true, keyboardType = KeyboardType.NumberPassword)
            val digits = PaymentCard.digits(cardNumber)
            if (digits.length >= 6) {
                if (PaymentCard.looksValid(digits)) Hint("${PaymentCard.brand(digits).label} ${PaymentCard.masked(digits)}")
                else ErrorText("Số thẻ có vẻ bị gõ sai (kiểm tra lại từng chữ số)")
            }
            TrackedTextField(
                cardExpiry, { cardExpiry = it.take(7) }, label = { Text("Hết hạn (MM/YY)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (expiryInvalid) ErrorText("Nhập tháng / năm, ví dụ 08/29")
            SecretField(cardCvv, { cardCvv = it.filter(Char::isDigit).take(4) }, "CVV / CVC", monospace = true, guarded = true, keyboardType = KeyboardType.NumberPassword)
            SecretField(cardPin, { cardPin = it.filter(Char::isDigit).take(12) }, "Mã PIN", monospace = true, guarded = true, keyboardType = KeyboardType.NumberPassword)
            Hint("Tự động điền thẻ vào trang thanh toán luôn hỏi vân tay trước.")
        }

        if (type == EntryType.IDENTITY) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                IdentityKinds.all.forEach { k ->
                    FilterChip(selected = idKind == k, onClick = { idKind = k }, label = { Text(IdentityKinds.label(k)) })
                }
            }
            TrackedTextField(idName, { idName = it }, label = { Text("Họ và tên") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SecretField(idNumber, { idNumber = it }, "Số giấy tờ", monospace = true, guarded = true)
            TrackedTextField(idBirth, { idBirth = it }, label = { Text("Ngày sinh (dd/mm/yyyy)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TrackedTextField(idGender, { idGender = it }, label = { Text("Giới tính") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TrackedTextField(idNationality, { idNationality = it }, label = { Text("Quốc tịch") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TrackedTextField(idIssued, { idIssued = it }, label = { Text("Ngày cấp") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TrackedTextField(idExpiry, { idExpiry = it }, label = { Text("Có giá trị đến") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TrackedTextField(idIssuedBy, { idIssuedBy = it }, label = { Text("Nơi cấp") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TrackedTextField(idAddress, { idAddress = it }, label = { Text("Nơi thường trú / địa chỉ") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Hint("Có thể đính kèm ảnh chụp giấy tờ ở màn chi tiết sau khi lưu.")
        }

        if (type == EntryType.LOGIN) {
            TrackedTextField(
                username, { username = it }, label = { Text("Username / email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            SecretField(password, { password = it }, "Mật khẩu", monospace = true, guarded = true, trailingExtra = {
                IconButton(onClick = { showGenerator = true }) { Icon(Icons.Filled.AutoAwesome, "Tạo mật khẩu") }
            })
            TrackedTextField(
                url, { url = it }, label = { Text("Website URL") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            SecretField(totp, { totp = it }, "Secret TOTP (2FA)", monospace = true, guarded = true, trailingExtra = {
                IconButton(onClick = { startScan() }) { Icon(Icons.Filled.QrCodeScanner, "Quét mã QR") }
            })
            if (totpInvalid) {
                ErrorText("Secret không hợp lệ. Nhập chuỗi Base32 (vd. JBSWY3DPEHPK3PXP) hoặc URI otpauth://totp/...")
            } else {
                Hint(scanMessage ?: "Bấm biểu tượng QR để quét mã trên trang bật 2FA, hoặc dán secret dạng chữ / URI otpauth://.")
            }
        }

        TrackedTextField(
            notes, { notes = it },
            label = { Text(if (type == EntryType.NOTE) "Nội dung (recovery code, API key, Wi-Fi...)" else "Ghi chú") },
            minLines = if (type == EntryType.NOTE) 6 else 2,
            // Tắt gợi ý / tự sửa: bàn phím không học nội dung ghi chú (recovery code, API key...)
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
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
        TrackedTextField(
            tagsText, { tagsText = it }, label = { Text("Tag (cách nhau bằng dấu phẩy)") },
            placeholder = { Text("work, github") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("Trường tùy chỉnh")
        fields.forEachIndexed { index, f ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrackedTextField(f.name, { f.name = it }, label = { Text("Tên trường") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (f.hidden) {
                        SecretField(f.value, { f.value = it }, "Giá trị", guarded = true)
                    } else {
                        TrackedTextField(f.value, { f.value = it }, label = { Text("Giá trị") }, modifier = Modifier.fillMaxWidth())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Bỏ che = hiện giá trị: qua cổng xác thực.
                        Switch(checked = f.hidden, onCheckedChange = { on -> if (on) f.hidden = true else guard("Bỏ che “${f.name}”") { f.hidden = false } })
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
