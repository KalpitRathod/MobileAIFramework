package com.mobileai.framework

import org.junit.Assert.*
import org.junit.Test

class ThermalGovernorLogicTest {

    private fun computeAdaptedThreads(baselineThreads: Int, thermalStatus: Int): Int {
        // Mirrored logic from ThermalGovernor
        return when (thermalStatus) {
            0, 1 -> baselineThreads // NONE, LIGHT
            2 -> (baselineThreads - 1).coerceAtLeast(2) // MODERATE
            3 -> 2.coerceAtMost(baselineThreads) // SEVERE: Throttle to max 2 threads
            4, 5, 6 -> 1 // CRITICAL, EMERGENCY, SHUTDOWN: 1 thread emergency
            else -> baselineThreads
        }
    }

    @Test
    fun testOctaCoreBigCoreThrottling() {
        val baseline = 4 // e.g. 4 Big cores (Cortex-X / Cortex-A78)

        assertEquals(4, computeAdaptedThreads(baseline, 0)) // NONE
        assertEquals(4, computeAdaptedThreads(baseline, 1)) // LIGHT
        assertEquals(3, computeAdaptedThreads(baseline, 2)) // MODERATE
        assertEquals(2, computeAdaptedThreads(baseline, 3)) // SEVERE
        assertEquals(1, computeAdaptedThreads(baseline, 4)) // CRITICAL
        assertEquals(1, computeAdaptedThreads(baseline, 6)) // SHUTDOWN
    }

    @Test
    fun testDualBigCoreBudgetPhoneThrottling() {
        val baseline = 2 // e.g. MediaTek Helio G85 with 2x A75 Big cores

        assertEquals(2, computeAdaptedThreads(baseline, 0))
        assertEquals(2, computeAdaptedThreads(baseline, 1))
        assertEquals(2, computeAdaptedThreads(baseline, 2)) // Coerced at least 2
        assertEquals(2, computeAdaptedThreads(baseline, 3)) // Coerced max 2
        assertEquals(1, computeAdaptedThreads(baseline, 4)) // Emergency single-thread
    }
}

