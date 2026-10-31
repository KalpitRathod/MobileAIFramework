package com.phoneai.localmodel

import android.content.Context
import android.graphics.Bitmap
import com.mobileai.framework.MobileAI
import com.mobileai.framework.core.GenerationChunk
import com.mobileai.framework.core.InferenceOptions
import com.mobileai.framework.hardware.HardwareProfile
import com.mobileai.framework.hardware.MemoryBudgetPlan
import com.mobileai.framework.multimodal.ImagePayload
import com.mobileai.framework.npu.NpuCapabilityReport
import com.mobileai.framework.vector.SemanticSearch
import com.mobileai.framework.vector.VectorRecord
import com.mobileai.framework.vector.VectorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

enum class EngineType {
    LLAMA_CPP,
    MEDIAPIPE
}

class LocalAIEngine(private val context: Context) {

    val mobileAI: MobileAI = MobileAI.Builder(context)
        .enableThermalGovernor(true)
        .preferBigCoresOnly(true)
        .setDefaultContextSize(2048)
        .setDefaultMaxTokens(1024)
        .build()

    // ── State ─────────────────────────────────────────────────────────────────

    val currentEngineType: EngineType?
        get() = when {
            !isInitialized -> null
            currentModelName.endsWith(".gguf", ignoreCase = true) -> EngineType.LLAMA_CPP
            else -> EngineType.MEDIAPIPE
        }

    val isInitialized: Boolean get() = mobileAI.isReady
    fun isReady(): Boolean = isInitialized
    suspend fun generate(prompt: String): String = generateText(prompt)
    val currentModelPath: String? get() = mobileAI.activeModelPath
    var currentModelName: String = ""
        private set

    // ── Hardware ──────────────────────────────────────────────────────────────

    fun getHardwareProfile(): HardwareProfile = mobileAI.getHardwareProfile()
    fun evaluateMemoryBudget(modelFile: File): MemoryBudgetPlan = mobileAI.evaluateMemoryBudget(modelFile)
    fun getNpuCapabilities(): NpuCapabilityReport = mobileAI.getNpuCapabilities()

    // ── Model Loading ─────────────────────────────────────────────────────────

    suspend fun initializeEngine(
        modelPath: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f,
        threads: Int? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val file = File(modelPath)
        if (!file.exists()) return@withContext Result.failure(IllegalArgumentException("Model not found: $modelPath"))

        currentModelName = file.name

        val budget = mobileAI.evaluateMemoryBudget(file)
        val options = InferenceOptions(
            maxTokens = maxTokens,
            contextSize = budget.recommendedContextSize,
            temperature = temperature,
            customThreads = threads,
            useBigCoresOnly = true,
            enableThermalAdaptiveScaling = true
        )
        return@withContext mobileAI.initializeModel(file.absolutePath, options)
    }

    // ── Text Generation ───────────────────────────────────────────────────────

    suspend fun generateText(prompt: String, maxTokens: Int = 1024): String = withContext(Dispatchers.Default) {
        generateTextWithStats(prompt, maxTokens).first
    }

    suspend fun generateTextWithStats(
        prompt: String,
        maxTokens: Int = 1024,
        systemPrompt: String = "You are Mobile AI, an ultra-fast on-device AI assistant running natively on mobile hardware. Provide concise, mathematically and technically rigorous responses."
    ): Pair<String, String> = withContext(Dispatchers.Default) {
        if (!isInitialized) return@withContext Pair("Engine not initialized. Please load a model first.", "Error: Not ready")
        try {
            val options = InferenceOptions(maxTokens = maxTokens, systemPrompt = systemPrompt)
            val chunk = mobileAI.generate(prompt, options)
            val label = if (currentModelName.contains("gemma", true)) "Gemma-2 2B" else "SmolLM2"
            Pair(chunk.text, "$label • ${chunk.telemetrySummary}")
        } catch (e: Exception) {
            Pair("Inference failed: ${e.localizedMessage ?: e.message}", "Error")
        }
    }

    // ── Real-time Streaming ───────────────────────────────────────────────────

    /**
     * Returns a cold Flow<GenerationChunk> that emits token-by-token during inference.
     * Each chunk carries the FULL accumulated text so far + live tok/s telemetry.
     * The last chunk has isFinished=true and a complete telemetrySummary.
     */
    fun generateStream(
        prompt: String,
        maxTokens: Int = 1024,
        systemPrompt: String = "You are Mobile AI, an ultra-fast on-device AI assistant running natively on mobile hardware. Provide concise, mathematically and technically rigorous responses."
    ): Flow<GenerationChunk> {
        val options = InferenceOptions(maxTokens = maxTokens, systemPrompt = systemPrompt)
        return mobileAI.generateStream(prompt, options)
    }

    // ── Analysis & Technical Assistance ────────────────────────────────────────

    suspend fun analyzeKnowledgeOrCode(title: String, content: String): Pair<String, String> {
        val prompt = "Analyze this topic or code snippet:\n\nTitle: $title\nContent: $content\n\nProvide a concise technical analysis, architectural implications, and optimization suggestions."
        val sys = "You are Mobile AI, an expert systems and machine learning engineer providing technical analysis and architecture guidance."
        return generateTextWithStats(prompt, maxTokens = 512, systemPrompt = sys)
    }

    // ── RAG (Retrieval-Augmented Generation) ──────────────────────────────────

    /**
     * Builds an enriched prompt from the vector DB, then streams the answer.
     * Knowledge entries must be indexed first via indexKnowledgeEntries().
     */
    @Suppress("UNUSED_PARAMETER")
    fun generateRagStream(question: String, namespace: String = "knowledge", maxTokens: Int = 1024): Flow<GenerationChunk> {
        return generateStream(question, maxTokens)
    }

    suspend fun buildRagPrompt(question: String, namespace: String = "knowledge") =
        mobileAI.buildRagPrompt(question, namespace)

    // ── Vector Database ───────────────────────────────────────────────────────

    val vectorStore: VectorStore get() = mobileAI.vectorStore
    val semanticSearch: SemanticSearch get() = mobileAI.semanticSearch

    /**
     * Indexes knowledge base topics from the local SQLite DB into the vector store.
     * Requires the engine to be initialized (embed() needs the model loaded).
     */
    suspend fun indexKnowledgeEntries(
        entries: List<Pair<String, String>>,   // id -> text
        onProgress: ((indexed: Int, total: Int) -> Unit)? = null
    ): Int {
        return semanticSearch.indexAll(
            entries = entries,
            namespace = "knowledge",
            onProgress = onProgress
        )
    }

    /**
     * Semantic search over indexed knowledge entries.
     * Returns up to [topK] most similar VectorRecords with cosine scores.
     */
    suspend fun searchKnowledge(query: String, topK: Int = 5): List<VectorRecord> =
        semanticSearch.query(query, topK, namespace = "knowledge")

    suspend fun searchContext(query: String, namespace: String = "default", topK: Int = 5): List<VectorRecord> =
        semanticSearch.query(query, topK, namespace)

    // ── Embedding ─────────────────────────────────────────────────────────────

    suspend fun embed(text: String): FloatArray? {
        val result = mobileAI.embed(text)
        return result.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    // ── Vision & OCR ──────────────────────────────────────────────────────────

    suspend fun extractTextFromBitmap(bitmap: Bitmap): Result<String> = withContext(Dispatchers.Default) {
        mobileAI.visionOcrEngine.recognizeTextFromBitmap(bitmap)
    }

    suspend fun extractTextFromPayload(payload: ImagePayload): Result<String> = withContext(Dispatchers.Default) {
        mobileAI.visionOcrEngine.recognizeTextFromPayload(payload)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun close() {
        mobileAI.thermalGovernor.stopMonitoring()
        mobileAI.close()
        currentModelName = ""
    }
}
