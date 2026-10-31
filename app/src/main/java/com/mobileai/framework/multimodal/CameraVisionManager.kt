package com.mobileai.framework.multimodal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/**
 * Camera2 manager providing real-time camera preview, periodic frame sampling
 * for multimodal AI reasoning, and on-demand high-resolution photo snapshots.
 */
class CameraVisionManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraVisionManager"
        private const val TARGET_VISION_WIDTH = 640
        private const val TARGET_VISION_HEIGHT = 480
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var imageReader: ImageReader? = null
    private var isStreamingVision = false
    private var lastSampleTimeMs = 0L
    private var sampleIntervalMs = 1500L // Default: 1 frame every 1.5s for AI perception

    private var currentCameraId: String? = null
    private var isFrontFacing = false
    private var pendingSnapshotCallback: ((Bitmap, ImagePayload) -> Unit)? = null

    interface FrameListener {
        fun onFrameSampled(bitmap: Bitmap, imagePayload: ImagePayload)
        fun onError(message: String)
    }

    private var frameListener: FrameListener? = null

    init {
        findDefaultCamera()
    }

    private fun findDefaultCamera() {
        try {
            val cameraIds = cameraManager.cameraIdList
            for (id in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    currentCameraId = id
                    isFrontFacing = false
                    return
                }
            }
            if (cameraIds.isNotEmpty()) {
                currentCameraId = cameraIds[0]
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error discovering cameras", e)
        }
    }

    fun switchCamera(textureView: TextureView) {
        val targetFacing = if (isFrontFacing) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        try {
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == targetFacing) {
                    currentCameraId = id
                    isFrontFacing = !isFrontFacing
                    stopCamera()
                    startCamera(textureView, frameListener)
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error switching camera", e)
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted stopping camera thread", e)
        }
    }

    /**
     * Start camera preview and frame sampling.
     */
    @SuppressLint("MissingPermission")
    fun startCamera(textureView: TextureView, listener: FrameListener?) {
        frameListener = listener
        startBackgroundThread()

        val cameraId = currentCameraId ?: run {
            listener?.onError("No available camera found")
            return
        }

        if (textureView.isAvailable) {
            openCamera(cameraId, textureView)
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    openCamera(cameraId, textureView)
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(cameraId: String, textureView: TextureView) {
        try {
            imageReader = ImageReader.newInstance(
                TARGET_VISION_WIDTH, TARGET_VISION_HEIGHT,
                ImageFormat.JPEG, 2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    val snapshotCb = pendingSnapshotCallback
                    if (snapshotCb != null) {
                        pendingSnapshotCallback = null
                        val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                val payload = ImagePayload.fromBitmap(bitmap, 85)
                                CoroutineScope(Dispatchers.Main).launch {
                                    snapshotCb.invoke(bitmap, payload)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error capturing camera snapshot", e)
                        } finally {
                            image.close()
                        }
                        return@setOnImageAvailableListener
                    }

                    val now = System.currentTimeMillis()
                    if (isStreamingVision && (now - lastSampleTimeMs >= sampleIntervalMs)) {
                        lastSampleTimeMs = now
                        val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                val payload = ImagePayload.fromBitmap(bitmap, 75)
                                CoroutineScope(Dispatchers.Main).launch {
                                    frameListener?.onFrameSampled(bitmap, payload)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing camera frame", e)
                        } finally {
                            image.close()
                        }
                    } else {
                        // Discard frame if not due for sampling
                        val image = reader.acquireLatestImage()
                        image?.close()
                    }
                }, backgroundHandler)
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(textureView)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    frameListener?.onError("Camera error code: $error")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            frameListener?.onError("Failed to open camera: ${e.message}")
        }
    }

    private fun createCaptureSession(textureView: TextureView) {
        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(TARGET_VISION_WIDTH, TARGET_VISION_HEIGHT)
        val previewSurface = Surface(texture)
        val readerSurface = imageReader?.surface ?: return

        try {
            val previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                addTarget(readerSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            camera.createCaptureSession(
                listOf(previewSurface, readerSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
                            isStreamingVision = true
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start camera repeating request", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        frameListener?.onError("Failed to configure camera capture session")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            frameListener?.onError("Capture session error: ${e.message}")
        }
    }

    /**
     * Capture single immediate high-quality snapshot.
     */
    fun captureSnapshot(onCaptured: (Bitmap, ImagePayload) -> Unit, onError: (String) -> Unit) {
        val camera = cameraDevice
        val reader = imageReader
        if (camera == null || reader == null) {
            onError("Camera is not active")
            return
        }

        pendingSnapshotCallback = onCaptured
        try {
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            captureSession?.capture(captureBuilder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            pendingSnapshotCallback = null
            onError("Snapshot error: ${e.message}")
        }
    }

    fun setVisionStreamingEnabled(enabled: Boolean, intervalMs: Long = 1500L) {
        this.isStreamingVision = enabled
        this.sampleIntervalMs = intervalMs
    }

    fun isVisionStreaming(): Boolean = isStreamingVision

    fun stopCamera() {
        isStreamingVision = false
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
        stopBackgroundThread()
    }
}

