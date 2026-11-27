package com.samuelcruz.matkhau.autofill

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.samuelcruz.matkhau.core.autofill.AutofillMatcher
import com.samuelcruz.matkhau.core.autofill.AutofillTarget
import com.samuelcruz.matkhau.core.autofill.DomainMatcher
import com.samuelcruz.matkhau.model.Entry
import com.samuelcruz.matkhau.model.EntryType
import com.samuelcruz.matkhau.model.LinkedApp
import com.samuelcruz.matkhau.ui.LockedScreen
import com.samuelcruz.matkhau.ui.VaultViewModel
import com.samuelcruz.matkhau.ui.components.AppScaffold
import com.samuelcruz.matkhau.ui.components.ErrorText
import com.samuelcruz.matkhau.ui.components.FieldRow
import com.samuelcruz.matkhau.ui.components.Hint
import com.samuelcruz.matkhau.ui.components.LocalUserActivity
import com.samuelcruz.matkhau.ui.components.SectionTitle
import com.samuelcruz.matkhau.ui.components.TrackedTextField
import com.samuelcruz.matkhau.ui.screens.RecoveryUnlockScreen
import com.samuelcruz.matkhau.ui.screens.UnlockScreen
import kotlinx.coroutines.launch

/** Khung chung: mở khóa vault nếu đang khóa, rồi mới hiện [content]. */
@Composable
private fun AutofillFrame(vm: VaultViewModel, onCancel: () -> Unit, content: @Composable () -> Unit) {
    val unlocked by vm.repository.unlocked.collectAsState()
    val initialized by vm.repository.initialized.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbarHost.showSnackbar(it) } }

    CompositionLocalProvider(LocalUserActivity provides vm::userActivity) {
        Box(Modifier.fillMaxSize()) {
            when {
                unlocked -> content()
                initialized == false -> AppScaffold(title = "Mật Khẩu", onBack = onCancel) {
                    Text("Chưa có vault. Mở app Mật Khẩu để tạo vault trước.")
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Đóng") }
                }
                initialized == null -> Unit
                vm.lockedScreen == LockedScreen.RecoveryUnlock -> RecoveryUnlockScreen(vm)
                else -> UnlockScreen(vm)
            }
            vm.busyText?.let { busy ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        if (busy.isNotEmpty()) Text(busy, color = Color.White)
                    }
                }
            }
            SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
        }
    }
}

private fun targetDescription(context: android.content.Context, target: AutofillTarget): Pair<String, String> = when (target) {
    is AutofillTarget.Web -> displayHost(target.host) to "Trang web (${AppIdentity.label(context, target.browserPackage)})"
    is AutofillTarget.UnverifiedBrowser -> displayHost(target.host) to "Trang web (${AppIdentity.label(context, target.packageName)}, chưa xác nhận)"
    is AutofillTarget.App -> AppIdentity.label(context, target.packageName) to target.packageName
}

/** Tên miền như trình duyệt báo, kèm dạng punycode nếu khác (lộ ra chữ Cyrillic / chữ lạ giả mạo). */
private fun displayHost(host: String): String {
    val ascii = DomainMatcher.hostOf(host)
    return if (ascii != null && ascii != host.lowercase()) "$host ($ascii)" else host
}

/**
 * Trình duyệt có tên gói đã biết nhưng chưa được xác nhận (hoặc chứng chỉ ký đã đổi): hiện danh tính để người dùng
 * quyết định có tin tên miền nó báo hay không. Xác nhận = ghim chứng chỉ ký hiện tại.
 */
@Composable
private fun TrustBrowserCard(target: AutofillTarget.UnverifiedBrowser, onTrust: () -> Unit) {
    val context = LocalContext.current
    val identity = remember(target.packageName) { AppIdentity.of(context, target.packageName) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (target.certChanged) "⚠ Chứng chỉ của trình duyệt đã thay đổi" else "Xác nhận trình duyệt",
                style = MaterialTheme.typography.titleSmall,
                color = if (target.certChanged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text("Ứng dụng: ${AppIdentity.label(context, target.packageName)}")
            Text("Tên gói: ${target.packageName}", fontFamily = FontFamily.Monospace)
            Text("Cài từ: ${AppIdentity.installerLabel(context, identity.installer)}")
            Text("Chứng chỉ: " + (target.certSha256?.let { fingerprint(it) } ?: "không đọc được"), fontFamily = FontFamily.Monospace)
            if (target.certChanged) {
                ErrorText("Lần trước bạn tin trình duyệt này với chứng chỉ KHÁC. Có thể đây là app giả mạo cùng tên gói. Chỉ tin lại nếu bạn vừa cài lại trình duyệt từ cửa hàng chính thức.")
            } else if (!target.fromStore) {
                ErrorText("Trình duyệt này không được cài từ cửa hàng đã biết. App giả mạo thường được cài từ file APK.")
            }
            Hint("Mật Khẩu chỉ tin tên miền (vd. ${target.host}) do trình duyệt bạn đã xác nhận báo lên.")
            if (target.certSha256 != null) {
                Button(onClick = onTrust, modifier = Modifier.fillMaxWidth()) { Text("Tin trình duyệt này") }
            }
        }
    }
}

private fun fingerprint(sha: String) = sha.take(24).chunked(2).joinToString(":") + "…"

// ------------------------------------------------------------------------------------------ chọn mục để điền

@Composable
fun AutofillPickRoot(
    vm: VaultViewModel,
    screen: ParsedScreen,
    preferredEntryId: String?,
    onFill: (Entry) -> Boolean,
    onLinkAndFill: (Entry) -> Boolean,
    onSetUrlAndFill: (Entry, String) -> Boolean,
    onTrustBrowser: () -> Unit,
    onCancel: () -> Unit,
) {
    AutofillFrame(vm, onCancel) {
        val context = LocalContext.current
        val entries by vm.repository.entries.collectAsState()
        val target = screen.target
        val (label, detail) = remember(target) { targetDescription(context, target) }
        var query by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf<Entry?>(null) }

        // Vừa mở khóa từ một dòng đề xuất cụ thể: điền luôn nếu mục đó vẫn khớp.
        LaunchedEffect(preferredEntryId) {
            entries.firstOrNull { it.id == preferredEntryId }?.takeIf { AutofillMatcher.matches(it, target) }?.let { onFill(it) }
        }

        val logins = entries.filter { it.type == EntryType.LOGIN }
        val matching = AutofillMatcher.matching(logins, target)
        // Gợi ý theo tên chỉ cho app cài từ cửa hàng: app giả mạo (cài ngoài) không được "nhắc" mục của app thật.
        val suggested = if (target is AutofillTarget.App && target.fromStore) {
            logins.filter { it !in matching && AutofillMatcher.suggestedForApp(it, target.packageName) }
        } else emptyList()
        val others = logins.filter { it !in matching && it !in suggested && (query.isBlank() || it.matches(query)) }

        fun choose(e: Entry) {
            if (AutofillMatcher.matches(e, target)) {
                if (!onFill(e)) vm.snackbar = "Mục này không có username / mật khẩu để điền"
            } else {
                confirm = e
            }
        }

        AppScaffold(title = "Tự động điền", onBack = onCancel) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Điền vào: $label", style = MaterialTheme.typography.titleMedium)
                    Hint(detail)
                }
            }
            if (target is AutofillTarget.UnverifiedBrowser) TrustBrowserCard(target, onTrustBrowser)
            if (matching.isNotEmpty()) {
                SectionTitle("Khớp với ${if (target is AutofillTarget.Web) "trang này" else "ứng dụng này"}")
                matching.forEach { EntryItem(it) { choose(it) } }
            } else {
                Hint(
                    when (target) {
                        is AutofillTarget.Web -> "Không có mục nào lưu cho tên miền này. Mẹo: lưu URL là tên miền gốc (vd. google.com) để dùng cho mọi trang con."
                        is AutofillTarget.UnverifiedBrowser -> "Xác nhận trình duyệt ở trên để thấy các mục của trang này."
                        is AutofillTarget.App -> "Chưa có mục nào được liên kết với ứng dụng này. Chọn một mục bên dưới để liên kết."
                    },
                )
            }
            if (suggested.isNotEmpty()) {
                SectionTitle("Có thể là")
                suggested.forEach { EntryItem(it) { choose(it) } }
            }
            SectionTitle("Mục khác")
            TrackedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Tìm theo tên, username, URL, #tag") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            others.take(50).forEach { EntryItem(it) { choose(it) } }
            if (others.size > 50) Hint("… gõ để tìm trong ${others.size} mục")
        }

        confirm?.let { e ->
            MismatchDialog(
                entry = e,
                target = target,
                label = label,
                onDismiss = { confirm = null },
                onFillOnce = { confirm = null; if (!onFill(e)) vm.snackbar = "Mục này không có username / mật khẩu để điền" },
                onLinkAndFill = { confirm = null; if (!onLinkAndFill(e)) vm.snackbar = "Không liên kết được" },
                onSetUrlAndFill = { url -> confirm = null; if (!onSetUrlAndFill(e, url)) vm.snackbar = "Mục này không có username / mật khẩu để điền" },
            )
        }
    }
}

@Composable
private fun EntryItem(e: Entry, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(e.title) },
        supportingContent = { Text(listOf(e.username, e.url).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "—" }) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Mục được chọn KHÔNG khớp nơi đang điền (spec 4.2):
 *  - App: hiện rõ tên app, tên gói, chứng chỉ; cho liên kết (ghim chứng chỉ) hoặc chỉ điền lần này.
 *  - Web, mục đã có URL khác: CHẶN, không có nút điền.
 *  - Web, mục chưa có URL: cho gán tên miền này vào mục sau cảnh báo.
 */
@Composable
private fun MismatchDialog(
    entry: Entry,
    target: AutofillTarget,
    label: String,
    onDismiss: () -> Unit,
    onFillOnce: () -> Unit,
    onLinkAndFill: () -> Unit,
    onSetUrlAndFill: (String) -> Unit,
) {
    when (target) {
        is AutofillTarget.UnverifiedBrowser -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Chưa xác nhận trình duyệt") },
            text = { Text("Hãy xác nhận trình duyệt ở đầu màn hình trước. Chưa xác nhận thì Mật Khẩu không tin tên miền ${target.host} mà nó báo.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Đã hiểu") } },
        )
        is AutofillTarget.App -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Điền “${entry.title}” vào $label?") },
            text = {
                val context = LocalContext.current
                val installer = remember(target.packageName) { AppIdentity.of(context, target.packageName).installer }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Ứng dụng: $label")
                    Text("Tên gói: ${target.packageName}", fontFamily = FontFamily.Monospace)
                    Text("Cài từ: ${AppIdentity.installerLabel(context, installer)}")
                    Text("Chứng chỉ: " + (target.certSha256?.let { fingerprint(it) } ?: "không đọc được"), fontFamily = FontFamily.Monospace)
                    if (!target.fromStore) {
                        ErrorText("⚠ App này KHÔNG được cài từ cửa hàng đã biết. App giả mạo ngân hàng / mạng xã hội thường được cài từ file APK. Đừng điền nếu không chắc.")
                    }
                    Text("Chỉ tiếp tục nếu bạn chắc đây là ứng dụng thật. Liên kết = lần sau tự đề xuất mục này cho đúng app này (cùng chứng chỉ ký).")
                }
            },
            confirmButton = {
                if (target.certSha256 != null) TextButton(onClick = onLinkAndFill) { Text("Liên kết & điền") }
                else TextButton(onClick = onFillOnce) { Text("Điền lần này") }
            },
            dismissButton = {
                Row2 {
                    TextButton(onClick = onDismiss) { Text("Hủy") }
                    if (target.certSha256 != null) TextButton(onClick = onFillOnce) { Text("Chỉ lần này") }
                }
            },
        )
        is AutofillTarget.Web -> {
            val savedHost = DomainMatcher.hostOf(entry.url)
            if (DomainMatcher.hostMatches(entry.url, target.host) && DomainMatcher.isInsecureDowngrade(entry.url, target.scheme)) {
                AlertDialog(
                    onDismissRequest = onDismiss,
                    title = { Text("⚠ Đã chặn: trang không mã hóa") },
                    text = { Text("Mục “${entry.title}” lưu cho https, nhưng trang đang mở bằng http (không mã hóa): mật khẩu có thể bị đọc trên đường truyền. Hãy mở lại trang bằng https://.") },
                    confirmButton = { TextButton(onClick = onDismiss) { Text("Đã hiểu") } },
                )
            } else if (savedHost == null && entry.url.isBlank()) {
                val candidates = remember(target.host) { DomainMatcher.saveCandidates(target.host) }
                var chosen by remember { mutableStateOf(candidates.firstOrNull() ?: target.host) }
                AlertDialog(
                    onDismissRequest = onDismiss,
                    title = { Text("Gán trang này cho “${entry.title}”?") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Mục chưa có URL. Trang đang mở là:\n${displayHost(target.host)}\n\nKiểm tra kỹ tên miền: trang giả mạo " +
                                    "thường chỉ khác một chữ. Chọn phạm vi lần sau tự đề xuất mục này:",
                            )
                            DomainChoice(candidates, chosen) { chosen = it }
                        }
                    },
                    confirmButton = { TextButton(onClick = { onSetUrlAndFill(webUrl(target, chosen)) }) { Text("Gán & điền") } },
                    dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
                )
            } else {
                AlertDialog(
                    onDismissRequest = onDismiss,
                    title = { Text("⚠ Đã chặn tự động điền") },
                    text = {
                        Text(
                            "Trang đang mở: ${displayHost(target.host)}\nMục “${entry.title}” lưu cho: ${savedHost ?: entry.url}\n\n" +
                                "Tên miền không khớp nên đây có thể là trang giả mạo. Nếu đúng là website của bạn " +
                                "(vd. đã đổi tên miền), hãy sửa URL của mục trong app Mật Khẩu.",
                        )
                    },
                    confirmButton = { TextButton(onClick = onDismiss) { Text("Đã hiểu") } },
                )
            }
        }
    }
}

/** URL lưu cho trang web: giữ đúng scheme (trang http lưu https thì lần sau sẽ bị chặn điền). */
private fun webUrl(target: AutofillTarget.Web, host: String = target.host) =
    "${target.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: "https"}://$host"

/**
 * Chọn URL lưu cho mục: đúng tên miền đang mở (mặc định, an toàn nhất) hoặc tên miền cha (dùng cho mọi trang con,
 * vd. lưu từ m.facebook.com nhưng muốn dùng cả ở www.facebook.com).
 */
@Composable
private fun DomainChoice(candidates: List<String>, chosen: String, onChoose: (String) -> Unit) {
    candidates.forEachIndexed { i, host ->
        androidx.compose.foundation.layout.Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onChoose(host) },
        ) {
            androidx.compose.material3.RadioButton(selected = chosen == host, onClick = { onChoose(host) })
            Column {
                Text(host, fontFamily = FontFamily.Monospace)
                Hint(if (i == 0) "Chỉ đúng trang này (và trang con của nó)" else "Mọi trang *.$host")
            }
        }
    }
}

@Composable
private fun Row2(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row { content() }
}

// ------------------------------------------------------------------------------------------ lưu đăng nhập

@Composable
fun AutofillSaveRoot(vm: VaultViewModel, save: PendingSave, onDone: () -> Unit) {
    // Ngoài AutofillFrame: khóa / mở lại trong màn hình này không làm mất lựa chọn "tin trình duyệt".
    var target by remember { mutableStateOf(save.target) }
    AutofillFrame(vm, onDone) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val entries by vm.repository.entries.collectAsState()
        // Đăng nhập 2 bước (Google, Microsoft, ngân hàng): màn mật khẩu không có ô username => cho sửa tay.
        var username by remember { mutableStateOf(save.username) }
        val (label, detail) = remember(target) { targetDescription(context, target) }
        val matching = AutofillMatcher.matching(entries, target)
        val sameUser = matching.filter { it.username.equals(username.trim(), ignoreCase = true) }
        val unchanged = sameUser.any { it.password == save.password }
        val toUpdate = sameUser.firstOrNull { it.password != save.password }
        var title by remember {
            mutableStateOf(
                when (val t = save.target) {
                    is AutofillTarget.Web -> DomainMatcher.baseDomain(t.host)
                    is AutofillTarget.UnverifiedBrowser -> DomainMatcher.baseDomain(t.host)
                    is AutofillTarget.App -> AppIdentity.label(context, t.packageName)
                },
            )
        }
        var saving by remember { mutableStateOf(false) }
        val urlCandidates = remember(target) { (target as? AutofillTarget.Web)?.let { DomainMatcher.saveCandidates(it.host) }.orEmpty() }
        var urlHost by remember(target) { mutableStateOf(urlCandidates.firstOrNull() ?: (target as? AutofillTarget.Web)?.host.orEmpty()) }

        fun finishWith(message: String, action: suspend () -> Unit) {
            if (saving) return
            saving = true
            scope.launch {
                try {
                    action()
                    val app = context.applicationContext as com.samuelcruz.matkhau.MatKhauApp
                    if (app.pendingAutofillSave === save) app.pendingAutofillSave = null   // không xóa yêu cầu lưu mới hơn
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                    onDone()
                } catch (e: Exception) {
                    saving = false
                    vm.snackbar = "Chưa lưu được: vault đã bị khóa hoặc có lỗi"
                }
            }
        }

        AppScaffold(title = "Lưu đăng nhập", onBack = onDone) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Hint(detail)
                }
            }
            TrackedTextField(username, { username = it }, label = { Text("Username / email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FieldRow("Mật khẩu", save.password, secret = true)

            val t = target
            if (t is AutofillTarget.UnverifiedBrowser) {
                TrustBrowserCard(t) {
                    t.certSha256?.let { cert ->
                        vm.settings.trustBrowser(t.packageName, cert)
                        target = AutofillTarget.Web(t.host, t.scheme, t.packageName)
                    }
                }
                TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Không lưu") }
                return@AppScaffold
            }

            when {
                unchanged -> {
                    Text("Đăng nhập này đã có trong vault, không cần lưu.")
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Đóng") }
                }
                toUpdate != null -> {
                    Text("Cập nhật mật khẩu cho “${toUpdate.title}”? Mật khẩu cũ được giữ trong lịch sử.")
                    Button(
                        onClick = {
                            finishWith("Đã cập nhật mật khẩu") {
                                val now = System.currentTimeMillis()
                                vm.repository.save(toUpdate.withPassword(save.password, now).copy(updatedAt = now))
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Cập nhật") }
                    TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Không lưu") }
                }
                else -> {
                    TrackedTextField(title, { title = it }, label = { Text("Tên mục") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (t is AutofillTarget.Web && urlCandidates.size > 1) {
                        Text("Tự đề xuất mục này ở:", style = MaterialTheme.typography.titleSmall)
                        DomainChoice(urlCandidates, urlHost) { urlHost = it }
                    }
                    if (t is AutofillTarget.App && t.certSha256 == null) {
                        ErrorText("Không đọc được chứng chỉ của ứng dụng: mục sẽ được lưu nhưng chưa liên kết để tự điền.")
                    }
                    Button(
                        onClick = {
                            finishWith("Đã lưu vào Mật Khẩu") {
                                val now = System.currentTimeMillis()
                                vm.repository.save(
                                    Entry(
                                        id = java.util.UUID.randomUUID().toString(),
                                        title = title.trim().ifEmpty { label },
                                        username = username.trim(),
                                        password = save.password,
                                        url = (t as? AutofillTarget.Web)?.let { webUrl(it, urlHost) }.orEmpty(),
                                        linkedApps = (t as? AutofillTarget.App)?.certSha256?.let { listOf(LinkedApp(t.packageName, it)) }.orEmpty(),
                                        createdAt = now,
                                        updatedAt = now,
                                        passwordUpdatedAt = now,
                                    ),
                                )
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Lưu mục mới") }
                    TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Không lưu") }
                }
            }
        }
    }
}
