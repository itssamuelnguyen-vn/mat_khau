package com.samuelcruz.matkhau.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.samuelcruz.matkhau.core.crypto.Bytes
import com.samuelcruz.matkhau.ui.VaultViewModel
import com.samuelcruz.matkhau.ui.components.AppScaffold
import com.samuelcruz.matkhau.ui.components.ErrorText
import com.samuelcruz.matkhau.ui.components.Hint
import com.samuelcruz.matkhau.ui.components.SensitiveWindow
import com.samuelcruz.matkhau.ui.components.sensitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.cancellation.CancellationException
import java.text.DateFormat
import java.util.Date

/** Nội dung đã giải mã của file đang xem: chỉ trong RAM của màn hình này, xóa khi rời màn hình hoặc khi khóa. */
private sealed interface Preview {
    data object Loading : Preview
    data object Failed : Preview
    class Image(val bitmap: Bitmap) : Preview
    class Text(val text: String) : Preview
    data object Unsupported : Preview
}

/** Xem file đính kèm ngay trong app (ảnh, chữ). File khác: xuất ra ngoài (không mã hóa) sau cảnh báo. */
@Composable
fun AttachmentScreen(vm: VaultViewModel, entryId: String, attachmentId: String) {
    val entries by vm.repository.entries.collectAsState()
    val entry = entries.firstOrNull { it.id == entryId }
    val ref = entry?.attachments?.firstOrNull { it.id == attachmentId }
    val context = LocalContext.current
    var preview by remember(attachmentId) { mutableStateOf<Preview>(Preview.Loading) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ref?.mimeType?.takeIf { it.contains('/') } ?: "application/octet-stream"),
    ) { uri -> vm.exportAttachment(context, entryId, attachmentId, uri) }

    if (entry == null || ref == null) {
        AppScaffold(title = "Không tìm thấy", onBack = { vm.back() }) { Text("File này không còn tồn tại.") }
        return
    }

    LaunchedEffect(attachmentId) {
        preview = try {
            var data: ByteArray? = null
            try {
                // Đọc không bị hủy giữa chừng (để mảng đã giải mã luôn về tới đây và được ghi đè bằng 0).
                data = withContext(NonCancellable) { vm.readAttachment(entryId, attachmentId) }
                data?.let { withContext(Dispatchers.Default) { decodePreview(it, ref.mimeType) } } ?: Preview.Failed
            } finally {
                Bytes.wipe(data)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Preview.Failed   // hết RAM khi giải mã ảnh, lỗi đọc DB...
        }
    }
    DisposableEffect(attachmentId) {
        onDispose { (preview as? Preview.Image)?.bitmap?.recycle() }
    }

    AppScaffold(
        title = ref.name,
        onBack = { vm.back() },
        actions = {
            IconButton(onClick = { confirmExport = true }) { Icon(Icons.Filled.SaveAlt, "Xuất file") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Xóa file") }
        },
    ) {
        Hint("${formatSize(ref.size)} · ${ref.mimeType} · thêm lúc ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ref.addedAt))}")
        when (val p = preview) {
            Preview.Loading -> CircularProgressIndicator()
            Preview.Failed -> ErrorText("Không giải mã được file (bị hỏng hoặc bị sửa đổi).")
            is Preview.Image -> Image(
                bitmap = p.bitmap.asImageBitmap(),
                contentDescription = ref.name,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().sensitive(),
            )
            is Preview.Text -> Text(p.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.sensitive())
            Preview.Unsupported -> Hint("Không xem trước được loại file này trong app. Bấm biểu tượng lưu để xuất file ra ngoài.")
        }
    }

    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text("Xuất file ra ngoài?") },
            text = { SensitiveWindow(); Text("File xuất ra KHÔNG được mã hóa: ai mở được nơi lưu là đọc được. Hãy xóa bản đó khi dùng xong.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmExport = false
                    vm.suspendAutoLock()
                    exportLauncher.launch(ref.name)
                }) { Text("Xuất") }
            },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("Hủy") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Xóa “${ref.name}”?") },
            text = { SensitiveWindow(); Text("File bị xóa khỏi vault (backup cũ vẫn còn bản của nó).") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteAttachment(entryId, attachmentId) }) { Text("Xóa") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Hủy") } },
        )
    }
}

private const val MAX_PREVIEW_SIDE = 2048
private const val MAX_TEXT_PREVIEW = 512 * 1024

private fun decodePreview(data: ByteArray, mime: String): Preview {
    // Ảnh: thu nhỏ khi giải mã (ảnh 48 MP không làm hết RAM).
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        var sample = 1
        while (bounds.outWidth / sample > MAX_PREVIEW_SIDE || bounds.outHeight / sample > MAX_PREVIEW_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        return if (bitmap != null) Preview.Image(bitmap) else Preview.Unsupported
    }
    val textLike = mime.startsWith("text/") || mime == "application/json" || mime.endsWith("+xml") || mime == "application/x-pem-file"
    if (textLike && data.size <= MAX_TEXT_PREVIEW) {
        val decoder = Charsets.UTF_8.newDecoder()
        return runCatching { Preview.Text(decoder.decode(java.nio.ByteBuffer.wrap(data)).toString()) }.getOrDefault(Preview.Unsupported)
    }
    return Preview.Unsupported
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
