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

    fun deleteMeta(key: String) {
        writableDatabase.delete("meta", "k = ?", arrayOf(key))
    }

    fun allItems(): List<StoredItem> =
        readableDatabase.rawQuery("SELECT id, fmt, nonce, ct FROM items", null).use { c ->
            buildList {
                while (c.moveToNext()) add(StoredItem(c.getString(0), c.getInt(1), c.getBlob(2), c.getBlob(3)))
            }
        }

    fun upsertItem(item: StoredItem) {
        writableDatabase.insertWithOnConflict("items", null, item.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteItem(id: String) {
        writableDatabase.delete("items", "id = ?", arrayOf(id))
    }

    /** Thay toàn bộ vault trong một transaction (dùng khi khôi phục backup): hoặc thành công hết, hoặc không đổi gì. */
    fun replaceVault(meta: Map<String, ByteArray>, items: List<StoredItem>) {
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
    }
}
