package com.mobileai.framework

import android.content.Context
import com.mobileai.framework.core.GenerationChunk
import com.mobileai.framework.core.InferenceOptions
import com.mobileai.framework.engine.BackendType
import com.mobileai.framework.engine.InferenceBackend
import com.mobileai.framework.engine.LlamaNativeEngine
import com.mobileai.framework.engine.MediaPipeEngine
import com.mobileai.framework.hardware.HardwareInspector
import com.mobileai.framework.hardware.HardwareProfile
import com.mobileai.framework.hardware.MemoryBudgetManager
import com.mobileai.framework.hardware.MemoryBudgetPlan
import com.mobileai.framework.hardware.ThermalGovernor
import com.mobileai.framework.npu.NpuBackendSelector
import com.mobileai.framework.npu.NpuCapabilityProbe
import com.mobileai.framework.npu.NpuCapabilityReport
import com.mobileai.framework.vector.SemanticSearch
import com.mobileai.framework.vector.VectorRecord
import com.mobileai.framework.vector.VectorStore
import com.mobileai.framework.agent.DeviceAgent
import com.mobileai.framework.cloud.CloudAiClient
import com.mobileai.framework.cloud.CloudApiKeyStore
import com.mobileai.framework.multimodal.AudioInputManager
import com.mobileai.framework.multimodal.CameraVisionManager
import com.mobileai.framework.multimodal.OfflineVisionOcrEngine
import com.mobileai.framework.multimodal.ScreenCaptureManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File

/**
 * MobileAI — Central facade for the hardware-integrated offline AI framework.
 *
 * Features:
 *  • CPU cluster-aware Big-core thread pinning (llama.cpp)
 *  • Real-time streaming token output (Flow<GenerationChunk>)
 *  • Dynamic thermal governor (Android PowerManager)
 *  • RAM + KV-cache budget guard (prevents Android LMK kills)
 *  • On-device Vector Database (SQLite cosine similarity)
 *  • NPU capability detection for ALL mobile chipsets (NNAPI)
 *  • Builder pattern for clean configuration
 */
class MobileAI private constructor(
    private val context: Context,
    val thermalGovernor: ThermalGovernor,
    private val defaultOptions: InferenceOptions,
    val vectorStore: VectorStore,
    private val embeddingHolder: EmbeddingBackendHolder
) {
    private var activeBackend: InferenceBackend? = null

    /** Provides SemanticSearch bound to the currently-loaded model's embed() */
    val semanticSearch: SemanticSearch = SemanticSearch(vectorStore, embeddingHolder)

    /** Multimodal Sensory, Cloud AI Gateway, and Autonomous Device Agent Subsystems */
    val audioManager: AudioInputManager by lazy { AudioInputManager(context) }
    val cameraManager: CameraVisionManager by lazy { CameraVisionManager(context) }
    val screenManager: ScreenCaptureManager by lazy { ScreenCaptureManager(context) }
    val visionOcrEngine: OfflineVisionOcrEngine by lazy { OfflineVisionOcrEngine(context) }
    val cloudClient: CloudAiClient by lazy { CloudAiClient() }
    val cloudApiKeyStore: CloudApiKeyStore by lazy { CloudApiKeyStore(context) }
    val deviceAgent: DeviceAgent by lazy { DeviceAgent(context, cloudClient) }

    val isReady: Boolean get() = activeBackend?.isInitialized == true
    val activeModelPath: String? get() = activeBackend?.currentModelPath

    // ── Hardware ──────────────────────────────────────────────────────────────

    fun getHardwareProfile(): HardwareProfile = HardwareInspector.inspect(context)

    fun evaluateMemoryBudget(
        modelFile: File,
        requestedContextSize: Int = defaultOptions.contextSize
    ): MemoryBudgetPlan = MemoryBudgetManager.evaluateBudget(context, modelFile, requestedContextSize)

    fun getNpuCapabilities(): NpuCapabilityReport = NpuCapabilityProbe.probe(context)

    fun getNpuBackendStrategy() = NpuBackendSelector.selectStrategy(context)

    // ── Model Loading ─────────────────────────────────────────────────────────

    /**
     * Initialises the optimal backend based on file extension:
     *  .gguf  →  LlamaNativeEngine (ARM NEON Big-core pinned, thermally governed)
     *  .bin/.task  →  MediaPipeEngine (GPU OpenCL, Mali-safe fallback)
     */
    suspend fun initializeModel(
        modelPath: String,
        options: InferenceOptions? = null
    ): Result<String> {
        val file = File(modelPath)
        if (!file.exists()) return Result.failure(IllegalArgumentException("Model not found: $modelPath"))

        close()

        val opts = options ?: defaultOptions
        val isGguf = file.name.lowercase().endsWith(".gguf")
        val backend: InferenceBackend =
            if (isGguf) LlamaNativeEngine(context, thermalGovernor)
            else MediaPipeEngine(context)

        val result = backend.initialize(file.absolutePath, opts)
        if (result.isSuccess) {
            activeBackend = backend
            embeddingHolder.backend = backend   // wire embed() into SemanticSearch
        }
        return result
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    suspend fun generate(prompt: String, options: InferenceOptions? = null): GenerationChunk =
        activeBackend?.generate(prompt, options ?: defaultOptions)
            ?: throw IllegalStateException("MobileAI not initialised. Call initializeModel() first.")

    /**
     * Real-time streaming generation — emits GenerationChunk per token/word.
     * Each chunk has the full accumulated text + live tok/s.
     * Final chunk: isFinished=true + complete telemetrySummary.
     */
    fun generateStream(prompt: String, options: InferenceOptions? = null): Flow<GenerationChunk> =
        activeBackend?.generateStream(prompt, options ?: defaultOptions)
            ?: throw IllegalStateException("MobileAI not initialised. Call initializeModel() first.")

    suspend fun embed(text: String): Result<FloatArray> =
        activeBackend?.embed(text) ?: Result.failure(IllegalStateException("Not initialised"))

    // ── Semantic / RAG ────────────────────────────────────────────────────────

    suspend fun buildRagPrompt(
        question: String,
        namespace: String = "default",
        topK: Int = 3
    ): String = semanticSearch.buildRagPrompt(question, namespace, topK)

    // ── Status ────────────────────────────────────────────────────────────────

    fun getThermalStatusDescription(): String = thermalGovernor.getThermalStatusDescription()

    fun close() {
        activeBackend?.close()
        activeBackend = null
        embeddingHolder.backend = NoOpBackend
        runCatching { visionOcrEngine.close() }
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    class Builder(private val context: Context) {
        private var enableThermalMonitoring = true
        private var defaultContextSize = 2048
        private var defaultMaxTokens = 1024
        private var defaultTemperature = 0.7f
        private var useBigCoresOnly = true

        fun enableThermalGovernor(enable: Boolean) = apply { enableThermalMonitoring = enable }
        fun setDefaultContextSize(ctx: Int) = apply { defaultContextSize = ctx }
        fun setDefaultMaxTokens(tokens: Int) = apply { defaultMaxTokens = tokens }
        fun setTemperature(temp: Float) = apply { defaultTemperature = temp }
        fun preferBigCoresOnly(bigCoresOnly: Boolean) = apply { useBigCoresOnly = bigCoresOnly }

        fun build(): MobileAI {
            val appCtx = context.applicationContext
            val thermalGovernor = ThermalGovernor(appCtx)
            if (enableThermalMonitoring) thermalGovernor.startMonitoring()

            val opts = InferenceOptions(
                maxTokens = defaultMaxTokens,
                contextSize = defaultContextSize,
                temperature = defaultTemperature,
                useBigCoresOnly = useBigCoresOnly,
                enableThermalAdaptiveScaling = enableThermalMonitoring
            )

            val vectorStore = VectorStore(appCtx)
            val holder = EmbeddingBackendHolder(NoOpBackend)

            return MobileAI(appCtx, thermalGovernor, opts, vectorStore, holder)
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Mutable wrapper so SemanticSearch always delegates embed() to whichever
     * model is currently loaded, without requiring subclassing.
     */
    class EmbeddingBackendHolder(var backend: InferenceBackend) : InferenceBackend {
        override val backendType get() = backend.backendType
        override val isInitialized get() = backend.isInitialized
        override val currentModelPath get() = backend.currentModelPath
        override suspend fun initialize(modelPath: String, options: InferenceOptions) = backend.initialize(modelPath, options)
        override suspend fun generate(prompt: String, options: InferenceOptions) = backend.generate(prompt, options)
        override fun generateStream(prompt: String, options: InferenceOptions) = backend.generateStream(prompt, options)
        override suspend fun embed(text: String) = backend.embed(text)
        override fun close() = backend.close()
    }

    companion object {
        /** Stub used before any model is loaded. */
        private val NoOpBackend: InferenceBackend = object : InferenceBackend {
            override val backendType = BackendType.LLAMA_CPP_CPU
            override val isInitialized = false
            override val currentModelPath: String? = null
            override suspend fun initialize(modelPath: String, options: InferenceOptions) = Result.failure<String>(UnsupportedOperationException("No model loaded"))
            override suspend fun generate(prompt: String, options: InferenceOptions) = GenerationChunk("", 0, 0f, 0L)
            override fun generateStream(prompt: String, options: InferenceOptions): Flow<GenerationChunk> = flowOf(GenerationChunk("", 0, 0f, 0L))
            override suspend fun embed(text: String) = Result.success(FloatArray(0))
            override fun close() {}
        }
    }
}
