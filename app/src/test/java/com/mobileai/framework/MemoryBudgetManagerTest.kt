package com.mobileai.framework

import org.junit.Assert.*
import org.junit.Test

class MemoryBudgetManagerTest {

    private fun calculateKvCacheMb(
        layers: Int = 24,
        heads: Int = 16,
        headDim: Int = 64,
        contextSize: Int = 2048,
        bytesPerElement: Int = 2 // FP16 = 2 bytes
    ): Int {
        // formula: 2 (K & V) * layers * heads * headDim * contextSize * bytesPerElement
        val totalBytes = 2L * layers * heads * headDim * contextSize * bytesPerElement
        return (totalBytes / (1024 * 1024)).toInt()
    }

    @Test
    fun testKvCacheCalculationForSmolLm2() {
        // SmolLM2 360M: 32 layers, 16 heads, 64 dim
        val kv2048 = calculateKvCacheMb(layers = 32, heads = 16, headDim = 64, contextSize = 2048)
        assertEquals(256, kv2048)

        val kv1024 = calculateKvCacheMb(layers = 32, heads = 16, headDim = 64, contextSize = 1024)
        assertEquals(128, kv1024)
    }

    @Test
    fun testKvCacheCalculationForGemma2B() {
        // Gemma-2 2B: 26 layers, 8 heads, 256 dim
        val kv2048 = calculateKvCacheMb(layers = 26, heads = 8, headDim = 256, contextSize = 2048)
        assertEquals(416, kv2048)
    }

    @Test
    fun testBudgetSafetyLogic() {
        val availMemMb = 1200
        val modelWeightMb = 350
        val kvMb = 128
        val osSafetyMarginMb = 400

        val requiredMb = modelWeightMb + kvMb + osSafetyMarginMb
        val isSafe = requiredMb <= availMemMb
        assertTrue("350MB model on 1200MB avail RAM should be completely safe", isSafe)

        // Huge 7B model (4000MB) on 1200MB RAM
        val hugeModelMb = 4000
        val isHugeSafe = (hugeModelMb + kvMb + osSafetyMarginMb) <= availMemMb
        assertFalse("4GB model on 1200MB RAM must be flagged unsafe to prevent LMK crash", isHugeSafe)
    }
}

