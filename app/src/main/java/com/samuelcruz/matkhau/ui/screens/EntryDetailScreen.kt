package com.samuelcruz.matkhau.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samuelcruz.matkhau.core.totp.Totp
import com.samuelcruz.matkhau.core.totp.TotpConfig
import com.samuelcruz.matkhau.model.Categories
import com.samuelcruz.matkhau.model.EntryType
import com.samuelcruz.matkhau.ui.Screen
import com.samuelcruz.matkhau.ui.VaultViewModel
import com.samuelcruz.matkhau.ui.components.AppScaffold
import com.samuelcruz.matkhau.ui.components.ErrorText
import com.samuelcruz.matkhau.ui.components.FieldRow
import com.samuelcruz.matkhau.ui.components.SectionTitle
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryDetailScreen(vm: VaultViewModel, id: String) {
    val entries by vm.repository.entries.collectAsState()
    val entry = entries.firstOrNull { it.id == id }
    val context = LocalContext.current.applicationContext
    var confirmDelete by remember { mutableStateOf(false) }

    if (entry == null) {
        AppScaffold(title = "Không tìm thấy", onBack = { vm.back() }) { Text("Mục này không còn tồn tại.") }
        return
    }

    AppScaffold(
        title = entry.title,
        onBack = { vm.back() },
        actions = {
            IconButton(onClick = { vm.navigate(Screen.Edit(entry.id, entry.type)) }) { Icon(Icons.Filled.Edit, "Sửa") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Xóa") }
        },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text(Categories.label(entry.category)) })
            entry.tags.forEach { AssistChip(onClick = {}, label = { Text("#$it") }) }
        }

        if (entry.type == EntryType.LOGIN) {
            FieldRow("Username", entry.username, onCopy = { vm.copy(context, "username", entry.username) })
            FieldRow("Mật khẩu", entry.password, secret = true, onCopy = { vm.copy(context, "mật khẩu", entry.password) })
            FieldRow("URL", entry.url, onCopy = { vm.copy(context, "URL", entry.url) })
            if (entry.totp.isNotBlank()) TotpCard(entry.totp) { code -> vm.copy(context, "mã 2FA", code) }
        }

        if (entry.notes.isNotBlank()) {
            SectionTitle(if (entry.type == EntryType.NOTE) "Nội dung" else "Ghi chú")
            FieldRow("Ghi chú", entry.notes, secret = entry.type == EntryType.NOTE, onCopy = { vm.copy(context, "ghi chú", entry.notes) })
        }

        if (entry.customFields.isNotEmpty()) {
            SectionTitle("Trường tùy chỉnh")
            entry.customFields.forEach { f ->
                FieldRow(f.name.ifBlank { "(không tên)" }, f.value, secret = f.hidden, onCopy = { vm.copy(context, f.name, f.value) })
            }
        }

        HorizontalDivider()
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        Text("Tạo: ${fmt.format(Date(entry.createdAt))}", style = MaterialTheme.typography.bodySmall)
        Text("Sửa: ${fmt.format(Date(entry.updatedAt))}", style = MaterialTheme.typography.bodySmall)
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Xóa \"${entry.title}\"?") },
            text = { Text("Không thể hoàn tác (trừ khi bạn khôi phục từ backup cũ).") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteEntry(entry.id) }) { Text("Xóa") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Hủy") } },
        )
    }
}

/** Mã TOTP tự cập nhật mỗi giây, có thanh đếm ngược. */
@Composable
fun TotpCard(secret: String, onCopy: (String) -> Unit) {
    val config: TotpConfig? = remember(secret) { Totp.parseOrNull(secret) }
    if (config == null) {
        ErrorText("Secret TOTP không hợp lệ — hãy sửa lại mục này")
        return
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(config) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1000 - System.currentTimeMillis() % 1000)
        }
    }
    val code = Totp.generate(config, now)
    val remaining = Totp.secondsRemaining(config, now)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Mã xác thực 2 bước (TOTP)", style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    code.chunked(if (code.length == 6) 3 else 4).joinToString(" "),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    modifier = Modifier.weight(1f),
                )
                Text("${remaining}s", style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { onCopy(code) }) { Icon(Icons.Filled.ContentCopy, "Copy mã") }
            }
            LinearProgressIndicator(
                progress = { remaining / config.period.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
