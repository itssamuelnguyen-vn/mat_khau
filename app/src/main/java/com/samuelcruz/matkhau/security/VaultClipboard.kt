package com.samuelcruz.matkhau.security

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.AndroidClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

/**
 * Clipboard cho mọi ô nhập / chữ chọn được trong app (thay LocalClipboard của Compose). Nút "Copy" / "Cắt" trên thanh
 * chọn chữ và Ctrl+C trên bàn phím rời đều đi qua đây, nên cũng được đánh dấu nhạy cảm và tự xóa sau N giây như nút
 * copy của app. Dán (paste) dùng clipboard hệ thống như bình thường.
 *
 * PHẢI là [AndroidClipboard]: Compose gọi `Clipboard.nativeClipboardManager` (vd. khi bôi đen chữ, để biết có hiện
 * nút Dán không), hàm đó ném IllegalArgumentException với Clipboard loại khác => app bị dừng (lỗi của 0.5.0).
 */
class VaultClipboard(
    private val context: Context,
    private val system: Clipboard,
    private val clearAfterSeconds: () -> Int,
) : AndroidClipboard {
    override val clipboardManager: ClipboardManager =
        context.getSystemService(ClipboardManager::class.java)

    override suspend fun getClipEntry(): ClipEntry? = system.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val text = clipEntry?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) {
            system.setClipEntry(clipEntry)
            return
        }
        SecureClipboard.copy(context, "văn bản đã chọn", text, clearAfterSeconds())
    }
}
