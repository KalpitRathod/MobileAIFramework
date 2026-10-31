package com.mobileai.framework.multimodal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * High-performance on-device vision & OCR processing engine.
 * Converts camera photos, screenshots, and image payloads into structured text
 * completely offline in ~30ms without needing external cloud APIs or gigabytes of weights.
 */
class OfflineVisionOcrEngine(private val context: Context) {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Extracts text from an Android Bitmap.
     */
    suspend fun recognizeTextFromBitmap(bitmap: Bitmap): Result<String> = suspendCancellableCoroutine { continuation ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (continuation.isActive) {
                        val text = visionText.text.trim()
                        if (text.isBlank()) {
                            continuation.resume(Result.success("No legible text detected in image."))
                        } else {
                            continuation.resume(Result.success(text))
                        }
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
     * Extracts text from an ImagePayload (base64 JPEG/PNG).
     */
    suspend fun recognizeTextFromPayload(payload: ImagePayload): Result<String> {
        return try {
            val bytes = Base64.decode(payload.base64Data, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return Result.failure(IllegalArgumentException("Failed to decode image payload into bitmap"))
            recognizeTextFromBitmap(bitmap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        try {
            recognizer.close()
        } catch (_: Exception) {}
    }
}


