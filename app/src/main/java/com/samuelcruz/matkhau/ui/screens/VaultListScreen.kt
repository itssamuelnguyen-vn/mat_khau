package com.samuelcruz.matkhau.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samuelcruz.matkhau.model.Categories
import com.samuelcruz.matkhau.model.Entry
import com.samuelcruz.matkhau.model.EntryType
import com.samuelcruz.matkhau.ui.Screen
import com.samuelcruz.matkhau.ui.VaultViewModel
import com.samuelcruz.matkhau.ui.components.AppScaffold

private const val FILTER_ALL = "__all"
private const val FILTER_NOTES = "__notes"

@Composable
fun VaultListScreen(vm: VaultViewModel) {
    val entries by vm.repository.entries.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(FILTER_ALL) }
    var addMenu by remember { mutableStateOf(false) }

    val visible = entries.filter { e ->
        val passesFilter = when (filter) {
            FILTER_ALL -> true
            FILTER_NOTES -> e.type == EntryType.NOTE
            else -> e.category == filter
        }
        passesFilter && e.matches(query)
    }

    AppScaffold(
        title = "Mật Khẩu",
        scrollable = false,
        actions = {
            IconButton(onClick = { vm.navigate(Screen.Generator) }) { Icon(Icons.Filled.Password, "Tạo mật khẩu") }
            IconButton(onClick = { vm.navigate(Screen.Settings) }) { Icon(Icons.Filled.Settings, "Cài đặt") }
            IconButton(onClick = { vm.lock() }) { Icon(Icons.Filled.Lock, "Khóa ngay") }
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { addMenu = true }) { Icon(Icons.Filled.Add, "Thêm mục") }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Tài khoản đăng nhập") },
                        leadingIcon = { Icon(Icons.Filled.Key, null) },
                        onClick = { addMenu = false; vm.navigate(Screen.Edit(null, EntryType.LOGIN)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Ghi chú bảo mật") },
                        leadingIcon = { Icon(Icons.Filled.Description, null) },
                        onClick = { addMenu = false; vm.navigate(Screen.Edit(null, EntryType.NOTE)) },
                    )
                }
            }
        },
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Tìm theo tên, username, URL, #tag") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Xóa") }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            FilterChip(selected = filter == FILTER_ALL, onClick = { filter = FILTER_ALL }, label = { Text("Tất cả (${entries.size})") })
            FilterChip(selected = filter == FILTER_NOTES, onClick = { filter = FILTER_NOTES }, label = { Text("Ghi chú") })
            Categories.all.forEach { c ->
                FilterChip(selected = filter == c, onClick = { filter = c }, label = { Text(Categories.label(c)) })
            }
        }

        if (visible.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (entries.isEmpty()) "Vault trống. Bấm + để thêm mục đầu tiên." else "Không tìm thấy mục nào.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(visible, key = { it.id }) { entry ->
                    EntryRow(entry) { vm.navigate(Screen.Detail(entry.id)) }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: Entry, onClick: () -> Unit) {
    val subtitle = when {
        entry.type == EntryType.NOTE -> "Ghi chú bảo mật"
        entry.username.isNotBlank() -> entry.username
        entry.url.isNotBlank() -> entry.url
        else -> Categories.label(entry.category)
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.title.firstOrNull()?.uppercase() ?: "?",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.tags.isNotEmpty()) {
                    Text(
                        entry.tags.joinToString(" ") { "#$it" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        trailingContent = { if (entry.totp.isNotBlank()) Text("2FA", style = MaterialTheme.typography.labelSmall) },
    )
}
