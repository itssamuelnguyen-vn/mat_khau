package com.samuelcruz.matkhau.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Một dòng trong bảng items: chỉ có id ngẫu nhiên + dữ liệu đã mã hóa. */
class StoredItem(val id: String, val format: Int, val nonce: ByteArray, val ciphertext: ByteArray)

/**
 * SQLite thuần (SQLiteOpenHelper), không cần Room/annotation processor.
 * Giống ActiveRecord ở mức rất thấp: tự viết SQL, tự map dòng -> đối tượng.
 *
 * Bảng meta:  k (TEXT) -> v (BLOB)   — header vault, VEK bọc bởi Keystore (vân tay)
 * Bảng items: id, fmt, nonce, ct     — KHÔNG có cột plaintext nào
 *   fmt = 1: một mục (Entry JSON đã mã hóa)
 *   fmt = 2: một đoạn file đính kèm (xem core.vault.Attachments); vẫn đúng 2 bảng, không đổi schema.
 */
class VaultDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // Ghi đè vùng dữ liệu đã xóa bằng 0 (dữ liệu vốn đã mã hóa, đây là lớp phòng thủ thêm).
        try {
            db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() }
        } catch (_: Exception) {
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY NOT NULL, v BLOB NOT NULL)")
        db.execSQL("CREATE TABLE items (id TEXT PRIMARY KEY NOT NULL, fmt INTEGER NOT NULL, nonce BLOB NOT NULL, ct BLOB NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Chưa có migration ở phiên bản 1.
    }

    fun getMeta(key: String): ByteArray? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getBlob(0) else null
        }

    fun putMeta(key: String, value: ByteArray) {
        writableDatabase.insertWithOnConflict(
            "meta", null,
            ContentValues().apply { put("k", key); put("v", value) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Bảng của app (bỏ bảng nội bộ của SQLite / Android), sắp xếp theo tên. */
    fun tables(): List<String> =
        readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY name",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun metaKeys(): List<String> =
        readableDatabase.rawQuery("SELECT k FROM meta", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** Tên các cột của một bảng (để màn hình Security kiểm tra thật là không có cột plaintext). */
    fun columns(table: String): List<String> =
        readableDatabase.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val name = c.getColumnIndexOrThrow("name")
            buildList { while (c.moveToNext()) add(c.getString(name)) }
        }

    fun deleteMeta(key: String) {
        writableDatabase.delete("meta", "k = ?", arrayOf(key))
    }

    /**
     * Mọi dòng TRỪ các đoạn file đính kèm (không nạp vài chục MB file vào RAM lúc mở khóa). Dòng có fmt lạ vẫn được
     * trả về để bị đếm là hỏng.
     */
    fun entryItems(): List<StoredItem> =
        readableDatabase.rawQuery("SELECT id, fmt, nonce, ct FROM items WHERE fmt != ?", arrayOf(ATTACHMENT_FORMAT.toString())).use { c ->
            buildList {
                while (c.moveToNext()) add(StoredItem(c.getString(0), c.getInt(1), c.getBlob(2), c.getBlob(3)))
            }
        }

    /** id của mọi đoạn file đính kèm (không đọc nội dung). */
    fun attachmentRowIds(): Set<String> =
        readableDatabase.rawQuery("SELECT id FROM items WHERE fmt = ?", arrayOf(ATTACHMENT_FORMAT.toString())).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    /** Một dòng (mỗi đoạn ≤ 512 KB nên luôn vừa CursorWindow). */
    fun getItem(id: String): StoredItem? =
        readableDatabase.rawQuery("SELECT id, fmt, nonce, ct FROM items WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) StoredItem(c.getString(0), c.getInt(1), c.getBlob(2), c.getBlob(3)) else null
        }

    /** Chạy [block] trong MỘT transaction: hoặc ghi hết, hoặc không ghi gì. */
    fun <T> inTransaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun insertItem(item: StoredItem) {
        writableDatabase.insertOrThrow("items", null, item.toValues())
    }

    fun deleteItems(ids: Collection<String>) {
        if (ids.isEmpty()) return
        inTransaction { ids.forEach { writableDatabase.delete("items", "id = ?", arrayOf(it)) } }
    }

    fun upsertItem(item: StoredItem) {
        writableDatabase.insertWithOnConflict("items", null, item.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteItem(id: String) {
        writableDatabase.delete("items", "id = ?", arrayOf(id))
    }

    /** Thêm nhiều mục trong MỘT transaction (nhập CSV): hoặc vào hết, hoặc không mục nào. */
    fun insertItems(items: List<StoredItem>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (item in items) db.insertOrThrow("items", null, item.toValues())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Thay toàn bộ vault trong một transaction (dùng khi khôi phục backup): hoặc thành công hết, hoặc không đổi gì. */
    fun replaceVault(meta: Map<String, ByteArray>, items: Sequence<StoredItem>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("meta", null, null)
            db.delete("items", null, null)
            for ((k, v) in meta) db.insertOrThrow("meta", null, ContentValues().apply { put("k", k); put("v", v) })
            for (item in items) db.insertOrThrow("items", null, item.toValues())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun StoredItem.toValues() = ContentValues().apply {
        put("id", id)
        put("fmt", format)
        put("nonce", nonce)
        put("ct", ciphertext)
    }

    companion object {
        const val DB_NAME = "vault.db"
        const val DB_VERSION = 1
        const val ATTACHMENT_FORMAT = 2
    }
}
