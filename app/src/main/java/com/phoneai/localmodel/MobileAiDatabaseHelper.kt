package com.phoneai.localmodel

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class KnowledgeEntry(
    val id: Long = 0,
    val title: String,
    val category: String, // e.g. "Hardware", "Inference", "NPU", "Agents", "RAG"
    val content: String,
    val summary: String,
    val timestamp: String,
    val isEmbedded: Boolean = false
)

/**
 * Primary SQLite storage for Mobile AI Framework:
 * - Persistent chat message history
 * - On-device vector knowledge base & RAG documents
 */
class MobileAiDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "mobile_ai_framework.db"
        private const val DATABASE_VERSION = 2

        private const val TABLE_CHAT = "chat_history"
        private const val TABLE_KNOWLEDGE = "knowledge_base"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_CHAT (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                text TEXT NOT NULL,
                is_user INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                stats_info TEXT
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_KNOWLEDGE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                category TEXT NOT NULL,
                content TEXT NOT NULL,
                summary TEXT NOT NULL,
                timestamp TEXT NOT NULL,
                is_embedded INTEGER DEFAULT 0
            )
            """.trimIndent()
        )

        seedDefaultKnowledge(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CHAT")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_KNOWLEDGE")
        onCreate(db)
    }

    private fun seedDefaultKnowledge(db: SQLiteDatabase) {
        val defaultDocs = listOf(
            KnowledgeEntry(
                title = "Welcome to Mobile AI",
                category = "Notes",
                summary = "Your private, on-device AI workspace running offline on your smartphone.",
                content = "Mobile AI runs high-speed AI models entirely on your phone. You can chat offline, extract text from photos with Google Lens, record and transcribe voice notes, and store your personal knowledge base securely on your device.",
                timestamp = "Getting Started"
            ),
            KnowledgeEntry(
                title = "Voice Notes & Audio",
                category = "Notes",
                summary = "Record voice memos and ask AI to summarize them.",
                content = "Use the Audio tab to record voice memos and speech. Transcripts can be saved directly as notes or sent to Chat with a single tap to generate concise AI summaries.",
                timestamp = "User Tip"
            ),
            KnowledgeEntry(
                title = "Google Lens & OCR",
                category = "Notes",
                summary = "Extract text from camera snapshots and photos.",
                content = "Point the camera at documents, signs, or receipts and tap the Lens shutter button. Offline Google ML Kit recognizes the text instantly, allowing you to copy it or ask AI questions about it.",
                timestamp = "User Tip"
            )
        )

        for (doc in defaultDocs) {
            val cv = ContentValues().apply {
                put("title", doc.title)
                put("category", doc.category)
                put("content", doc.content)
                put("summary", doc.summary)
                put("timestamp", doc.timestamp)
                put("is_embedded", if (doc.isEmbedded) 1 else 0)
            }
            db.insert(TABLE_KNOWLEDGE, null, cv)
        }
    }

    // ── Chat History ─────────────────────────────────────────────────────────

    fun insertChatMessage(msg: ChatMessage): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("uuid", msg.id)
            put("text", msg.text)
            put("is_user", if (msg.isUser) 1 else 0)
            put("timestamp", msg.timestamp)
            put("stats_info", msg.statsInfo)
        }
        return db.insert(TABLE_CHAT, null, cv)
    }

    fun getChatHistory(): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        val db = readableDatabase
        val cursor: Cursor = db.query(
            TABLE_CHAT,
            arrayOf("uuid", "text", "is_user", "timestamp", "stats_info"),
            null, null, null, null, "timestamp ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val uuid = it.getString(0)
                val text = it.getString(1)
                val isUser = it.getInt(2) == 1
                val timestamp = it.getLong(3)
                val statsInfo = it.getString(4)
                list.add(
                    ChatMessage(
                        id = uuid,
                        text = text,
                        isUser = isUser,
                        timestamp = timestamp,
                        isGenerating = false,
                        statsInfo = statsInfo
                    )
                )
            }
        }
        return list
    }

    fun clearChatHistory() {
        val db = writableDatabase
        db.delete(TABLE_CHAT, null, null)
    }

    // ── Knowledge Base (Vector Docs) ─────────────────────────────────────────

    fun insertKnowledge(entry: KnowledgeEntry): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("title", entry.title)
            put("category", entry.category)
            put("content", entry.content)
            put("summary", entry.summary)
            put("timestamp", entry.timestamp)
            put("is_embedded", if (entry.isEmbedded) 1 else 0)
        }
        return db.insert(TABLE_KNOWLEDGE, null, cv)
    }

    fun getAllKnowledge(query: String? = null, category: String? = null): List<KnowledgeEntry> {
        val list = mutableListOf<KnowledgeEntry>()
        val db = readableDatabase

        val selectionClauses = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        if (!query.isNullOrBlank()) {
            selectionClauses.add("(title LIKE ? OR content LIKE ? OR summary LIKE ?)")
            val q = "%${query.trim()}%"
            selectionArgs.add(q)
            selectionArgs.add(q)
            selectionArgs.add(q)
        }

        if (!category.isNullOrBlank() && !category.equals("All", ignoreCase = true)) {
            selectionClauses.add("category = ?")
            selectionArgs.add(category.trim())
        }

        val selection = if (selectionClauses.isNotEmpty()) selectionClauses.joinToString(" AND ") else null
        val args = if (selectionArgs.isNotEmpty()) selectionArgs.toTypedArray() else null

        val cursor = db.query(TABLE_KNOWLEDGE, null, selection, args, null, null, "id ASC")
        cursor.use {
            val idCol = it.getColumnIndexOrThrow("id")
            val titleCol = it.getColumnIndexOrThrow("title")
            val catCol = it.getColumnIndexOrThrow("category")
            val contentCol = it.getColumnIndexOrThrow("content")
            val sumCol = it.getColumnIndexOrThrow("summary")
            val timeCol = it.getColumnIndexOrThrow("timestamp")
            val embCol = it.getColumnIndexOrThrow("is_embedded")

            while (it.moveToNext()) {
                list.add(
                    KnowledgeEntry(
                        id = it.getLong(idCol),
                        title = it.getString(titleCol),
                        category = it.getString(catCol),
                        content = it.getString(contentCol),
                        summary = it.getString(sumCol),
                        timestamp = it.getString(timeCol),
                        isEmbedded = it.getInt(embCol) == 1
                    )
                )
            }
        }
        return list
    }

    fun markKnowledgeEmbedded(id: Long) {
        val db = writableDatabase
        val cv = ContentValues().apply { put("is_embedded", 1) }
        db.update(TABLE_KNOWLEDGE, cv, "id = ?", arrayOf(id.toString()))
    }

    fun deleteKnowledge(id: Long): Int {
        val db = writableDatabase
        return db.delete(TABLE_KNOWLEDGE, "id = ?", arrayOf(id.toString()))
    }
}

