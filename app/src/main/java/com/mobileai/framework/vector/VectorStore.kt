package com.mobileai.framework.vector

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * A single record stored in the on-device vector database.
 */
data class VectorRecord(
    val id: String,
    val text: String,
    val embedding: FloatArray,
    val namespace: String = "default",
    val metadata: String = "",   // free-form JSON string for extra context
    val score: Float = 0f        // populated on query results
) {
    override fun equals(other: Any?) = other is VectorRecord && id == other.id
    override fun hashCode() = id.hashCode()
}

/**
 * On-device SQLite-backed vector store with cosine similarity search.
 *
 * Zero external dependencies. Works offline on any Android device.
 * Designed for namespaced collections:
 *   "wiki"   → Physics topic embeddings
 *   "chat"   → Persistent conversation context
 *   "docs"   → Custom document chunks
 */
class VectorStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME     = "mobileai_vectors.db"
        private const val DB_VERSION  = 1
        private const val TABLE       = "vectors"
        private const val COL_ID      = "id"
        private const val COL_TEXT    = "text"
        private const val COL_EMB     = "embedding"
        private const val COL_NS      = "namespace"
        private const val COL_META    = "metadata"
        private const val COL_CREATED = "created_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $TABLE (
                $COL_ID      TEXT    PRIMARY KEY,
                $COL_TEXT    TEXT    NOT NULL,
                $COL_EMB     BLOB    NOT NULL,
                $COL_NS      TEXT    NOT NULL DEFAULT 'default',
                $COL_META    TEXT    NOT NULL DEFAULT '',
                $COL_CREATED INTEGER NOT NULL DEFAULT (strftime('%s','now'))
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ns ON $TABLE ($COL_NS)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    // ── Write Operations ──────────────────────────────────────────────────────

    suspend fun upsert(record: VectorRecord) = withContext(Dispatchers.IO) {
        writableDatabase.insertWithOnConflict(TABLE, null, record.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun upsertAll(records: List<VectorRecord>) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            records.forEach { db.insertWithOnConflict(TABLE, null, it.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id))
    }

    suspend fun deleteNamespace(namespace: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE, "$COL_NS = ?", arrayOf(namespace))
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Cosine similarity search across all records in [namespace].
     * Returns top-[topK] records sorted by descending similarity score.
     *
     * cos(A,B) = (A·B) / (|A|·|B|)   ∈ [-1, 1]
     */
    suspend fun search(
        queryEmbedding: FloatArray,
        topK: Int = 5,
        namespace: String = "default",
        threshold: Float = 0.0f
    ): List<VectorRecord> = withContext(Dispatchers.Default) {
        val all = loadAll(namespace)
        val qNorm = norm(queryEmbedding)
        if (qNorm == 0f || all.isEmpty()) return@withContext emptyList()

        all.mapNotNull { r ->
            if (r.embedding.size != queryEmbedding.size) return@mapNotNull null
            val rNorm = norm(r.embedding)
            if (rNorm == 0f) return@mapNotNull null
            val cosine = dot(queryEmbedding, r.embedding) / (qNorm * rNorm)
            if (cosine >= threshold) r.copy(score = cosine) else null
        }
            .sortedByDescending { it.score }
            .take(topK)
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    suspend fun count(namespace: String = "default"): Int = withContext(Dispatchers.IO) {
        val c = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE $COL_NS=?", arrayOf(namespace))
        c.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    suspend fun listNamespaces(): List<String> = withContext(Dispatchers.IO) {
        val c = readableDatabase.rawQuery("SELECT DISTINCT $COL_NS FROM $TABLE", null)
        val result = mutableListOf<String>()
        c.use { while (it.moveToNext()) result.add(it.getString(0)) }
        result
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun loadAll(namespace: String): List<VectorRecord> = withContext(Dispatchers.IO) {
        val c = readableDatabase.query(TABLE, null, "$COL_NS=?", arrayOf(namespace), null, null, null)
        val list = mutableListOf<VectorRecord>()
        c.use { while (it.moveToNext()) list.add(it.toRecord()) }
        list
    }

    private fun VectorRecord.toContentValues() = ContentValues().apply {
        put(COL_ID,   id)
        put(COL_TEXT, text)
        put(COL_EMB,  embedding.toBlob())
        put(COL_NS,   namespace)
        put(COL_META, metadata)
    }

    private fun Cursor.toRecord() = VectorRecord(
        id        = getString(getColumnIndexOrThrow(COL_ID)),
        text      = getString(getColumnIndexOrThrow(COL_TEXT)),
        embedding = getBlob(getColumnIndexOrThrow(COL_EMB)).toFloatArray(),
        namespace = getString(getColumnIndexOrThrow(COL_NS)),
        metadata  = getString(getColumnIndexOrThrow(COL_META))
    )

    private fun FloatArray.toBlob(): ByteArray {
        val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
        forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun ByteArray.toFloatArray(): FloatArray {
        val buf = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(size / 4) { buf.getFloat() }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s
    }

    private fun norm(v: FloatArray): Float {
        var s = 0f; for (x in v) s += x * x; return sqrt(s)
    }
}
