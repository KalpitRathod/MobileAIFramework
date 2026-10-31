package com.mobileai.framework.npu

import android.content.Context
import com.mobileai.framework.hardware.HardwareInspector

/**
 * Selects the optimal backend strategy based on device NPU + CPU capabilities.
 * For ALL budget phones: generation always uses llama.cpp CPU (optimal).
 * NPU accelerates: embeddings, future TFLite models, batch processing.
 */
object NpuBackendSelector {

    data class BackendStrategy(
        val generationBackend: String,
        val embeddingBackend: String,
        val description: String,
        val expectedTokPerSec: String,
        val npuReport: NpuCapabilityReport
    )

    fun selectStrategy(context: Context): BackendStrategy {
        val hwProfile = HardwareInspector.inspect(context)
        val npuReport = NpuCapabilityProbe.probe(context)
        val cpuThreads = hwProfile.cpuTopology.optimalInferenceThreads
        val bigCoreFreqMhz = (hwProfile.cpuTopology.bigCores.firstOrNull()?.maxFreqKhz ?: 2000000L) / 1000

        // Generation: llama.cpp on Big CPU cores is ALWAYS best for GGUF models
        // (NPU won't accelerate GGUF — needs TFLite/ONNX; CPU+NEON is optimal for Q4_K_M)
        val genBackend = "llama.cpp CPU (${cpuThreads}T × ${bigCoreFreqMhz}MHz Big-Core NEON)"

        // Embeddings: NPU if available (much faster for small embedding forward passes)
        val embBackend = if (npuReport.hasNnApi && npuReport.preferredAccelerator != null) {
            val npu = npuReport.preferredAccelerator
            "${npu.name} via NNAPI (${String.format("%.1f", npu.estimatedSpeedupFactor)}x speedup)"
        } else {
            "llama.cpp CPU embed()"
        }

        val tokSec = when {
            bigCoreFreqMhz > 3000 -> "60 – 80 tok/s (Flagship)"
            bigCoreFreqMhz > 2500 -> "35 – 55 tok/s (Upper-mid)"
            bigCoreFreqMhz > 2000 -> "20 – 38 tok/s (Mid-range)"
            else -> "10 – 25 tok/s (Budget)"
        }

        val desc = buildString {
            appendLine("Generation: $genBackend")
            appendLine("Embeddings: $embBackend")
            if (npuReport.accelerators.isNotEmpty()) {
                appendLine("NPU: ${npuReport.summary}")
            }
        }

        return BackendStrategy(
            generationBackend = genBackend,
            embeddingBackend = embBackend,
            description = desc.trim(),
            expectedTokPerSec = tokSec,
            npuReport = npuReport
        )
    }
}
