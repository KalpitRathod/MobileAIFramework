package com.mobileai.framework.hardware

/**
 * Represents the physical attributes of a CPU core on a mobile SoC.
 */
data class CpuCore(
    val coreId: Int,
    val maxFreqKhz: Long,
    val minFreqKhz: Long,
    val isBigCore: Boolean
)

/**
 * CPU topology breakdown separating Big (Performance/Prime) cores from Little (Efficiency) cores.
 */
data class CpuTopology(
    val totalCores: Int,
    val bigCores: List<CpuCore>,
    val littleCores: List<CpuCore>,
    val optimalInferenceThreads: Int,
    val rawClusterDescription: String
)

/**
 * Hardware profile aggregating CPU, GPU, RAM, SIMD vector instructions, and thermal state.
 */
data class HardwareProfile(
    val socModel: String,
    val cpuTopology: CpuTopology,
    val supportedSimd: List<String>,
    val totalRamMb: Long,
    val availableRamMb: Long,
    val vulkanSupported: Boolean,
    val vulkanVersion: String,
    val recommendedBackend: String,
    val recommendedContextSize: Int,
    val hardwareNotes: String
)

