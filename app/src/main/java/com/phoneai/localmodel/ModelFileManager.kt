package com.phoneai.localmodel

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

enum class ModelRole(
    val categoryName: String,
    val icon: String,
    val tabName: String,
    val description: String
) {
    CHAT("Chat & Reasoning", "💬", "Chat", "Language model for text generation, chat & reasoning (LLM)"),
    VISUAL("Visual & OCR", "👁️", "Visual", "Vision-Language model or projector for camera & screen (VLM/mmproj)"),
    AUDIO("Audio & Speech", "🎙️", "Audio", "Whisper speech-to-text audio transcription (ASR)"),
    EMBEDDING("Vector Database", "🗄️", "Database", "Dense semantic vector embeddings for on-device RAG")
}

object ModelFileManager {

    // Default recommended models
    const val DEFAULT_SMOLLM_360M = "smollm2-360m-instruct-q4_k_m.gguf"     // ~258 MB
    const val DEFAULT_GEMMA_2_2B_GGUF = "gemma-2-2b-it-q4_k_m.gguf"          // ~1.7 GB
    const val DEFAULT_QWEN_05B = "qwen2.5-0.5b-instruct-q4_k_m.gguf"        // ~398 MB
    const val DEFAULT_SMOLLM_135M = "smollm2-135m-instruct-q4_k_m.gguf"     // ~96 MB

    // High-speed CDN URLs (HuggingFace direct resolve)
    const val URL_SMOLLM_360M = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q4_k_m.gguf"
    const val URL_SMOLLM_135M = "https://huggingface.co/HuggingFaceTB/SmolLM2-135M-Instruct-GGUF/resolve/main/smollm2-135m-instruct-q4_k_m.gguf"
    const val URL_QWEN_05B = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"

    val RECOMMENDED_MODELS = listOf(
        DEFAULT_GEMMA_2_2B_GGUF,
        DEFAULT_SMOLLM_360M,
        DEFAULT_QWEN_05B,
        DEFAULT_SMOLLM_135M
    )

    val KNOWN_LOCAL_TMP_MODELS = listOf(
        DEFAULT_SMOLLM_360M,
        DEFAULT_GEMMA_2_2B_GGUF,
        DEFAULT_QWEN_05B,
        DEFAULT_SMOLLM_135M,
        "smolvlm-500m-instruct-q4_k_m.gguf",
        "mmproj-SmolVLM-500M-Instruct-f16.gguf"
    )

    /**
     * Categorizes a model file according to its primary functional purpose.
     */
    fun getModelRole(fileName: String): ModelRole {
        val n = fileName.lowercase()
        return when {
            isAudioModel(n) -> ModelRole.AUDIO
            isEmbeddingModel(n) -> ModelRole.EMBEDDING
            isVisionProjector(n) || isVisionModel(n) -> ModelRole.VISUAL
            else -> ModelRole.CHAT
        }
    }

    /**
     * Determines whether a file is a multimodal vision projector (adapter)
     * such as mmproj-GLM-OCR, mmproj-SmolVLM, etc.
     */
    fun isVisionProjector(fileName: String): Boolean {
        val n = fileName.lowercase()
        return n.startsWith("mmproj") || n.contains("mmproj") || n.contains("projector")
    }

    /**
     * Determines whether a file is a vision-language base model (e.g. SmolVLM, GLM-OCR).
     */
    fun isVisionModel(fileName: String): Boolean {
        val n = fileName.lowercase()
        return (n.contains("smolvlm") || n.contains("vlm") || n.contains("glm-ocr") || n.contains("vision")) && !isVisionProjector(n)
    }

    /**
     * Determines whether a file is an audio transcription model (e.g. Whisper).
     */
    fun isAudioModel(fileName: String): Boolean {
        val n = fileName.lowercase()
        return n.contains("whisper")
    }

    /**
     * Determines whether a file is a dedicated vector embedding model (e.g. MiniLM, BGE, BERT).
     */
    fun isEmbeddingModel(fileName: String): Boolean {
        val n = fileName.lowercase()
        return n.contains("minilm") || n.contains("bge") || n.contains("embed") || n.contains("bert")
    }

    /**
     * Determines whether a file is a standalone conversational language model (LLM).
     */
    fun isChatModel(fileName: String): Boolean {
        val n = fileName.lowercase()
        return !isVisionProjector(n) && !isEmbeddingModel(n) && !isAudioModel(n)
    }

    /**
     * Finds companion vision projector (mmproj) for a base vision model.
     */
    fun getCompanionProjector(context: Context, baseModelName: String): File? {
        val installed = getInstalledModels(context)
        val lowerBase = baseModelName.lowercase()
        return when {
            lowerBase.contains("smolvlm") -> {
                installed.firstOrNull { it.name.contains("mmproj", ignoreCase = true) && it.name.contains("smolvlm", ignoreCase = true) }
            }
            lowerBase.contains("glm") -> {
                installed.firstOrNull { it.name.contains("mmproj", ignoreCase = true) && it.name.contains("glm", ignoreCase = true) }
            }
            else -> {
                installed.firstOrNull { isVisionProjector(it.name) }
            }
        }
    }

    /**
     * List all model files installed in app private files, /data/local/tmp, or Downloads.
     */
    fun getInstalledModels(context: Context): List<File> {
        val list = mutableMapOf<String, File>()
        val searchDirs = mutableListOf<File>()

        // 1. App internal filesDir
        searchDirs.add(context.filesDir)

        // 2. App external filesDir
        try {
            context.getExternalFilesDir(null)?.let { searchDirs.add(it) }
        } catch (_: Exception) {}

        // 3. Developer /data/local/tmp (ADB pushed models)
        searchDirs.add(File("/data/local/tmp"))

        // 4. Download directory on device storage
        try {
            searchDirs.add(File("/sdcard/Download"))
            searchDirs.add(File("/sdcard/Download/MobileAIFramework"))
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)?.let {
                searchDirs.add(it)
            }
        } catch (_: Exception) {}

        for (dir in searchDirs) {
            try {
                if (dir.exists() && dir.canRead()) {
                    dir.listFiles()?.forEach { file ->
                        val n = file.name.lowercase()
                        if ((n.endsWith(".gguf") || n.endsWith(".bin") || n.endsWith(".task")) &&
                            file.length() > 5_000_000L &&
                            !isAudioModel(n) &&
                            !isEmbeddingModel(n)) {
                            if (!list.containsKey(file.name)) {
                                list[file.name] = file
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Fallback direct-path lookup for Android 14/15 SELinux where /data/local/tmp listFiles() is blocked
        for (name in KNOWN_LOCAL_TMP_MODELS) {
            if (!list.containsKey(name)) {
                val f = File("/data/local/tmp", name)
                if (f.exists() && f.canRead() && f.length() > 5_000_000L) {
                    list[name] = f
                }
            }
        }

        return list.values.sortedWith(
            compareBy(
                { isVisionProjector(it.name) },
                { getModelRole(it.name).ordinal },
                { -it.length() }
            )
        )
    }

    /**
     * Look for an existing base language model across scanned directories.
     */
    fun findExistingModel(context: Context, preferredRole: ModelRole = ModelRole.CHAT): File? {
        val installed = getInstalledModels(context)
        val roleCandidates = installed.filter { getModelRole(it.name) == preferredRole && !isVisionProjector(it.name) }

        // 1. Check recommended models first
        for (name in RECOMMENDED_MODELS) {
            roleCandidates.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        }

        return roleCandidates.firstOrNull() ?: installed.firstOrNull { isChatModel(it.name) }
    }

    /**
     * Finds any installed multimodal vision projector file (e.g. mmproj-GLM-OCR-Q8_0.gguf).
     */
    fun findVisionProjector(context: Context): File? {
        return getInstalledModels(context).firstOrNull { isVisionProjector(it.name) }
    }

    /**
     * Download a GGUF model directly from a URL (e.g. HuggingFace) into app private storage.
     */
    suspend fun downloadModel(
        context: Context,
        modelUrl: String = URL_SMOLLM_360M,
        destFileName: String = DEFAULT_SMOLLM_360M,
        onProgress: (percent: Int, copiedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(modelUrl).build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP error ${response.code}: ${response.message}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty response body from server."))
            val contentLength = body.contentLength()
            val destFile = File(context.filesDir, destFileName)

            copyStreamWithProgress(
                sourceStream = body.byteStream(),
                totalSize = contentLength,
                destFile = destFile,
                onProgress = onProgress
            )
        } catch (e: Exception) {
            Result.failure(Exception("Download failed: ${e.localizedMessage ?: e.message}", e))
        }
    }

    /**
     * Scan /data/local/tmp for any pushed models and copy into filesDir.
     * Prioritizes base language models over mmproj files.
     */
    suspend fun copyFromLocalTmp(
        context: Context,
        onProgress: (percent: Int, copiedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<File>()
        for (name in RECOMMENDED_MODELS) {
            candidates.add(File("/data/local/tmp", name))
        }

        try {
            File("/data/local/tmp").listFiles()?.forEach { f ->
                val n = f.name.lowercase()
                if ((n.endsWith(".gguf") || n.endsWith(".bin") || n.endsWith(".task")) && !candidates.contains(f)) {
                    candidates.add(f)
                }
            }
        } catch (_: Exception) {}

        // Sort: base models first, mmproj last
        candidates.sortWith(compareBy({ isVisionProjector(it.name) }, { -it.length() }))

        var sourceFile: File? = null
        for (f in candidates) {
            if (f.exists() && f.canRead() && f.length() > 10_000_000L) {
                sourceFile = f
                break
            }
        }

        if (sourceFile == null) {
            return@withContext Result.failure(
                Exception("Model not found in /data/local/tmp/. Push a base model file (e.g. $DEFAULT_SMOLLM_360M) using 'adb push <file> /data/local/tmp/'.")
            )
        }

        val destFile = File(context.filesDir, sourceFile.name)
        return@withContext copyStreamWithProgress(
            sourceStream = FileInputStream(sourceFile),
            totalSize = sourceFile.length(),
            destFile = destFile,
            onProgress = onProgress
        )
    }

    /**
     * Copy model file from a Storage Access Framework Uri (e.g. Downloads folder).
     */
    suspend fun copyFromUri(
        context: Context,
        uri: Uri,
        destFileName: String? = null,
        onProgress: (percent: Int, copiedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        val inputStream = contentResolver.openInputStream(uri)
            ?: return@withContext Result.failure(Exception("Unable to open selected file stream."))

        var totalSize = 0L
        var resolvedFileName = destFileName

        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    totalSize = cursor.getLong(sizeIndex)
                }
                if (resolvedFileName == null) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        resolvedFileName = cursor.getString(nameIndex)
                    }
                }
            }
        } catch (_: Exception) {}

        val finalName = resolvedFileName ?: DEFAULT_SMOLLM_360M
        val destFile = File(context.filesDir, finalName)

        return@withContext copyStreamWithProgress(
            sourceStream = inputStream,
            totalSize = totalSize,
            destFile = destFile,
            onProgress = onProgress
        )
    }

    private fun copyStreamWithProgress(
        sourceStream: InputStream,
        totalSize: Long,
        destFile: File,
        onProgress: (percent: Int, copiedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> {
        val tempDest = File(destFile.parentFile, "${destFile.name}.tmp")
        try {
            sourceStream.use { input ->
                FileOutputStream(tempDest).use { output ->
                    val buffer = ByteArray(1024 * 1024) // 1MB buffer
                    var bytesRead: Int
                    var totalCopied = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalCopied += bytesRead

                        val percent = if (totalSize > 0) {
                            ((totalCopied * 100) / totalSize).toInt()
                        } else {
                            -1
                        }
                        onProgress(percent, totalCopied, totalSize)
                    }
                    output.flush()
                }
            }

            if (destFile.exists()) {
                destFile.delete()
            }
            if (tempDest.renameTo(destFile)) {
                return Result.success(destFile)
            } else {
                return Result.failure(Exception("Failed to finalize model file name."))
            }
        } catch (e: Exception) {
            if (tempDest.exists()) tempDest.delete()
            return Result.failure(e)
        }
    }
}
