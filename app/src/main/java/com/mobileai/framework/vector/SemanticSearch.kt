package com.mobileai.framework.vector

import android.util.Log
import com.mobileai.framework.engine.InferenceBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-level semantic search engine combining:
 *   1. llama.cpp embed() to convert text → FloatArray embedding
 *   2. VectorStore cosine similarity retrieval
 *
 * Supports Retrieval-Augmented Generation (RAG):
 *   index(text) → embed → store
 *   query(text) → embed → cosine search → ranked VectorRecords → RAG prompt
 */
open class SemanticSearch(
    val vectorStore: VectorStore,
    private val embeddingBackend: InferenceBackend
) {
    private val tag = "SemanticSearch"

    // ── Encoding ──────────────────────────────────────────────────────────────

    /**
     * Encodes text into an embedding vector using the active inference backend.
     * Returns null if the backend doesn't support embeddings or fails.
     * Override in subclasses to swap the backend.
     */
    open suspend fun encode(text: String): FloatArray? {
        val result = embeddingBackend.embed(text.take(512))
        return result.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    // ── Indexing ──────────────────────────────────────────────────────────────

    /**
     * Indexes a single text entry into the vector store.
     */
    suspend fun index(
        id: String,
        text: String,
        namespace: String = "default",
        metadata: String = ""
    ): Boolean {
        val emb = encode(text) ?: return false
        vectorStore.upsert(VectorRecord(id = id, text = text, embedding = emb, namespace = namespace, metadata = metadata))
        return true
    }

    /**
     * Batch-indexes a list of (id, text) pairs with optional progress callback.
     * Returns the number of successfully indexed entries.
     */
    suspend fun indexAll(
        entries: List<Pair<String, String>>,
        namespace: String = "default",
        metadata: (String) -> String = { "" },
        onProgress: ((indexed: Int, total: Int) -> Unit)? = null
    ): Int = withContext(Dispatchers.Default) {
        var indexed = 0
        val records = mutableListOf<VectorRecord>()

        entries.forEachIndexed { i, (id, text) ->
            val emb = encode(text)
            if (emb != null) {
                records += VectorRecord(id = id, text = text, embedding = emb, namespace = namespace, metadata = metadata(id))
                indexed++
            }
            onProgress?.invoke(i + 1, entries.size)
        }

        if (records.isNotEmpty()) vectorStore.upsertAll(records)
        Log.i(tag, "Indexed $indexed/${entries.size} entries into '$namespace'")
        indexed
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Semantic similarity search: encodes [queryText] → cosine similarity → ranked results.
     */
    suspend fun query(
        queryText: String,
        topK: Int = 5,
        namespace: String = "default",
        threshold: Float = 0.10f
    ): List<VectorRecord> {
        val emb = encode(queryText) ?: run {
            Log.w(tag, "Embedding failed for query: '$queryText'")
            return emptyList()
        }
        return vectorStore.search(emb, topK, namespace, threshold)
    }

    // ── RAG ───────────────────────────────────────────────────────────────────

    /**
     * Retrieval-Augmented Generation prompt builder.
     * Retrieves the most relevant indexed passages and prepends them to the
     * user's question so the model can cite real knowledge-base content.
     */
    suspend fun buildRagPrompt(
        question: String,
        namespace: String = "default",
        topK: Int = 3,
        maxContextChars: Int = 1200
    ): String {
        val results = query(question, topK, namespace, threshold = 0.15f)
        if (results.isEmpty()) return question

        val ctx = buildString {
            appendLine("Relevant knowledge base context:")
            var totalChars = 0
            for (r in results) {
                val snippet = r.text.take(400)
                if (totalChars + snippet.length > maxContextChars) break
                appendLine("• $snippet  [relevance: ${String.format("%.2f", r.score)}]")
                totalChars += snippet.length
            }
            appendLine()
            appendLine("Question: $question")
        }
        return ctx
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    suspend fun deleteNamespace(namespace: String) = vectorStore.deleteNamespace(namespace)
    suspend fun countIndexed(namespace: String = "default") = vectorStore.count(namespace)
    suspend fun listNamespaces() = vectorStore.listNamespaces()
}
