package com.samuelcruz.matkhau.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.samuelcruz.matkhau.R
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date

/**
 * In "phiếu khẩn cấp" có Recovery Key qua trình in của Android (app không có quyền mạng; dịch vụ in của hệ thống lo
 * phần gửi tới máy in). Trang được vẽ trực tiếp bằng Canvas (không WebView, không file tạm của app).
 */
object RecoveryKeyPrinter {
    /** [context] phải là Activity. [onFinished] chạy khi trình in đóng (in xong hoặc hủy). */
    fun print(context: Context, recoveryKey: String, onFinished: () -> Unit) {
        val manager = context.getSystemService(PrintManager::class.java) ?: run { onFinished(); return }
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
            .build()
        manager.print("Mật Khẩu - Recovery Key", Adapter(context.applicationContext, recoveryKey, onFinished), attributes)
    }

    private class Adapter(
        private val context: Context,
        private val key: String,
        private val onFinished: () -> Unit,
    ) : PrintDocumentAdapter() {
        private var attributes: PrintAttributes? = null

        override fun onLayout(
            oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback, extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
            attributes = newAttributes
            val info = PrintDocumentInfo.Builder("matkhau-recovery-key.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build()
            callback.onLayoutFinished(info, newAttributes != oldAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            val attrs = attributes ?: run { callback.onWriteFailed(null); return }
            val pdf = PrintedPdfDocument(context, attrs)
            try {
                val page = pdf.startPage(0)
                draw(page.canvas, page.info.pageWidth.toFloat())
                pdf.finishPage(page)
                if (cancellationSignal?.isCanceled == true) { callback.onWriteCancelled(); return }
                FileOutputStream(destination.fileDescriptor).use { pdf.writeTo(it) }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(null)
            } finally {
                pdf.close()
            }
        }

        override fun onFinish() = onFinished()

        private fun draw(canvas: android.graphics.Canvas, pageWidth: Float) {
            val margin = 56f   // đơn vị: điểm (1/72 inch)
            val width = (pageWidth - 2 * margin).toInt()
            var y = margin
            fun paragraph(text: String, size: Float, bold: Boolean = false, gapAfter: Float = 10f) {
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = size
                    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()
                canvas.save()
                canvas.translate(margin, y)
                layout.draw(canvas)
                canvas.restore()
                y += layout.height + gapAfter
            }

            paragraph("Mật Khẩu: phiếu khẩn cấp", 20f, bold = true, gapAfter = 4f)
            paragraph("Recovery Key, in lúc " + DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date()), 11f, gapAfter = 18f)

            // Key: font JetBrains Mono, 2 nhóm mỗi dòng; chữ số gạch dưới để không nhầm với chữ cái khi in đen trắng.
            val mono = ResourcesCompat.getFont(context, R.font.jetbrains_mono_bold) ?: Typeface.MONOSPACE
            val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; typeface = mono }
            val digitPaint = Paint(keyPaint).apply { isUnderlineText = true }
            val advance = keyPaint.measureText("M")
            key.split("-").chunked(2).forEach { pair ->
                y += keyPaint.textSize
                var x = margin
                pair.joinToString("  -  ").forEach { c ->
                    canvas.drawText(c.toString(), x, y, if (c.isDigit()) digitPaint else keyPaint)
                    x += advance
                }
                y += 12f
            }
            y += 14f

            paragraph("Key chỉ gồm chữ in hoa A–Z và số 2–7 (số được gạch dưới). Không có số 0, 1, 8, 9.", 11f)
            paragraph("Cách dùng khi quên mật khẩu chính: mở app Mật Khẩu → \"Quên mật khẩu? Dùng Recovery Key\" → nhập key → đặt mật khẩu chính mới. Key này cũng mở được file backup (.lvbackup) xuất trong thời gian nó còn hiệu lực.", 11f)
            paragraph("Tạo Recovery Key mới trong app thì key trên tờ này hết dùng được với vault (backup cũ vẫn mở được bằng nó).", 11f)
            paragraph("Cất tờ giấy này ở nơi an toàn (két, ngăn khóa), KHÔNG để chung với điện thoại. Ai có tờ này cùng file vault / backup là mở được toàn bộ mật khẩu của bạn. Không chụp ảnh, không lưu bản PDF.", 11f, bold = true)
        }
    }
}
