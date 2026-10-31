package com.mobileai.framework.engine

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.mobileai.framework.core.GenerationChunk
import com.mobileai.framework.core.InferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * GPU inference backend powered by Google MediaPipe Tasks GenAI.
 * Uses Qualcomm Adreno OpenCL or Vulkan GPU shaders where supported.
 *
 * Streaming strategy: MediaPipe 0.10.14 blocks on generateResponse().
 * We run inference first, then emit word-by-word at the real tok/s speed
 * — giving users a visually identical streaming experience.
 */
class MediaPipeEngine(private val context: Context) : InferenceBackend {

    override val backendType: BackendType = BackendType.MEDIAPIPE_GPU

    override var isInitialized: Boolean = false
        private set

    override var currentModelPath: String? = null
        private set

    private var llmInference: LlmInference? = null
    private var modelFileName: String = ""

    override suspend fun initialize(modelPath: String, options: InferenceOptions): Result<String> = withContext(Dispatchers.IO) {
        val file = File(modelPath)
        if (!file.exists()) return@withContext Result.failure(IllegalArgumentException("MediaPipe model not found at $modelPath"))

        close()

        try {
            val inferenceOptions = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(file.absolutePath)
                .setMaxTokens(options.maxTokens)
                .setTemperature(options.temperature)
                .setTopK(options.topK)
                .setRandomSeed(options.seed)
                .build()

            llmInference = LlmInference.createFromOptions(context, inferenceOptions)
            currentModelPath = file.absolutePath
            modelFileName = file.name
            isInitialized = true
            Result.success("MediaPipe GPU (${file.name})")
        } catch (e: Exception) {
            isInitialized = false
            llmInference = null
            val msg = e.localizedMessage ?: e.message ?: "Unknown error"
            val detailedMsg = if (msg.contains("OpenCL", ignoreCase = true) || msg.contains("clSetPerfHintQCOM", ignoreCase = true)) {
                "MediaPipe GPU OpenCL failed ($msg).\n\nDevice GPU (Mali/Exynos) does not support Qualcomm OpenCL extensions. Switch to a CPU GGUF model (SmolLM2-360M or Gemma-2-2B GGUF)."
            } else {
                "MediaPipe load error: $msg"
            }
            Result.failure(Exception(detailedMsg, e))
        }
    }

    override suspend fun generate(prompt: String, options: InferenceOptions): GenerationChunk = withContext(Dispatchers.Default) {
        val engine = llmInference ?: throw IllegalStateException("MediaPipe engine not loaded")

        val startTime = System.currentTimeMillis()
        val text = engine.generateResponse(prompt)
        val elapsed = System.currentTimeMillis() - startTime

        val approxTokens = (text.length / 4.0).toInt().coerceAtLeast(1)
        val speed = if (elapsed > 0) (approxTokens / (elapsed / 1000f)) else 0f
        val summary = "MediaPipe GPU • ~$approxTokens tokens • ${String.format("%.1f", speed)} tok/s • ${elapsed}ms"

        GenerationChunk(
            text = text,
            tokensGenerated = approxTokens,
            tokensPerSecond = speed,
            elapsedMs = elapsed,
            timeToFirstTokenMs = (elapsed / approxTokens).coerceAtLeast(10),
            isFinished = true,
            telemetrySummary = summary
        )
    }

    /**
     * Word-by-word streaming simulation for MediaPipe.
     * Phase 1: run full inference (blocking). Phase 2: emit chunks at the real tok/s speed.
     */
    override fun generateStream(prompt: String, options: InferenceOptions): Flow<GenerationChunk> = flow {
        val engine = llmInference ?: throw IllegalStateException("MediaPipe engine not loaded")
        val startTime = System.currentTimeMillis()

        // Emit blank chunk immediately so UI shows spinner
        emit(GenerationChunk(text = "", tokensGenerated = 0, tokensPerSecond = 0f, elapsedMs = 0L, isFinished = false))

        // Run full inference
        val text = engine.generateResponse(prompt)
        val elapsed = System.currentTimeMillis() - startTime
        val approxTokens = (text.length / 4.0).toInt().coerceAtLeast(1)
        val speed = if (elapsed > 0) (approxTokens / (elapsed / 1000f)) else 8f

        // Stream word-by-word at real speed
        val tokens = tokenize(text)
        val msPerTok = (1000f / speed).toLong().coerceIn(20L, 150L)
        var accumulated = StringBuilder()
        val finalStats = "MediaPipe GPU • ~$approxTokens tokens • ${String.format("%.1f", speed)} tok/s • ${elapsed}ms"

        tokens.forEachIndexed { idx, chunk ->
            accumulated.append(chunk)
            val isLast = idx == tokens.size - 1
            emit(GenerationChunk(
                text = accumulated.toString(),
                tokensGenerated = idx + 1,
                tokensPerSecond = speed,
                elapsedMs = System.currentTimeMillis() - startTime,
                timeToFirstTokenMs = elapsed,
                isFinished = isLast,
                telemetrySummary = if (isLast) finalStats else ""
            ))
            if (!isLast) delay(msPerTok)
        }
    }.flowOn(Dispatchers.Default)

    private fun tokenize(text: String): List<String> {
        val chunks = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val end = (i + (2..5).random()).coerceAtMost(text.length)
            chunks.add(text.substring(i, end))
            i = end
        }
        return chunks
    }

    override suspend fun embed(text: String): Result<FloatArray> =
        Result.failure(UnsupportedOperationException("Embedding not supported on MediaPipe LLM backend"))

    override fun close() {
        llmInference?.let { try { it.close() } catch (_: Exception) {} }
        llmInference = null
        isInitialized = false
        currentModelPath = null
    }
}
