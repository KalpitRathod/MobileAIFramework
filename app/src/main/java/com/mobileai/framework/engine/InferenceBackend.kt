package com.mobileai.framework.engine

import com.mobileai.framework.core.GenerationChunk
import com.mobileai.framework.core.InferenceOptions
import kotlinx.coroutines.flow.Flow

enum class BackendType {
    LLAMA_CPP_CPU,
    VULKAN_GPU,
    MEDIAPIPE_GPU,
    QNN_NPU
}

/**
 * Common hardware-accelerated interface for mobile inference backends.
 */
interface InferenceBackend {
    val backendType: BackendType
    val isInitialized: Boolean
    val currentModelPath: String?

    suspend fun initialize(modelPath: String, options: InferenceOptions): Result<String>
    suspend fun generate(prompt: String, options: InferenceOptions): GenerationChunk
    fun generateStream(prompt: String, options: InferenceOptions): Flow<GenerationChunk>
    suspend fun embed(text: String): Result<FloatArray>
    fun close()
}

