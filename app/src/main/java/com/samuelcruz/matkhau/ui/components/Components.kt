package com.samuelcruz.matkhau.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import com.samuelcruz.matkhau.security.AccessibilityGuard
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.samuelcruz.matkhau.core.generator.Strength
import com.samuelcruz.matkhau.core.generator.StrengthEstimator

/**
 * Báo "người dùng đang thao tác" cho bộ hẹn giờ tự khóa. Activity.onUserInteraction chỉ thấy chạm/phím trên
 * cửa sổ chính, KHÔNG thấy chữ gõ từ bàn phím ảo hay chạm trong hộp thoại, nên các ô nhập và hộp thoại tự báo.
 */
val LocalUserActivity = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Cổng trước khi lộ bí mật (hiện / copy): (lý do, hành động). Khi bật tùy chọn "xác thực mỗi lần" thì hỏi vân tay
 * trước (SecretGuard); mặc định chạy luôn.
 */
val LocalSecretGuard = staticCompositionLocalOf<(String, () -> Unit) -> Unit> { { _, action -> action() } }

/**
 * Nút giao diện chứa dữ liệu nhạy cảm: chỉ công cụ Trợ năng thật (TalkBack...) thấy và thao tác được; dịch vụ Trợ năng
 * khác (trojan) không đọc được nội dung, không bấm thay được (Android 14+; máy cũ hơn: không có tác dụng).
 */
fun Modifier.sensitive(): Modifier = semantics { isSensitiveData = true }

/**
 * Đặt trong nội dung của AlertDialog / Dialog / Popup: đó là CỬA SỔ RIÊNG, không thừa hưởng đánh dấu nhạy cảm của
 * activity (FLAG_SECURE thì có), nên phải tự đánh dấu.
 */
@Composable
fun SensitiveWindow() {
    val view = LocalView.current
    SideEffect { AccessibilityGuard.protectView(view) }
}

/** Gắn vào nội dung hộp thoại / popup: mỗi lần chạm đều tính là thao tác (không chặn sự kiện). */
fun Modifier.trackUserActivity(onActivity: () -> Unit): Modifier = pointerInput(onActivity) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onActivity()
    }
}

/** OutlinedTextField có báo thao tác cho bộ hẹn giờ tự khóa mỗi khi nội dung đổi. */
@Composable
fun TrackedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    textStyle: TextStyle = LocalTextStyle.current,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val activity = LocalUserActivity.current
    OutlinedTextField(
        value = value,
        onValueChange = { activity(); onValueChange(it) },
        modifier = modifier.sensitive(),
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
    )
}

/** Khung chung cho mọi màn hình: thanh tiêu đề, nút back, nội dung cuộn được. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                        }
                    }
                },
                actions = { actions() },
            )
        },
        floatingActionButton = floatingActionButton,
    ) { padding ->
        val base = Modifier
            .padding(padding)
            .consumeWindowInsets(padding)   // để imePadding không cộng thêm phần thanh điều hướng lần nữa
            .fillMaxSize()
            .imePadding()
        Column(
            modifier = (if (scrollable) base.verticalScroll(rememberScrollState()).padding(16.dp) else base).sensitive(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Ô nhập bí mật: ẩn ký tự, kiểu bàn phím password (bàn phím không học/gợi ý từ). */
@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    /** true: bấm "hiện" phải qua cổng xác thực (ô chứa bí mật đã lưu). false: chữ người dùng vừa gõ. */
    guarded: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    monospace: Boolean = false,
    trailingExtra: @Composable () -> Unit = {},
) {
    var visible by remember { mutableStateOf(false) }
    val activity = LocalUserActivity.current
    val guard = LocalSecretGuard.current
    OutlinedTextField(
        value = value,
        onValueChange = { activity(); onValueChange(it) },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth().sensitive(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction, autoCorrectEnabled = false),
        textStyle = if (monospace) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        trailingIcon = {
            Row {
                trailingExtra()
                IconButton(onClick = {
                    when {
                        visible -> visible = false
                        guarded -> guard("Hiện $label") { visible = true }
                        else -> visible = true
                    }
                }) {
                    Icon(
                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) "Ẩn" else "Hiện",
                    )
                }
            }
        },
    )
}

/** Thanh độ mạnh mật khẩu (ước lượng thô). */
@Composable
fun StrengthMeter(password: String) {
    if (password.isEmpty()) return
    val result = remember(password) { StrengthEstimator.analyze(password) }
    val strength = result.strength
    val bits = result.bits.toInt()
    val color = when (strength) {
        Strength.VERY_WEAK, Strength.WEAK -> Color(0xFFD32F2F)
        Strength.FAIR -> Color(0xFFF9A825)
        Strength.STRONG, Strength.VERY_STRONG -> Color(0xFF2E7D32)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { (strength.ordinal + 1) / Strength.entries.size.toFloat() },
            color = color,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Độ mạnh: ${strength.label} (~$bits bit, ước lượng)", style = MaterialTheme.typography.bodySmall, color = color)
        // Lý do cụ thể (mật khẩu phổ biến, ngày sinh, chuỗi phím...) khi chưa đủ mạnh
        if (strength <= Strength.FAIR) {
            result.warnings.take(2).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = color) }
        }
    }
}

/** Một dòng thông tin có nút copy (và tùy chọn che giấu). */
@Composable
fun FieldRow(
    label: String,
    value: String,
    secret: Boolean = false,
    monospace: Boolean = false,
    onCopy: (() -> Unit)? = null,
) {
    if (value.isEmpty()) return
    var revealed by remember { mutableStateOf(false) }
    val guard = LocalSecretGuard.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().sensitive()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = if (secret && !revealed) "•".repeat(minOf(value.length, 16)) else value,
                style = MaterialTheme.typography.bodyLarge.let { if (monospace || (secret && revealed)) it.copy(fontFamily = FontFamily.Monospace) else it },
            )
        }
        if (secret) {
            IconButton(onClick = { if (revealed) revealed = false else guard("Hiện $label") { revealed = true } }) {
                Icon(if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Hiện/ẩn")
            }
        }
        if (onCopy != null) {
            IconButton(onClick = { if (secret) guard("Copy $label") { onCopy() } else onCopy() }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy $label")
            }
        }
    }
}

@Composable
fun ErrorText(text: String?) {
    if (text.isNullOrBlank()) return
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun SectionTitle(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

val ScreenPadding = PaddingValues(16.dp)

@Composable
fun CenteredBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun HSpace(w: Int) = Spacer(Modifier.width(w.dp))
