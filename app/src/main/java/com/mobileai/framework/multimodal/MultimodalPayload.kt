package com.mobileai.framework.multimodal

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Data containers for multimodal inputs (images, camera frames, screenshots, audio).
 */
data class ImagePayload(
    val base64Data: String,
    val mimeType: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0,
    val timestampMs: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromBitmap(bitmap: Bitmap, quality: Int = 80): ImagePayload {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            val bytes = stream.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            return ImagePayload(
                base64Data = base64,
                mimeType = "image/jpeg",
                width = bitmap.width,
                height = bitmap.height
            )
        }
    }
}

data class AudioPayload(
    val base64Data: String,
    val mimeType: String = "audio/wav",
    val durationMs: Long = 0,
    val sampleRate: Int = 16000,
    val channelCount: Int = 1,
    val timestampMs: Long = System.currentTimeMillis()
) {
    companion object {
        fun fromBytes(bytes: ByteArray, mimeType: String = "audio/wav", durationMs: Long = 0): AudioPayload {
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            return AudioPayload(
                base64Data = base64,
                mimeType = mimeType,
                durationMs = durationMs
            )
        }
    }
}

data class ScreenPayload(
    val image: ImagePayload,
    val displayWidth: Int,
    val displayHeight: Int,
    val orientation: Int,
    val windowTitle: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class MultimodalContext(
    val textPrompt: String = "",
    val cameraImage: ImagePayload? = null,
    val screenCapture: ScreenPayload? = null,
    val audioClip: AudioPayload? = null,
    val audioTranscript: String? = null
) {
    val hasVisual: Boolean get() = cameraImage != null || screenCapture != null
    val hasAudio: Boolean get() = audioClip != null || !audioTranscript.isNullOrBlank()
    val isMultimodal: Boolean get() = hasVisual || hasAudio
}

