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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
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
import androidx.compose.runtime.key
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
import com.samuelcruz.matkhau.core.generator.Strength
import com.samuelcruz.matkhau.core.generator.StrengthEstimator
import com.samuelcruz.matkhau.core.health.PasswordHealth
import com.samuelcruz.matkhau.autofill.AppIdentity
import androidx.compose.material3.ListItem
import com.samuelcruz.matkhau.model.Categories
import com.samuelcruz.matkhau.model.Entry
import com.samuelcruz.matkhau.model.PasswordHistoryItem
import com.samuelcruz.matkhau.ui.components.Hint
import com.samuelcruz.matkhau.ui.components.LocalSecretGuard
import com.samuelcruz.matkhau.ui.components.sensitive
import com.samuelcruz.matkhau.ui.components.SensitiveWindow
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
    val guard = LocalSecretGuard.current
    var restoreCandidate by remember { mutableStateOf<PasswordHistoryItem?>(null) }

    if (entry == null) {
        AppScaffold(title = "Không tìm thấy", onBack = { vm.back() }) { Text("Mục này không còn tồn tại.") }
        return
    }

    AppScaffold(
        title = entry.title,
        onBack = { vm.back() },
        actions = {
            IconButton(onClick = { vm.toggleFavorite(entry.id) }) {
                Icon(
                    if (entry.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    if (entry.favorite) "Bỏ yêu thích" else "Thêm vào yêu thích",
                )
            }
            // Màn Sửa hiện ghi chú / trường tùy chỉnh ở dạng chữ: qua cổng xác thực như khi bấm "hiện".
            IconButton(onClick = { guard("Sửa “${entry.title}”") { vm.navigate(Screen.Edit(entry.id, entry.type)) } }) { Icon(Icons.Filled.Edit, "Sửa") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Xóa") }
        },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text(Categories.label(entry.category)) })
            entry.tags.forEach { AssistChip(onClick = {}, label = { Text("#$it") }) }
        }

        if (entry.type == EntryType.LOGIN) {
            FieldRow("Username", entry.username, onCopy = { vm.copy(context, "username", entry.username) })
            // key: đổi mật khẩu (vd. dùng lại bản cũ) thì dòng này về trạng thái đang che
            key(entry.password) {
                FieldRow("Mật khẩu", entry.password, secret = true, onCopy = { vm.copy(context, "mật khẩu", entry.password) })
            }
            PasswordHealthHint(entry, entries)
            FieldRow("URL", entry.url, onCopy = { vm.copy(context, "URL", entry.url) })
            if (entry.totp.isNotBlank()) TotpCard(entry.totp) { code -> vm.copy(context, "mã 2FA", code) }
        }

        if (entry.notes.isNotBlank()) {
            SectionTitle(if (entry.type == EntryType.NOTE) "Nội dung" else "Ghi chú")
            FieldRow("Ghi chú", entry.notes, secret = entry.type == EntryType.NOTE, onCopy = { vm.copy(context, "ghi chú", entry.notes) })
        }

        if (entry.linkedApps.isNotEmpty()) {
            SectionTitle("Ứng dụng tự động điền")
            entry.linkedApps.forEach { linked ->
                val label = remember(linked.packageName) { AppIdentity.label(context, linked.packageName) }
                ListItem(
                    headlineContent = { Text(label) },
                    supportingContent = { Text(linked.packageName) },
                    trailingContent = { TextButton(onClick = { vm.unlinkApp(entry.id, linked.packageName) }) { Text("Bỏ liên kết") } },
                )
            }
        }

        if (entry.customFields.isNotEmpty()) {
            SectionTitle("Trường tùy chỉnh")
            entry.customFields.forEach { f ->
                FieldRow(f.name.ifBlank { "(không tên)" }, f.value, secret = f.hidden, onCopy = { vm.copy(context, f.name, f.value) })
            }
        }

        if (entry.type == EntryType.LOGIN && entry.passwordHistory.isNotEmpty()) {
            var showHistory by remember { mutableStateOf(false) }
            TextButton(onClick = { showHistory = !showHistory }) {
                Text((if (showHistory) "▾ " else "▸ ") + "Lịch sử mật khẩu (${entry.passwordHistory.size})")
            }
            if (showHistory) {
                val dateFmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
                entry.passwordHistory.forEach { item ->
                    // key theo từng bản: trạng thái "đang hiện" không nhảy sang dòng khác khi danh sách đổi
                    key(item.changedAt, item.password) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            FieldRow(
                                "Dùng tới ${dateFmt.format(Date(item.changedAt))}", item.password, secret = true,
                                onCopy = { vm.copy(context, "mật khẩu cũ", item.password) },
                            )
                            TextButton(onClick = { restoreCandidate = item }) { Text("Dùng lại mật khẩu này") }
                        }
                    }
                    }
                }
            }
        }

        HorizontalDivider()
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        Text("Tạo: ${fmt.format(Date(entry.createdAt))}", style = MaterialTheme.typography.bodySmall)
        Text("Sửa: ${fmt.format(Date(entry.updatedAt))}", style = MaterialTheme.typography.bodySmall)
    }

    restoreCandidate?.let { old ->
        AlertDialog(
            onDismissRequest = { restoreCandidate = null },
            title = { Text("Dùng lại mật khẩu cũ?") },
            text = { SensitiveWindow(); Text("Mật khẩu hiện tại sẽ được chuyển vào lịch sử. App không đổi mật khẩu trên website: hãy chắc là website đang dùng mật khẩu cũ này.") },
            confirmButton = {
                TextButton(onClick = { restoreCandidate = null; vm.restorePassword(entry.id, old) }) { Text("Dùng lại") }
            },
            dismissButton = { TextButton(onClick = { restoreCandidate = null }) { Text("Hủy") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Xóa \"${entry.title}\"?") },
            text = { SensitiveWindow(); Text("Không thể hoàn tác (trừ khi bạn khôi phục từ backup cũ).") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteEntry(entry.id) }) { Text("Xóa") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Hủy") } },
        )
    }
}

/** Độ mạnh + cảnh báo của mật khẩu hiện tại: yếu, dùng chung với mục khác, lâu chưa đổi. */
@Composable
private fun PasswordHealthHint(entry: Entry, entries: List<Entry>) {
    if (entry.password.isEmpty()) return
    val result = remember(entry.password) { StrengthEstimator.analyze(entry.password) }
    val reused = remember(entry, entries) { PasswordHealth.reuseCount(entry, entries) }
    val ageDays = (System.currentTimeMillis() - entry.passwordSince()) / (24L * 60 * 60 * 1000)
    val problems = buildList {
        if (result.strength <= Strength.WEAK) add("Mật khẩu ${result.strength.label.lowercase()}: ${result.warnings.firstOrNull() ?: "dễ đoán"}")
        if (reused > 0) add("Dùng chung với $reused mục khác")
        if (entry.passwordSince() > 0 && ageDays * 24L * 60 * 60 * 1000 > PasswordHealth.OLD_AFTER_MS) add("Chưa đổi $ageDays ngày")
    }
    if (problems.isEmpty()) {
        Hint("Độ mạnh: ${result.strength.label}")
    } else {
        problems.forEach { ErrorText("⚠ $it") }
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

    Card(Modifier.fillMaxWidth().sensitive()) {
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
                val guard = LocalSecretGuard.current
                // Tính lại mã SAU khi xác thực xong (hộp thoại vân tay có thể mở quá chu kỳ 30 giây).
                IconButton(onClick = { guard("Copy mã 2FA") { onCopy(Totp.generate(config, System.currentTimeMillis() / 1000)) } }) {
                    Icon(Icons.Filled.ContentCopy, "Copy mã")
                }
            }
            LinearProgressIndicator(
                progress = { remaining / config.period.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
