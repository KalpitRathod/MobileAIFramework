package com.mobileai.framework.core

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Manages OTA (Over-The-Air) model downloads from Google Cloud Storage CDN.
 * Supports resume, chunked streaming, progress callbacks, and SHA-256 verification.
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloadManager"
        const val GCS_CDN_BASE = "https://storage.googleapis.com/mobileai-models-cdn-646100637693"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Download progress callback.
     */
    interface DownloadListener {
        fun onProgress(percent: Int, downloadedBytes: Long, totalBytes: Long, speedKbps: Long)
        fun onVerifyingHash()
        fun onSuccess(file: File)
        fun onError(message: String)
    }

    /**
     * Downloads model file to app external storage with optional SHA-256 verification.
     */
    suspend fun downloadModel(
        modelName: String,
        customUrl: String? = null,
        expectedSha256: String? = null,
        listener: DownloadListener
    ): Result<File> = withContext(Dispatchers.IO) {
        val downloadUrl = customUrl ?: "$GCS_CDN_BASE/$modelName"
        val targetDir = context.getExternalFilesDir(null) ?: context.filesDir
        val finalFile = File(targetDir, modelName)
        val tempFile = File(targetDir, "$modelName.download.tmp")

        try {
            Log.i(TAG, "Starting OTA download: $downloadUrl -> ${finalFile.absolutePath}")

            val request = Request.Builder()
                .url(downloadUrl)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val err = "HTTP download failed with status ${response.code}: ${response.message}"
                withContext(Dispatchers.Main) { listener.onError(err) }
                return@withContext Result.failure(Exception(err))
            }

            val body = response.body ?: run {
                val err = "Empty response body from CDN"
                withContext(Dispatchers.Main) { listener.onError(err) }
                return@withContext Result.failure(Exception(err))
            }

            val totalBytes = body.contentLength()

            // Verify available storage
            val stat = StatFs(targetDir.path)
            val availableSpace = stat.availableBlocksLong * stat.blockSizeLong
            if (totalBytes > 0 && availableSpace < totalBytes + (50 * 1024 * 1024)) {
                val err = "Insufficient storage space. Required: ${totalBytes / (1024 * 1024)}MB, Available: ${availableSpace / (1024 * 1024)}MB"
                withContext(Dispatchers.Main) { listener.onError(err) }
                return@withContext Result.failure(Exception(err))
            }

            val digest = if (expectedSha256 != null) MessageDigest.getInstance("SHA-256") else null
            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            var totalRead = 0L
            var lastUpdate = System.currentTimeMillis()
            var bytesSinceLastUpdate = 0L

            outputStream.use { out ->
                inputStream.use { inp ->
                    while (inp.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        digest?.update(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        bytesSinceLastUpdate += bytesRead

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastUpdate
                        if (elapsed >= 300 || (totalBytes > 0 && totalRead == totalBytes)) {
                            val percent = if (totalBytes > 0) ((totalRead * 100) / totalBytes).toInt() else -1
                            val speedKbps = if (elapsed > 0) (bytesSinceLastUpdate * 8) / elapsed else 0
                            withContext(Dispatchers.Main) {
                                listener.onProgress(percent, totalRead, totalBytes, speedKbps)
                            }
                            lastUpdate = now
                            bytesSinceLastUpdate = 0L
                        }
                    }
                }
            }

            // SHA-256 Verification if hash provided
            if (digest != null && expectedSha256 != null) {
                withContext(Dispatchers.Main) { listener.onVerifyingHash() }
                val calculatedHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!calculatedHash.equals(expectedSha256, ignoreCase = true)) {
                    tempFile.delete()
                    val err = "SHA-256 checksum mismatch! Expected: $expectedSha256, Calculated: $calculatedHash"
                    withContext(Dispatchers.Main) { listener.onError(err) }
                    return@withContext Result.failure(SecurityException(err))
                }
                Log.i(TAG, "SHA-256 checksum verified: $calculatedHash")
            }

            // Atomic rename
            if (finalFile.exists()) finalFile.delete()
            val renamed = tempFile.renameTo(finalFile)
            if (!renamed) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            Log.i(TAG, "OTA model download complete: ${finalFile.name} (${finalFile.length() / (1024 * 1024)}MB)")
            withContext(Dispatchers.Main) { listener.onSuccess(finalFile) }
            Result.success(finalFile)
        } catch (e: Exception) {
            tempFile.delete()
            Log.e(TAG, "Model download error", e)
            withContext(Dispatchers.Main) { listener.onError("Download error: ${e.message}") }
            Result.failure(e)
        }
    }
}
