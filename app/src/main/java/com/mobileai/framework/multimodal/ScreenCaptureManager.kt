package com.mobileai.framework.multimodal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/**
 * Manages live screen recording, real-time screen capture updates,
 * and periodic screen snapshot extraction for AI reasoning.
 */
class ScreenCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "ScreenCaptureManager"
        const val SCREEN_CAPTURE_REQUEST_CODE = 4099
        private const val VIRTUAL_DISPLAY_NAME = "MobileAI_Screen_Stream"
        private const val DOWNSAMPLE_SCALE = 0.5f // Downsample to 50% for fast AI reasoning
    }

    private val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var isRecording = false
    private var sampleIntervalMs = 1500L
    private var lastSampleTimeMs = 0L

    private var width = 720
    private var height = 1280
    private var densityDpi = 320

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    interface ScreenCaptureListener {
        fun onScreenFrame(bitmap: Bitmap, screenPayload: ScreenPayload)
        fun onCaptureStarted()
        fun onCaptureStopped()
        fun onError(message: String)
    }

    private var captureListener: ScreenCaptureListener? = null

    /**
     * Intent to launch screen projection permission dialog from an Activity.
     */
    fun createScreenCaptureIntent(): Intent {
        return projectionManager.createScreenCaptureIntent()
    }

    /**
     * Start live screen recording / stream using result from screen capture permission intent.
     */
    fun startScreenStream(
        resultCode: Int,
        resultData: Intent,
        listener: ScreenCaptureListener,
        intervalMs: Long = 1500L
    ) {
        if (resultCode != Activity.RESULT_OK) {
            listener.onError("Screen capture permission denied by user")
            return
        }

        this.captureListener = listener
        this.sampleIntervalMs = intervalMs

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        // Downscale for efficient mobile AI processing
        this.width = (metrics.widthPixels * DOWNSAMPLE_SCALE).toInt().coerceAtLeast(360)
        this.height = (metrics.heightPixels * DOWNSAMPLE_SCALE).toInt().coerceAtLeast(640)
        this.densityDpi = metrics.densityDpi

        try {
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            if (mediaProjection == null) {
                listener.onError("Failed to obtain MediaProjection instance")
                return
            }

            // ImageReader in RGBA_8888 for easy bitmap conversion
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                val now = System.currentTimeMillis()
                if (isRecording && (now - lastSampleTimeMs >= sampleIntervalMs)) {
                    lastSampleTimeMs = now
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)

                        // Crop if rowPadding exists
                        val finalBitmap = if (rowPadding > 0) {
                            Bitmap.createBitmap(bitmap, 0, 0, width, height)
                        } else {
                            bitmap
                        }

                        val imagePayload = ImagePayload.fromBitmap(finalBitmap, 75)
                        val screenPayload = ScreenPayload(
                            image = imagePayload,
                            displayWidth = width,
                            displayHeight = height,
                            orientation = context.resources.configuration.orientation
                        )

                        mainHandler.post {
                            captureListener?.onScreenFrame(finalBitmap, screenPayload)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing screen frame", e)
                    } finally {
                        image.close()
                    }
                } else {
                    val image = reader.acquireLatestImage()
                    image?.close()
                }
            }, null)

            isRecording = true
            listener.onCaptureStarted()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen capture", e)
            listener.onError("Screen capture initialization failed: ${e.message}")
        }
    }

    /**
     * Stop screen recording and release resources.
     */
    fun stopScreenStream() {
        if (!isRecording) return
        isRecording = false
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping screen stream", e)
        }
        captureListener?.onCaptureStopped()
    }

    fun isScreenStreamActive(): Boolean = isRecording
}

