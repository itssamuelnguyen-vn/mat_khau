package com.samuelcruz.matkhau.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.samuelcruz.matkhau.model.EntryType
import com.samuelcruz.matkhau.ui.VaultViewModel
import com.samuelcruz.matkhau.ui.components.AppScaffold
import com.samuelcruz.matkhau.ui.components.Hint

/**
 * Nhập từ CSV: xem trước (nguồn, số mục, mục trùng) → nhập trong một transaction → nhắc xóa file CSV
 * (file đó chứa mọi mật khẩu ở dạng chữ, không mã hóa). Không hiện mật khẩu nào ở màn hình này.
 */
@Composable
fun ImportCsvScreen(vm: VaultViewModel) {
    val context = LocalContext.current
    val pending = vm.pendingImport
    val imported = vm.importedCount

    AppScaffold(title = "Nhập từ CSV", onBack = { vm.finishImport() }) {
        when {
            imported != null -> {
                Text("✓ Đã nhập $imported mục vào vault", style = MaterialTheme.typography.titleMedium)
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Xóa file CSV ngay",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            "File CSV chứa toàn bộ mật khẩu ở dạng chữ, KHÔNG mã hóa. Ai mở được file là đọc được hết. " +
                                "Hãy xóa nó, cả bản trong Thùng rác, Google Drive, máy tính hay email nếu có.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Button(
                            onClick = { vm.deleteImportedCsv(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Xóa file CSV") }
                    }
                }
                Hint("Nên xuất một backup mã hóa mới (Cài đặt → Xuất backup mã hóa) sau khi nhập.")
                OutlinedButton(onClick = { vm.finishImport() }, modifier = Modifier.fillMaxWidth()) { Text("Xong") }
            }

            pending == null -> Text("Chưa chọn file CSV.")

            else -> {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Nguồn: ${pending.source.label}", style = MaterialTheme.typography.titleSmall)
                        Text("${pending.entries.size} mục sẽ được nhập")
                        if (pending.duplicates > 0) Hint("${pending.duplicates} mục đã có trong vault (hoặc trùng trong file): bỏ qua")
                        if (pending.skippedRows > 0) Hint("${pending.skippedRows} dòng trống: bỏ qua")
                        val notes = pending.entries.count { it.type == EntryType.NOTE }
                        if (notes > 0) Hint("Trong đó $notes ghi chú bảo mật")
                    }
                }
                Hint(
                    "Thư mục / nhóm thành tag. Cột không có chỗ riêng được giữ thành trường tùy chỉnh; URL phụ và TOTP " +
                        "không đọc được giữ trong ghi chú. Mục trong Thùng rác bị bỏ qua. Có thể sửa từng mục sau khi nhập.",
                )
                Hint("File CSV không chứa thẻ ngân hàng, tệp đính kèm hay passkey: những thứ đó cần chuyển bằng tay.")
                Button(
                    onClick = { vm.confirmImport() },
                    enabled = pending.entries.isNotEmpty() && vm.busyText == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Nhập ${pending.entries.size} mục") }
                TextButton(onClick = { vm.finishImport() }, modifier = Modifier.fillMaxWidth()) { Text("Hủy") }

                if (pending.entries.isNotEmpty()) {
                    Text("Xem trước", style = MaterialTheme.typography.titleSmall)
                    pending.entries.take(PREVIEW).forEach { e ->
                        ListItem(
                            headlineContent = { Text(e.title) },
                            supportingContent = {
                                val detail = if (e.type == EntryType.NOTE) "Ghi chú bảo mật" else listOf(e.username, e.url).filter { it.isNotBlank() }.joinToString(" · ")
                                if (detail.isNotBlank()) Text(detail)
                            },
                        )
                    }
                    if (pending.entries.size > PREVIEW) Hint("… và ${pending.entries.size - PREVIEW} mục nữa")
                }
            }
        }
    }
}

private const val PREVIEW = 30
