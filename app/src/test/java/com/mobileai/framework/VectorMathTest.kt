package com.mobileai.framework

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class VectorMathTest {

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else (dot / denom).coerceIn(-1f, 1f)
    }

    @Test
    fun testIdenticalVectorsHaveSimilarityOne() {
        val v = floatArrayOf(0.5f, 0.2f, 0.8f, 0.1f)
        val sim = cosineSimilarity(v, v)
        assertEquals(1.0f, sim, 0.0001f)
    }

    @Test
    fun testOrthogonalVectorsHaveSimilarityZero() {
        val a = floatArrayOf(1.0f, 0.0f, 0.0f)
        val b = floatArrayOf(0.0f, 1.0f, 0.0f)
        val sim = cosineSimilarity(a, b)
        assertEquals(0.0f, sim, 0.0001f)
    }

    @Test
    fun testOppositeVectorsHaveSimilarityMinusOne() {
        val a = floatArrayOf(0.5f, 0.5f)
        val b = floatArrayOf(-0.5f, -0.5f)
        val sim = cosineSimilarity(a, b)
        assertEquals(-1.0f, sim, 0.0001f)
    }

    @Test
    fun testZeroVectorReturnsZero() {
        val a = floatArrayOf(0f, 0f, 0f)
        val b = floatArrayOf(1f, 2f, 3f)
        val sim = cosineSimilarity(a, b)
        assertEquals(0.0f, sim, 0.0001f)
    }
}

