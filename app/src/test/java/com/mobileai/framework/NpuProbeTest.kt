package com.mobileai.framework

import com.mobileai.framework.npu.AcceleratorType
import com.mobileai.framework.npu.NpuAccelerator
import com.mobileai.framework.npu.NpuCapabilityReport
import org.junit.Assert.*
import org.junit.Test

class NpuProbeTest {

    @Test
    fun testNpuCapabilityReportFlags() {
        val noNpuReport = NpuCapabilityReport(
            hasNnApi = false,
            nnApiVersion = 0,
            accelerators = emptyList(),
            preferredAccelerator = null,
            recommendedEmbeddingBatchSize = 1,
            supportsInt8Quantization = false,
            supportsFp16Inference = false,
            estimatedEmbeddingSpeedupVsCpu = 1.0f,
            summary = "No NNAPI hardware accelerator detected"
        )
        assertFalse(noNpuReport.hasNnApi)
        assertEquals(0, noNpuReport.accelerators.size)
        assertNull(noNpuReport.preferredAccelerator)

        val flagshipNpu = NpuAccelerator(
            name = "Hexagon NPU",
            vendor = "Qualcomm",
            type = AcceleratorType.NPU,
            nnApiDeviceName = "qti-dsp",
            estimatedSpeedupFactor = 5.0f,
            supportsFp16 = true,
            supportsInt8 = true,
            isPreferred = true
        )

        val flagshipNpuReport = NpuCapabilityReport(
            hasNnApi = true,
            nnApiVersion = 34,
            accelerators = listOf(flagshipNpu),
            preferredAccelerator = flagshipNpu,
            recommendedEmbeddingBatchSize = 8,
            supportsInt8Quantization = true,
            supportsFp16Inference = true,
            estimatedEmbeddingSpeedupVsCpu = 5.0f,
            summary = "Hexagon NPU (5.0x speedup)"
        )

        assertTrue(flagshipNpuReport.hasNnApi)
        assertEquals(1, flagshipNpuReport.accelerators.size)
        assertEquals(AcceleratorType.NPU, flagshipNpuReport.accelerators[0].type)
        assertEquals(5.0f, flagshipNpuReport.estimatedEmbeddingSpeedupVsCpu, 0.01f)
        assertEquals("Hexagon NPU", flagshipNpuReport.preferredAccelerator?.name)
    }

    @Test
    fun testNpuAcceleratorProperties() {
        val accel = NpuAccelerator(
            name = "MediaTek APU 790",
            vendor = "MediaTek",
            type = AcceleratorType.NPU,
            nnApiDeviceName = "mtk-apu",
            estimatedSpeedupFactor = 4.5f,
            supportsFp16 = true,
            supportsInt8 = true,
            isPreferred = true
        )

        assertEquals("MediaTek APU 790", accel.name)
        assertEquals("MediaTek", accel.vendor)
        assertEquals(AcceleratorType.NPU, accel.type)
        assertEquals(4.5f, accel.estimatedSpeedupFactor, 0.01f)
        assertTrue(accel.supportsFp16)
        assertTrue(accel.supportsInt8)
        assertTrue(accel.isPreferred)
    }
}
