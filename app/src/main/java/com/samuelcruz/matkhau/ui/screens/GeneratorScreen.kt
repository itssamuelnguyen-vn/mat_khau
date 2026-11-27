package com.samuelcruz.matkhau.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.samuelcruz.matkhau.core.generator.PassphraseOptions
import com.samuelcruz.matkhau.core.generator.PasswordGenerator
import com.samuelcruz.matkhau.core.generator.PasswordOptions
import com.samuelcruz.matkhau.ui.VaultViewModel
import com.samuelcruz.matkhau.ui.components.AppScaffold
import com.samuelcruz.matkhau.ui.components.Hint
import com.samuelcruz.matkhau.ui.components.LocalUserActivity
import com.samuelcruz.matkhau.ui.components.trackUserActivity
import com.samuelcruz.matkhau.ui.components.SensitiveWindow
import com.samuelcruz.matkhau.ui.components.sensitive

/** Trạng thái bộ sinh mật khẩu, dùng chung cho màn hình riêng và hộp thoại trong form. */
private class GeneratorState {
    var passphraseMode by mutableStateOf(false)
    var length by mutableIntStateOf(24)
    var upper by mutableStateOf(true)
    var lower by mutableStateOf(true)
    var digits by mutableStateOf(true)
    var symbols by mutableStateOf(true)
    var excludeAmbiguous by mutableStateOf(false)
    var words by mutableIntStateOf(6)
    var separator by mutableStateOf("-")
    var result by mutableStateOf("")

    val passwordOptions get() = PasswordOptions(length, upper, lower, digits, symbols, excludeAmbiguous)
    val passphraseOptions get() = PassphraseOptions(words = words, separator = separator)

    fun regenerate(wordList: List<String>) {
        result = try {
            if (passphraseMode) PasswordGenerator.passphrase(wordList, passphraseOptions)
            else PasswordGenerator.generate(passwordOptions)
        } catch (e: IllegalArgumentException) {
            ""
        }
    }

    fun entropy(wordListSize: Int): Int =
        if (passphraseMode) PasswordGenerator.passphraseEntropyBits(wordListSize, passphraseOptions).toInt()
        else PasswordGenerator.entropyBits(passwordOptions).toInt()
}

@Composable
private fun GeneratorPanel(vm: VaultViewModel, state: GeneratorState, onCopy: (() -> Unit)?) {
    val words = vm.wordList
    // Sinh lại mỗi khi đổi tùy chọn
    LaunchedEffect(state.passphraseMode, state.length, state.upper, state.lower, state.digits, state.symbols, state.excludeAmbiguous, state.words, state.separator) {
        state.regenerate(words)
    }

    val activity = LocalUserActivity.current
    SensitiveWindow()   // khi nằm trong GeneratorDialog (cửa sổ riêng)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.trackUserActivity(activity)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !state.passphraseMode, onClick = { state.passphraseMode = false }, label = { Text("Mật khẩu") })
            FilterChip(selected = state.passphraseMode, onClick = { state.passphraseMode = true }, label = { Text("Cụm từ (passphrase)") })
        }
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.result.ifEmpty { "Chọn ít nhất một loại ký tự" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).sensitive(),
                )
                IconButton(onClick = { state.regenerate(words) }) { Icon(Icons.Filled.Refresh, "Sinh lại") }
                if (onCopy != null) IconButton(onClick = onCopy, enabled = state.result.isNotEmpty()) { Icon(Icons.Filled.ContentCopy, "Copy") }
            }
        }
        Hint("Độ ngẫu nhiên: ~${state.entropy(words.size)} bit (≥ 80 bit là rất tốt)")

        if (state.passphraseMode) {
            Text("Số từ: ${state.words}")
            Slider(value = state.words.toFloat(), onValueChange = { state.words = it.toInt() }, valueRange = 4f..12f, steps = 7)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("-", ".", "_", " ").forEach { sep ->
                    FilterChip(
                        selected = state.separator == sep,
                        onClick = { state.separator = sep },
                        label = { Text(if (sep == " ") "dấu cách" else sep) },
                    )
                }
            }
            Hint("Từ điển BIP39 tiếng Anh: 2048 từ, mỗi từ ≈ 11 bit.")
        } else {
            Text("Độ dài: ${state.length}")
            Slider(
                value = state.length.toFloat(),
                onValueChange = { state.length = it.toInt() },
                valueRange = PasswordGenerator.MIN_LENGTH.toFloat()..64f,
            )
            ToggleRow("Chữ hoa (A–Z)", state.upper) { state.upper = it }
            ToggleRow("Chữ thường (a–z)", state.lower) { state.lower = it }
            ToggleRow("Chữ số (0–9)", state.digits) { state.digits = it }
            ToggleRow("Ký hiệu (!@#…)", state.symbols) { state.symbols = it }
            ToggleRow("Bỏ ký tự dễ nhầm (I l 1 O 0)", state.excludeAmbiguous) { state.excludeAmbiguous = it }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun GeneratorScreen(vm: VaultViewModel) {
    val state = remember { GeneratorState() }
    val context = LocalContext.current.applicationContext
    AppScaffold(title = "Tạo mật khẩu", onBack = { vm.back() }) {
        GeneratorPanel(vm, state, onCopy = { vm.copy(context, "mật khẩu", state.result) })
    }
}

@Composable
fun GeneratorDialog(vm: VaultViewModel, onDismiss: () -> Unit, onUse: (String) -> Unit) {
    val state = remember { GeneratorState() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tạo mật khẩu") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                GeneratorPanel(vm, state, onCopy = null)
            }
        },
        confirmButton = {
            TextButton(onClick = { onUse(state.result) }, enabled = state.result.isNotEmpty()) { Text("Dùng mật khẩu này") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
}
