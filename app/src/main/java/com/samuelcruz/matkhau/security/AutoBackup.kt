package com.samuelcruz.matkhau.security

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.samuelcruz.matkhau.data.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tự ghi file backup MÃ HÓA (.lvbackup, giống hệt "Xuất backup") vào một thư mục người dùng chọn, ví dụ thư mục mà
 * Google Drive / Syncthing đồng bộ. File chỉ mở được bằng mật khẩu chính hoặc Recovery Key lúc ghi.
 *
 * Chạy khi vault ĐANG MỞ (cần khóa vault để mã hóa danh sách mục): ~20 giây sau thay đổi cuối, hoặc ngay sau khi mở
 * khóa nếu còn thay đổi chưa backup. Giữ [KEEP] bản mới nhất, xóa bản tự động cũ hơn (không đụng file khác).
 */
class AutoBackup(
    private val context: Context,
    private val repository: VaultRepository,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var pending: Job? = null
    private val _runs = kotlinx.coroutines.flow.MutableStateFlow(0L)
    /** Tăng sau mỗi lần chạy (thành công hay lỗi), để giao diện đọc lại trạng thái backup. */
    val runs: kotlinx.coroutines.flow.StateFlow<Long> = _runs

    val isEnabled: Boolean get() = settings.autoBackupTreeUri != null

    /** Gọi trên main thread. Hẹn chạy sau [delayMs]; gọi lại thì dời hẹn. */
    fun schedule(delayMs: Long = DEBOUNCE_MS) {
        if (!isEnabled) return
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            // Dời hẹn chỉ hủy lúc đang CHỜ; lần ghi đã bắt đầu thì chạy cho xong (không xóa file vừa ghi đúng).
            withContext(NonCancellable) { runNow() }
        }
    }

    /** @return null nếu thành công (hoặc không cần chạy), thông báo lỗi nếu thất bại. */
    suspend fun runNow(): String? = mutex.withLock {
        val tree = settings.autoBackupTreeUri?.let(Uri::parse) ?: return null
        if (!repository.isUnlocked()) return null
        val changes = settings.changesSinceBackup
        val restoresBefore = repository.restoreCount
        val outcome = withContext(Dispatchers.IO) { write(tree) }
        // Khôi phục backup trong lúc đang ghi: file vừa ghi thuộc vault cũ, không tính là backup của vault mới.
        if (outcome.written && repository.restoreCount == restoresBefore) {
            val now = System.currentTimeMillis()
            // Bản thiếu mục hỏng vẫn chứa mọi thứ còn đọc được: tính là đã backup (vẫn báo lỗi để người dùng biết).
            settings.markBackedUp(changes, now)
            settings.autoBackupLastAt = now
        }
        settings.autoBackupLastError = outcome.error
        _runs.value++
        outcome.error
    }

    private class Outcome(val written: Boolean, val error: String?)

    private suspend fun write(tree: Uri): Outcome {
        val resolver = context.contentResolver
        val parent = try {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        } catch (e: Exception) {
            return Outcome(false, "Thư mục tự backup không còn hợp lệ. Hãy chọn lại.")
        }
        val name = PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + SUFFIX
        val doc = try {
            DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
        } catch (e: Exception) {
            null
        } ?: return Outcome(false, "Không tạo được file trong thư mục tự backup (thư mục bị xóa hoặc mất quyền?). Hãy chọn lại.")
        return try {
            val result = resolver.openOutputStream(doc, "w")?.use { raw ->
                BufferedOutputStream(raw, 256 * 1024).use { repository.exportBackup(it) }
            } ?: throw IllegalStateException()
            val corrupted = repository.corruptedItemCount
            val docId = runCatching { DocumentsContract.getDocumentId(doc) }.getOrNull()
            val complete = corrupted == 0 && result.skippedAttachments == 0
            if (complete && docId != null) settings.autoBackupLastCompleteDoc = docId
            // Luôn giữ bản vừa ghi và bản ĐẦY ĐỦ gần nhất (bản thiếu mục hỏng không được đẩy bản đủ ra ngoài).
            prune(tree, protect = setOfNotNull(docId, settings.autoBackupLastCompleteDoc))
            when {
                corrupted > 0 -> Outcome(true, "$corrupted mục bị hỏng không có trong bản tự backup (đã giữ bản đầy đủ gần nhất).")
                result.skippedAttachments > 0 -> Outcome(true, "${result.skippedAttachments} file đính kèm bị hỏng không có trong bản tự backup (đã giữ bản đầy đủ gần nhất).")
                else -> Outcome(true, null)
            }
        } catch (e: CancellationException) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            Outcome(false, "Tự backup thất bại (vault bị khóa giữa chừng hoặc hết dung lượng).")
        }
    }

    /**
     * Giữ các file trong [protect] (file vừa ghi, bản đầy đủ gần nhất) + các bản tự động mới nhất khác (theo tên chứa
     * thời gian) cho đủ [KEEP] bản, xóa bản cũ hơn. Không bao giờ xóa file vừa ghi, kể cả khi đồng hồ máy bị lùi.
     */
    private fun prune(tree: Uri, protect: Set<String>) {
        val resolver = context.contentResolver
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val found = ArrayList<Pair<String, String>>()
        runCatching {
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val n = c.getString(1) ?: continue
                    if (n.startsWith(PREFIX) && n.endsWith(SUFFIX)) found += id to n
                }
            }
        }
        val protectedHere = found.count { it.first in protect }
        found.filter { it.first !in protect }.sortedByDescending { it.second }.drop((KEEP - protectedHere).coerceAtLeast(0)).forEach { (id, _) ->
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
        }
    }

    companion object {
        const val KEEP = 5
        private const val DEBOUNCE_MS = 20_000L
        private const val PREFIX = "matkhau-auto-"
        private const val SUFFIX = ".lvbackup"
    }
}
