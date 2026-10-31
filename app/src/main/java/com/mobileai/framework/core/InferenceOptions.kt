package com.mobileai.framework.core

/**
 * Configuration options and hardware tuning parameters for on-device inference.
 */
data class InferenceOptions(
    val maxTokens: Int = 1024,
    val contextSize: Int = 2048,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val seed: Int = 42,
    val systemPrompt: String = "",
    val customThreads: Int? = null,
    val useBigCoresOnly: Boolean = true,
    val enableThermalAdaptiveScaling: Boolean = true
)

/**
 * Telemetry and text payload emitted continuously during streaming generation.
 */
data class GenerationChunk(
    val text: String,
    val tokensGenerated: Int,
    val tokensPerSecond: Float,
    val elapsedMs: Long,
    val timeToFirstTokenMs: Long = 0L,
    val isFinished: Boolean = false,
    val telemetrySummary: String = ""
)

