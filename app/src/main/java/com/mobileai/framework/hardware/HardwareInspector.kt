package com.mobileai.framework.hardware

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max

/**
 * Probes the underlying mobile SoC, asymmetric CPU clusters, SIMD instruction sets,
 * Vulkan compute capability, and RAM limits to maximize AI inference efficiency.
 */
object HardwareInspector {

    /**
     * Inspects the entire mobile device and builds a comprehensive HardwareProfile.
     */
    fun inspect(context: Context): HardwareProfile {
        val cpuTopology = detectCpuTopology()
        val simdFeatures = detectSimdFeatures()
        val (totalRam, availRam) = detectRam(context)
        val (vulkanSupported, vulkanVer) = detectVulkan(context)
        val socName = detectSocModel()

        // Calculate recommended context size based on available RAM
        // < 1.5GB free -> 1024 tokens
        // 1.5GB - 3.0GB free -> 2048 tokens
        // > 3.0GB free -> 4096 tokens
        val recommendedContextSize = when {
            availRam < 1500 -> 1024
            availRam < 3000 -> 2048
            else -> 4096
        }

        val recommendedBackend = if (vulkanSupported && !isMaliOrUnstableGpu()) {
            "Vulkan / MediaPipe GPU (Adreno Fast-Path)"
        } else {
            "llama.cpp CPU (Optimized NEON SIMD)"
        }

        val notes = buildString {
            append("Detected ${cpuTopology.totalCores} cores: ")
            append("${cpuTopology.bigCores.size} Big cores, ${cpuTopology.littleCores.size} Little cores. ")
            append("Inference pinned to ${cpuTopology.optimalInferenceThreads} threads to prevent Little-core synchronization stalls. ")
            if (simdFeatures.contains("dotprod") || simdFeatures.contains("i8mm")) {
                append("Hardware INT8 matrix multiply acceleration supported.")
            }
        }

        return HardwareProfile(
            socModel = socName,
            cpuTopology = cpuTopology,
            supportedSimd = simdFeatures,
            totalRamMb = totalRam,
            availableRamMb = availRam,
            vulkanSupported = vulkanSupported,
            vulkanVersion = vulkanVer,
            recommendedBackend = recommendedBackend,
            recommendedContextSize = recommendedContextSize,
            hardwareNotes = notes
        )
    }

    /**
     * Parses sysfs CPU frequency nodes to isolate Big / Performance cores from Little cores.
     */
    fun detectCpuTopology(): CpuTopology {
        val totalCores = Runtime.getRuntime().availableProcessors()
        val cores = mutableListOf<CpuCore>()

        var maxObservedFreq = 0L
        for (i in 0 until totalCores) {
            val maxFreq = readCpuFreqKhz("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            val minFreq = readCpuFreqKhz("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_min_freq")
            if (maxFreq > maxObservedFreq) {
                maxObservedFreq = maxFreq
            }
            cores.add(CpuCore(coreId = i, maxFreqKhz = maxFreq, minFreqKhz = minFreq, isBigCore = false))
        }

        // If sysfs didn't return frequencies, fallback to heuristic
        val bigCores: List<CpuCore>
        val littleCores: List<CpuCore>

        if (maxObservedFreq > 0) {
            // Cores within 15% of the highest observed frequency are classified as Big/Prime
            val threshold = (maxObservedFreq * 0.85).toLong()
            val categorized = cores.map { core ->
                core.copy(isBigCore = core.maxFreqKhz >= threshold)
            }
            bigCores = categorized.filter { it.isBigCore }
            littleCores = categorized.filter { !it.isBigCore }
        } else {
            // Standard mobile heuristic: Octa-core SoCs are usually 2 Big + 6 Little (e.g. Helio G85, SD680)
            // or 1 Prime + 3 Big + 4 Little. Default to the top 2-4 cores.
            val numBig = if (totalCores >= 8) 2 else max(1, totalCores / 2)
            val updated = cores.mapIndexed { idx, core ->
                core.copy(isBigCore = idx >= (totalCores - numBig))
            }
            bigCores = updated.filter { it.isBigCore }
            littleCores = updated.filter { !it.isBigCore }
        }

        // Crucial optimization: Optimal inference threads should match the Big core cluster count!
        // Spawning threads on Little cores slows down the entire GEMM loop due to barrier sync.
        val optimalThreads = when {
            bigCores.isNotEmpty() -> bigCores.size.coerceIn(2, 6)
            totalCores >= 8 -> 4
            else -> max(1, totalCores - 1)
        }

        val desc = if (maxObservedFreq > 0) {
            val bigFreqMhz = (bigCores.firstOrNull()?.maxFreqKhz ?: 0) / 1000
            val littleFreqMhz = (littleCores.firstOrNull()?.maxFreqKhz ?: 0) / 1000
            "${bigCores.size} Big (${bigFreqMhz}MHz) + ${littleCores.size} Little (${littleFreqMhz}MHz)"
        } else {
            "${bigCores.size} Big + ${littleCores.size} Little"
        }

        return CpuTopology(
            totalCores = totalCores,
            bigCores = bigCores,
            littleCores = littleCores,
            optimalInferenceThreads = optimalThreads,
            rawClusterDescription = desc
        )
    }

    private fun readCpuFreqKhz(path: String): Long {
        return try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                file.readText().trim().toLongOrNull() ?: 0L
            } else 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Inspects /proc/cpuinfo for vectorization features:
     * asimd (NEON), fp16, dotprod (SDOT/UDOT), i8mm (Int8 matrix multiply).
     */
    fun detectSimdFeatures(): List<String> {
        val detected = mutableListOf<String>()
        try {
            val file = File("/proc/cpuinfo")
            if (file.exists() && file.canRead()) {
                val lines = file.readLines()
                for (line in lines) {
                    if (line.startsWith("Features", ignoreCase = true) || line.startsWith("flags", ignoreCase = true)) {
                        val features = line.substringAfter(":").trim().split(" ")
                        val keywords = listOf("asimd", "neon", "fp16", "fphp", "dotprod", "i8mm", "sve", "sve2", "crc32")
                        for (kw in keywords) {
                            if (features.any { it.equals(kw, ignoreCase = true) } && !detected.contains(kw)) {
                                detected.add(kw)
                            }
                        }
                        if (detected.isNotEmpty()) break
                    }
                }
            }
        } catch (_: Exception) {}

        if (detected.isEmpty()) {
            // Default 64-bit ARMv8 guarantee
            if (Build.SUPPORTED_ABIS.any { it.contains("arm64") }) {
                detected.add("asimd (ARMv8 NEON)")
            }
        }
        return detected
    }

    /**
     * Reads Total & Available RAM in MB via ActivityManager.
     */
    fun detectRam(context: Context): Pair<Long, Long> {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val totalMb = memInfo.totalMem / (1024 * 1024)
            val availMb = memInfo.availMem / (1024 * 1024)
            Pair(totalMb, availMb)
        } catch (_: Exception) {
            Pair(4096L, 1500L)
        }
    }

    /**
     * Checks Vulkan GPU support and version.
     */
    fun detectVulkan(context: Context): Pair<Boolean, String> {
        return try {
            val pm = context.packageManager
            if (pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)) {
                val vulkanFeature = pm.systemAvailableFeatures.firstOrNull {
                    it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION
                }
                val versionInt = vulkanFeature?.version ?: 0
                val major = versionInt shr 22
                val minor = (versionInt shr 12) and 0x3ff
                val patch = versionInt and 0xfff
                Pair(true, "Vulkan $major.$minor.$patch")
            } else {
                Pair(false, "Not Supported")
            }
        } catch (_: Exception) {
            Pair(false, "Unknown")
        }
    }

    /**
     * Detects SoC manufacturer and chipset name.
     */
    fun detectSocModel(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = Build.SOC_MODEL
            val mfg = Build.SOC_MANUFACTURER
            if (soc.isNotBlank()) "$mfg $soc" else "${Build.MANUFACTURER} ${Build.HARDWARE}"
        } else {
            "${Build.MANUFACTURER} ${Build.HARDWARE} (${Build.BOARD})"
        }
    }

    /**
     * Checks if GPU is ARM Mali or non-Adreno, where Qualcomm OpenCL extensions fail.
     */
    fun isMaliOrUnstableGpu(): Boolean {
        val hw = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        return hw.contains("mt") || hw.contains("mali") || board.contains("mt") || hw.contains("exynos")
    }
}
