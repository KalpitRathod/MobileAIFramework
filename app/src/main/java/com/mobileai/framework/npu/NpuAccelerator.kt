package com.mobileai.framework.npu

/**
 * Represents a discovered on-device neural processing hardware accelerator.
 */
data class NpuAccelerator(
    val name: String,
    val vendor: String,
    val type: AcceleratorType,
    val nnApiDeviceName: String,
    val estimatedSpeedupFactor: Float,  // vs pure CPU baseline (1.0x)
    val supportsFp16: Boolean,
    val supportsInt8: Boolean,
    val isPreferred: Boolean
)

enum class AcceleratorType {
    NPU,        // Dedicated neural processing unit (best)
    GPU,        // GPU shader compute (good)
    DSP,        // Digital signal processor (good for int8)
    ACCELERATOR, // Generic hardware accelerator
    CPU,        // Software fallback (baseline)
    UNKNOWN
}

/**
 * Complete NPU capability summary for a device.
 */
data class NpuCapabilityReport(
    val hasNnApi: Boolean,
    val nnApiVersion: Int,
    val accelerators: List<NpuAccelerator>,
    val preferredAccelerator: NpuAccelerator?,
    val recommendedEmbeddingBatchSize: Int,
    val supportsInt8Quantization: Boolean,
    val supportsFp16Inference: Boolean,
    val estimatedEmbeddingSpeedupVsCpu: Float,
    val summary: String
)
