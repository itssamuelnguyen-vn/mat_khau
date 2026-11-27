package com.samuelcruz.matkhau.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samuelcruz.matkhau.core.health.HealthReport
import com.samuelcruz.matkhau.core.health.PasswordHealth
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.samuelcruz.matkhau.model.Entry
import com.samuelcruz.matkhau.ui.Screen
import com.samuelcruz.matkhau.ui.VaultViewModel
import com.samuelcruz.matkhau.ui.components.AppScaffold
import com.samuelcruz.matkhau.ui.components.Hint

/**
 * Sức khỏe mật khẩu (spec 3.4–3.7): mật khẩu yếu, dùng chung, lâu chưa đổi, thiếu 2FA, thiếu URL.
 * Tính trong RAM từ danh sách mục đang mở; không có gì rời khỏi máy.
 */
@Composable
fun HealthScreen(vm: VaultViewModel) {
    val entries by vm.repository.entries.collectAsState()
    // Đo độ mạnh mọi mục có thể mất vài trăm ms với vault lớn: tính ngoài main thread.
    val report by produceState<HealthReport?>(null, entries) {
        value = withContext(Dispatchers.Default) { PasswordHealth.analyze(entries, System.currentTimeMillis()) }
    }
    val open: (Entry) -> Unit = { vm.navigate(Screen.Detail(it.id)) }

    AppScaffold(title = "Sức khỏe mật khẩu", onBack = { vm.back() }) {
        val report = report ?: run {
            Hint("Đang phân tích…")
            return@AppScaffold
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val color = when {
                    report.score >= 80 -> Color(0xFF2E7D32)
                    report.score >= 50 -> Color(0xFFF9A825)
                    else -> Color(0xFFD32F2F)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${report.score}", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = color)
                    Text(" / 100", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
                LinearProgressIndicator(progress = { report.score / 100f }, color = color, modifier = Modifier.fillMaxWidth())
                Hint(
                    if (report.loginCount == 0) "Chưa có mục đăng nhập nào có mật khẩu."
                    else "${report.loginCount - report.flaggedIds.size}/${report.loginCount} mục đăng nhập không yếu, không dùng chung, không quá 1 năm chưa đổi.",
                )
            }
        }
        Hint("Tính ngay trên máy từ dữ liệu đang mở. Mật khẩu không được gửi đi đâu để so sánh.")

        HealthSection(
            title = "Mật khẩu yếu",
            hint = "Dễ đoán: mật khẩu phổ biến, ngày sinh, chuỗi phím liền nhau, quá ngắn…",
            items = report.weak.map { (e, s) -> e to "Độ mạnh: ${s.label}" },
            onOpen = open,
        )
        HealthSection(
            title = "Mật khẩu dùng chung",
            hint = "Một website bị lộ thì mọi tài khoản dùng chung mật khẩu đó đều bị lộ theo.",
            items = report.reused.flatMap { group -> group.map { e -> e to "Dùng chung với ${group.size - 1} mục khác" } },
            onOpen = open,
        )
        HealthSection(
            title = "Lâu chưa đổi (hơn 1 năm)",
            hint = "Nên đổi định kỳ các tài khoản quan trọng.",
            items = report.old.map { e ->
                val days = (System.currentTimeMillis() - e.passwordSince()) / (24L * 60 * 60 * 1000)
                e to "Chưa đổi $days ngày"
            },
            onOpen = open,
        )
        HealthSection(
            title = "Chưa có 2FA",
            hint = "Gợi ý: bật xác thực 2 bước trên website rồi thêm mã vào mục này.",
            items = report.missingTotp.map { it to (it.url.ifBlank { it.username }) },
            onOpen = open,
            isRecommendation = true,
        )
        HealthSection(
            title = "Thiếu URL",
            hint = "Có URL thì dễ nhận ra đúng website (và cần cho tự động điền sau này).",
            items = report.missingUrl.map { it to it.username },
            onOpen = open,
            isRecommendation = true,
        )
    }
}

@Composable
private fun HealthSection(
    title: String,
    hint: String,
    items: List<Pair<Entry, String>>,
    onOpen: (Entry) -> Unit,
    isRecommendation: Boolean = false,
) {
    var expanded by remember { mutableStateOf(!isRecommendation) }
    val countColor = when {
        items.isEmpty() -> Color(0xFF2E7D32)
        isRecommendation -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = items.isNotEmpty()) { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Hint(hint)
                }
                Text(if (items.isEmpty()) "✓" else "${items.size}", color = countColor, style = MaterialTheme.typography.titleLarge)
            }
            if (expanded && items.isNotEmpty()) {
                items.forEach { (entry, detail) ->
                    ListItem(
                        headlineContent = { Text(entry.title) },
                        supportingContent = { if (detail.isNotBlank()) Text(detail) },
                        modifier = Modifier.clickable { onOpen(entry) },
                    )
                }
            } else if (items.isNotEmpty()) {
                TextButton(onClick = { expanded = true }, modifier = Modifier.padding(start = 4.dp)) { Text("Xem ${items.size} mục") }
            }
        }
    }
}
