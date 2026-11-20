package com.mobileai.framework.multimodal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Base64
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Enhanced on-device offline OCR engine using bundled Google ML Kit Text Recognition (16.0.0).
 * Applies grayscale contrast-stretching + upscaling preprocessing and multi-pass scoring
 * to improve offline recognition on faint ink, shadowed paper, and handwriting.
 */
class OfflineVisionOcrEngine(private val context: Context) {

    companion object {
        private const val TAG = "OfflineVisionOcrEngine"
    }

    @Volatile
    private var recognizer: TextRecognizer? = null

    private fun getRecognizer(): TextRecognizer {
        return recognizer ?: synchronized(this) {
            recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also {
                recognizer = it
                Log.d(TAG, "Bundled offline ML Kit TextRecognizer initialized")
            }
        }
    }

    private suspend fun processSingleBitmap(bitmap: Bitmap): Result<String> =
        suspendCancellableCoroutine { continuation ->
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                getRecognizer().process(image)
                    .addOnSuccessListener { visionText ->
                        if (continuation.isActive) {
                            continuation.resume(Result.success(visionText.text.trim()))
                        }
                    }
                    .addOnFailureListener { e ->
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(e))
                        }
                    }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resume(Result.failure(e))
                }
            }
        }

    /**
     * Enhances contrast and resolution so faint pencil/pen strokes on dirty or shadowed paper
     * stand out more sharply for offline ML Kit recognition.
     */
    private fun enhanceForHandwriting(source: Bitmap): Bitmap? {
        return try {
            val maxDim = maxOf(source.width, source.height)
            val scale = when {
                maxDim < 1000 -> 1280f / maxDim.coerceAtLeast(1)
                maxDim > 2200 -> 2000f / maxDim
                else -> 1.0f
            }
            val targetW = (source.width * scale).toInt().coerceAtLeast(64)
            val targetH = (source.height * scale).toInt().coerceAtLeast(64)
            val scaled = if (scale != 1.0f) {
                Bitmap.createScaledBitmap(source, targetW, targetH, true)
            } else {
                source
            }

            val enhanced = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(enhanced)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            // Grayscale + high contrast stretch (contrast=1.65, brightness shift=-55)
            val grayMatrix = ColorMatrix().apply { setSaturation(0f) }
            val contrast = 1.65f
            val translate = (-0.5f * contrast + 0.5f) * 255f - 15f
            val contrastMatrix = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            grayMatrix.postConcat(contrastMatrix)
            paint.colorFilter = ColorMatrixColorFilter(grayMatrix)
            canvas.drawBitmap(scaled, 0f, 0f, paint)
            if (scaled !== source) scaled.recycle()
            enhanced
        } catch (e: Exception) {
            Log.w(TAG, "Image enhancement skipped: ${e.message}")
            null
        }
    }

    private fun scoreTextQuality(text: String): Int {
        if (text.isBlank()) return 0
        val alnum = text.count { it.isLetterOrDigit() }
        val words = text.split("\\s+".toRegex()).count { it.length >= 2 }
        return alnum + words * 3
    }

    /**
     * Multi-pass offline OCR: runs on original + high-contrast enhanced copy and returns best result.
     */
    suspend fun recognizeTextFromBitmap(bitmap: Bitmap): Result<String> {
        if (bitmap.isRecycled) {
            Log.e(TAG, "Cannot perform OCR: Bitmap is recycled")
            return Result.failure(IllegalArgumentException("Bitmap is already recycled"))
        }

        val startTime = System.currentTimeMillis()
        val rawResult = processSingleBitmap(bitmap)
        if (rawResult.isFailure) return rawResult

        val rawText = rawResult.getOrDefault("")
        var bestText = rawText

        // Second pass on contrast-enhanced grayscale bitmap for faint/dirty handwriting & shadows
        val enhanced = enhanceForHandwriting(bitmap)
        if (enhanced != null) {
            try {
                val enhResult = processSingleBitmap(enhanced)
                val enhText = enhResult.getOrDefault("")
                if (scoreTextQuality(enhText) > scoreTextQuality(bestText)) {
                    bestText = enhText
                }
            } finally {
                if (!enhanced.isRecycled) enhanced.recycle()
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Multi-pass offline OCR finished in ${elapsed}ms: ${bestText.length} chars")
        return if (bestText.isBlank()) {
            Result.success("No legible text detected in image.")
        } else {
            Result.success(bestText)
        }
    }

    /**
     * Extracts text from an ImagePayload (base64 JPEG/PNG).
     */
    suspend fun recognizeTextFromPayload(payload: ImagePayload): Result<String> {
        return try {
            val bytes = Base64.decode(payload.base64Data, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return Result.failure(IllegalArgumentException("Failed to decode image payload into bitmap"))
            recognizeTextFromBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding payload for OCR", e)
            Result.failure(e)
        }
    }

    fun close() {
        synchronized(this) {
            try {
                recognizer?.close()
                Log.d(TAG, "TextRecognizer closed")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing TextRecognizer", e)
            }
            recognizer = null
        }
    }
}
