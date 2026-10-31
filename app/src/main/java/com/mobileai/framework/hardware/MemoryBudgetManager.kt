package com.mobileai.framework.hardware

import android.app.ActivityManager
import android.content.Context
import java.io.File
import kotlin.math.min

data class MemoryBudgetPlan(
    val modelSizeBytes: Long,
    val estimatedKvCacheBytes: Long,
    val totalRequiredMemoryBytes: Long,
    val availableSystemRamBytes: Long,
    val isSafeToLoad: Boolean,
    val recommendedContextSize: Int,
    val advisoryNote: String
)

/**
 * Predicts and manages KV-cache and working memory footprints to protect against
 * Android OS Low Memory Killer (LMK) process terminations.
 */
object MemoryBudgetManager {

    /**
     * Calculates the estimated KV cache size in bytes.
     * Formula: 2 (Key + Value) * numLayers * numHeads * headDim * contextSize * bytesPerElement (2 for FP16)
     */
    fun estimateKvCacheBytes(
        contextSize: Int,
        numLayers: Int = 26,
        numHeads: Int = 8,
        headDim: Int = 128,
        bytesPerElement: Int = 2
    ): Long {
        return 2L * numLayers * numHeads * headDim * contextSize * bytesPerElement
    }

    /**
     * Validates whether a model file can be safely loaded into device RAM without triggering LMK.
     */
    fun evaluateBudget(
        context: Context,
        modelFile: File,
        requestedContextSize: Int = 2048
    ): MemoryBudgetPlan {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)

        val availRam = memInfo.availMem
        val modelSize = modelFile.length()

        // Guess model architecture parameters based on file size
        val (layers, heads, headDim) = when {
            modelSize < 150_000_000L -> Triple(30, 9, 64)   // SmolLM2-135M
            modelSize < 400_000_000L -> Triple(32, 15, 64)  // SmolLM2-360M / Qwen-0.5B
            modelSize < 2_000_000_000L -> Triple(26, 8, 256) // Gemma-2-2B / Llama-3.2-1B
            else -> Triple(32, 32, 128)                     // 7B+ models
        }

        var effectiveContext = requestedContextSize
        var kvBytes = estimateKvCacheBytes(effectiveContext, layers, heads, headDim)
        var totalNeeded = modelSize + kvBytes + (150L * 1024 * 1024) // 150MB scratch buffer

        // If total needed exceeds 80% of available RAM, reduce context size
        val safeRamThreshold = (availRam * 0.80).toLong()
        if (totalNeeded > safeRamThreshold && effectiveContext > 512) {
            effectiveContext = min(1024, requestedContextSize / 2)
            kvBytes = estimateKvCacheBytes(effectiveContext, layers, heads, headDim)
            totalNeeded = modelSize + kvBytes + (150L * 1024 * 1024)
        }

        val isSafe = totalNeeded <= availRam
        val modelMb = modelSize / (1024 * 1024)
        val kvMb = kvBytes / (1024 * 1024)
        val availMb = availRam / (1024 * 1024)

        val note = if (isSafe) {
            "Memory Budget Safe: ${modelMb}MB weights + ${kvMb}MB KV-cache (ctx: $effectiveContext). Avail RAM: ${availMb}MB."
        } else {
            "RAM Warning: Required ${(totalNeeded / (1024 * 1024))}MB exceeds free RAM (${availMb}MB). May risk Android OS LMK termination."
        }

        return MemoryBudgetPlan(
            modelSizeBytes = modelSize,
            estimatedKvCacheBytes = kvBytes,
            totalRequiredMemoryBytes = totalNeeded,
            availableSystemRamBytes = availRam,
            isSafeToLoad = isSafe,
            recommendedContextSize = effectiveContext,
            advisoryNote = note
        )
    }
}

