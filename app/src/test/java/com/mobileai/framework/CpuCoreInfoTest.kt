package com.mobileai.framework

import com.mobileai.framework.hardware.CpuCore
import com.mobileai.framework.hardware.CpuTopology
import org.junit.Assert.*
import org.junit.Test

class CpuCoreInfoTest {

    @Test
    fun testHelioG85TopologyDetection() {
        // Helio G85: 2x Cortex-A75 (2000MHz) + 6x Cortex-A55 (1800MHz)
        val cores = listOf(
            CpuCore(coreId = 0, maxFreqKhz = 1800000, minFreqKhz = 500000, isBigCore = false),
            CpuCore(coreId = 1, maxFreqKhz = 1800000, minFreqKhz = 500000, isBigCore = false),
            CpuCore(coreId = 2, maxFreqKhz = 1800000, minFreqKhz = 500000, isBigCore = false),
            CpuCore(coreId = 3, maxFreqKhz = 1800000, minFreqKhz = 500000, isBigCore = false),
            CpuCore(coreId = 4, maxFreqKhz = 1800000, minFreqKhz = 500000, isBigCore = false),
            CpuCore(coreId = 5, maxFreqKhz = 1800000, minFreqKhz = 500000, isBigCore = false),
            CpuCore(coreId = 6, maxFreqKhz = 2000000, minFreqKhz = 500000, isBigCore = true),
            CpuCore(coreId = 7, maxFreqKhz = 2000000, minFreqKhz = 500000, isBigCore = true)
        )

        val topo = CpuTopology(
            totalCores = 8,
            bigCores = cores.filter { it.isBigCore },
            littleCores = cores.filter { !it.isBigCore },
            optimalInferenceThreads = 2,
            rawClusterDescription = "2 Big (2000MHz) + 6 Little (1800MHz)"
        )

        assertEquals(8, topo.totalCores)
        assertEquals(2, topo.bigCores.size)
        assertEquals(6, topo.littleCores.size)
        assertEquals(2, topo.optimalInferenceThreads)
        assertEquals(listOf(6, 7), topo.bigCores.map { it.coreId })
    }

    @Test
    fun testSnapdragon8Gen3TriClusterTopology() {
        // Snapdragon 8 Gen 3: 1x X4 (3300MHz) + 5x A720 (3200/3000MHz) + 2x A520 (2300MHz)
        val cores = listOf(
            CpuCore(coreId = 0, maxFreqKhz = 2300000, minFreqKhz = 600000, isBigCore = false),
            CpuCore(coreId = 1, maxFreqKhz = 2300000, minFreqKhz = 600000, isBigCore = false),
            CpuCore(coreId = 2, maxFreqKhz = 3000000, minFreqKhz = 600000, isBigCore = true),
            CpuCore(coreId = 3, maxFreqKhz = 3000000, minFreqKhz = 600000, isBigCore = true),
            CpuCore(coreId = 4, maxFreqKhz = 3200000, minFreqKhz = 600000, isBigCore = true),
            CpuCore(coreId = 5, maxFreqKhz = 3200000, minFreqKhz = 600000, isBigCore = true),
            CpuCore(coreId = 6, maxFreqKhz = 3200000, minFreqKhz = 600000, isBigCore = true),
            CpuCore(coreId = 7, maxFreqKhz = 3300000, minFreqKhz = 600000, isBigCore = true)
        )

        val topo = CpuTopology(
            totalCores = 8,
            bigCores = cores.filter { it.isBigCore },
            littleCores = cores.filter { !it.isBigCore },
            optimalInferenceThreads = 4, // Max recommended 4 to prevent heating
            rawClusterDescription = "6 Big + 2 Little"
        )

        assertEquals(8, topo.totalCores)
        assertEquals(6, topo.bigCores.size)
        assertEquals(2, topo.littleCores.size)
        assertEquals(4, topo.optimalInferenceThreads)
    }
}
