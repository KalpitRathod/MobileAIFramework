package com.mobileai.framework.cloud

import android.util.Log
import com.mobileai.framework.core.GenerationChunk
import com.mobileai.framework.multimodal.MultimodalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Unified high-performance client for external cloud AI providers:
 * Google Gemini, Anthropic Claude, OpenAI, xAI (Grok), DeepSeek, Kimi, Z-AI, and Mistral (European).
 * Supports real-time SSE streaming, multimodal image/audio payloads, and autonomous agent calls.
 */
class CloudAiClient {

    companion object {
        private const val TAG = "CloudAiClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Real-time streaming generation returning Flow<GenerationChunk>.
     */
    fun generateStream(
        prompt: String,
        multimodalContext: MultimodalContext? = null,
        config: CloudAiConfig
    ): Flow<GenerationChunk> = flow {
        if (config.apiKey.isBlank()) {
            emit(GenerationChunk(
                text = "[Error: API key for ${config.provider.displayName} is missing. Please configure it in Cloud AI Hub.]",
                tokensGenerated = 0,
                tokensPerSecond = 0f,
                elapsedMs = 0L,
                isFinished = true,
                telemetrySummary = "Config Error"
            ))
            return@flow
        }

        val startTime = System.currentTimeMillis()
        var tokenCount = 0
        val accumulated = StringBuilder()

        try {
            val request = buildStreamRequest(prompt, multimodalContext, config)
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "HTTP ${response.code}"
                emit(GenerationChunk(
                    text = "\n[API Error ${response.code}: $errorBody]",
                    tokensGenerated = 0,
                    tokensPerSecond = 0f,
                    elapsedMs = System.currentTimeMillis() - startTime,
                    isFinished = true,
                    telemetrySummary = "API Error ${response.code}"
                ))
                response.close()
                return@flow
            }

            val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line?.trim() ?: continue
                if (currentLine.isEmpty() || currentLine.startsWith(":")) continue // Ping / comment

                val chunkText = parseStreamChunk(currentLine, config.provider)
                if (!chunkText.isNullOrEmpty()) {
                    tokenCount++
                    accumulated.append(chunkText)
                    val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                    val tokPerSec = if (elapsedSec > 0.1f) tokenCount / elapsedSec else 0f

                    emit(GenerationChunk(
                        text = accumulated.toString(),
                        tokensGenerated = tokenCount,
                        tokensPerSecond = tokPerSec,
                        elapsedMs = System.currentTimeMillis() - startTime,
                        isFinished = false
                    ))
                }
            }

            response.close()

            // Final completion chunk
            val finalElapsedMs = System.currentTimeMillis() - startTime
            val finalElapsedSec = finalElapsedMs / 1000f
            val finalTokPerSec = if (finalElapsedSec > 0.1f) tokenCount / finalElapsedSec else tokenCount.toFloat()
            val summary = "${config.provider.displayName} • ${String.format("%.1f", finalTokPerSec)} tok/s • $tokenCount tokens"

            emit(GenerationChunk(
                text = accumulated.toString(),
                tokensGenerated = tokenCount,
                tokensPerSecond = finalTokPerSec,
                elapsedMs = finalElapsedMs,
                isFinished = true,
                telemetrySummary = summary
            ))

        } catch (e: Exception) {
            Log.e(TAG, "Stream error for provider ${config.provider}", e)
            emit(GenerationChunk(
                text = accumulated.toString() + "\n[Network Error: ${e.message}]",
                tokensGenerated = tokenCount,
                tokensPerSecond = 0f,
                elapsedMs = System.currentTimeMillis() - startTime,
                isFinished = true,
                telemetrySummary = "Network Error"
            ))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Synchronous / suspend call for autonomous agents and tool-calling execution.
     */
    suspend fun generateSync(
        prompt: String,
        multimodalContext: MultimodalContext? = null,
        config: CloudAiConfig
    ): String = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) {
            return@withContext "Error: API key for ${config.provider.displayName} is missing."
        }

        try {
            val request = buildSyncRequest(prompt, multimodalContext, config)
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "HTTP ${response.code}"
                response.close()
                return@withContext "API Error ${response.code}: $errorBody"
            }

            val bodyString = response.body?.string() ?: ""
            response.close()
            return@withContext parseSyncResponse(bodyString, config.provider)
        } catch (e: Exception) {
            Log.e(TAG, "Sync call error for provider ${config.provider}", e)
            return@withContext "Network Error: ${e.message}"
        }
    }

    private fun buildStreamRequest(
        prompt: String,
        context: MultimodalContext?,
        config: CloudAiConfig
    ): Request {
        return when (config.provider) {
            CloudProvider.GEMINI -> buildGeminiRequest(prompt, context, config, stream = true)
            CloudProvider.CLAUDE -> buildClaudeRequest(prompt, context, config, stream = true)
            else -> buildOpenAiCompatibleRequest(prompt, context, config, stream = true)
        }
    }

    private fun buildSyncRequest(
        prompt: String,
        context: MultimodalContext?,
        config: CloudAiConfig
    ): Request {
        return when (config.provider) {
            CloudProvider.GEMINI -> buildGeminiRequest(prompt, context, config, stream = false)
            CloudProvider.CLAUDE -> buildClaudeRequest(prompt, context, config, stream = false)
            else -> buildOpenAiCompatibleRequest(prompt, context, config, stream = false)
        }
    }

    // ==================== Gemini Request / Response ====================

    private fun buildGeminiRequest(
        prompt: String,
        context: MultimodalContext?,
        config: CloudAiConfig,
        stream: Boolean
    ): Request {
        val action = if (stream) "streamGenerateContent?alt=sse&key=${config.apiKey}" else "generateContent?key=${config.apiKey}"
        val baseUrl = config.baseUrl.trimEnd('/')
        val url = "$baseUrl/v1beta/models/${config.modelName}:$action"

        val root = JSONObject()
        val contents = JSONArray()
        val userContent = JSONObject()
        val parts = JSONArray()

        // Visual frame (Camera snapshot or Screen capture)
        val visual = context?.cameraImage ?: context?.screenCapture?.image
        if (visual != null) {
            val inlineData = JSONObject().apply {
                put("mime_type", visual.mimeType)
                put("data", visual.base64Data)
            }
            parts.put(JSONObject().put("inline_data", inlineData))
        }

        // Audio clip if present
        val audio = context?.audioClip
        if (audio != null) {
            val audioData = JSONObject().apply {
                put("mime_type", audio.mimeType)
                put("data", audio.base64Data)
            }
            parts.put(JSONObject().put("inline_data", audioData))
        }

        // Text prompt (with transcript if available)
        val fullPrompt = if (!context?.audioTranscript.isNullOrBlank()) {
            "$prompt\n[Transcribed Audio: ${context?.audioTranscript}]"
        } else {
            prompt
        }
        parts.put(JSONObject().put("text", fullPrompt))

        userContent.put("role", "user")
        userContent.put("parts", parts)
        contents.put(userContent)
        root.put("contents", contents)

        val generationConfig = JSONObject().apply {
            put("temperature", config.temperature)
            put("maxOutputTokens", config.maxTokens)
        }
        root.put("generationConfig", generationConfig)

        val body = root.toString().toRequestBody(JSON_MEDIA_TYPE)
        return Request.Builder().url(url).post(body).build()
    }

    // ==================== Claude Request / Response ====================

    private fun buildClaudeRequest(
        prompt: String,
        context: MultimodalContext?,
        config: CloudAiConfig,
        stream: Boolean
    ): Request {
        val baseUrl = config.baseUrl.trimEnd('/')
        val url = "$baseUrl/v1/messages"

        val root = JSONObject().apply {
            put("model", config.modelName)
            put("max_tokens", config.maxTokens)
            put("stream", stream)
            put("temperature", config.temperature)
        }

        val messages = JSONArray()
        val messageObj = JSONObject().put("role", "user")
        val contentArray = JSONArray()

        // Visual attachment
        val visual = context?.cameraImage ?: context?.screenCapture?.image
        if (visual != null) {
            val imgSource = JSONObject().apply {
                put("type", "base64")
                put("media_type", visual.mimeType)
                put("data", visual.base64Data)
            }
            contentArray.put(JSONObject().apply {
                put("type", "image")
                put("source", imgSource)
            })
        }

        val fullPrompt = if (!context?.audioTranscript.isNullOrBlank()) {
            "$prompt\n[Transcribed Audio: ${context?.audioTranscript}]"
        } else {
            prompt
        }
        contentArray.put(JSONObject().apply {
            put("type", "text")
            put("text", fullPrompt)
        })

        messageObj.put("content", contentArray)
        messages.put(messageObj)
        root.put("messages", messages)

        val body = root.toString().toRequestBody(JSON_MEDIA_TYPE)
        return Request.Builder()
            .url(url)
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(body)
            .build()
    }

    // ==================== OpenAI Compatible (ChatGPT, xAI, DeepSeek, Kimi, Z-AI, Mistral) ====================

    private fun buildOpenAiCompatibleRequest(
        prompt: String,
        context: MultimodalContext?,
        config: CloudAiConfig,
        stream: Boolean
    ): Request {
        val baseUrl = config.baseUrl.trimEnd('/')
        val url = if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"

        val root = JSONObject().apply {
            put("model", config.modelName)
            put("stream", stream)
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
        }

        val messages = JSONArray()
        val userMsg = JSONObject().put("role", "user")

        val visual = context?.cameraImage ?: context?.screenCapture?.image
        if (visual != null) {
            val contentList = JSONArray()
            val fullPrompt = if (!context?.audioTranscript.isNullOrBlank()) {
                "$prompt\n[Transcribed Audio: ${context?.audioTranscript}]"
            } else {
                prompt
            }
            contentList.put(JSONObject().put("type", "text").put("text", fullPrompt))

            val imgUrlObj = JSONObject().put("url", "data:${visual.mimeType};base64,${visual.base64Data}")
            contentList.put(JSONObject().put("type", "image_url").put("image_url", imgUrlObj))
            userMsg.put("content", contentList)
        } else {
            val fullPrompt = if (!context?.audioTranscript.isNullOrBlank()) {
                "$prompt\n[Transcribed Audio: ${context?.audioTranscript}]"
            } else {
                prompt
            }
            userMsg.put("content", fullPrompt)
        }

        messages.put(userMsg)
        root.put("messages", messages)

        val body = root.toString().toRequestBody(JSON_MEDIA_TYPE)
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(body)
            .build()
    }

    // ==================== Stream Chunk Parsers ====================

    private fun parseStreamChunk(rawLine: String, provider: CloudProvider): String? {
        if (!rawLine.startsWith("data:")) return null
        val data = rawLine.removePrefix("data:").trim()
        if (data == "[DONE]") return null

        return try {
            val json = JSONObject(data)
            when (provider) {
                CloudProvider.GEMINI -> {
                    val candidates = json.optJSONArray("candidates") ?: return null
                    if (candidates.length() == 0) return null
                    val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
                    val parts = content.optJSONArray("parts") ?: return null
                    if (parts.length() == 0) return null
                    parts.getJSONObject(0).optString("text").takeIf { it.isNotEmpty() }
                }
                CloudProvider.CLAUDE -> {
                    val type = json.optString("type")
                    if (type == "content_block_delta") {
                        val delta = json.optJSONObject("delta")
                        delta?.optString("text")?.takeIf { it.isNotEmpty() }
                    } else null
                }
                else -> {
                    // OpenAI, xAI Grok, DeepSeek, Kimi, Z-AI, Mistral
                    val choices = json.optJSONArray("choices") ?: return null
                    if (choices.length() == 0) return null
                    val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return null
                    delta.optString("content").takeIf { it.isNotEmpty() }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseSyncResponse(rawResponse: String, provider: CloudProvider): String {
        return try {
            val json = JSONObject(rawResponse)
            when (provider) {
                CloudProvider.GEMINI -> {
                    val candidates = json.getJSONArray("candidates")
                    val content = candidates.getJSONObject(0).getJSONObject("content")
                    val parts = content.getJSONArray("parts")
                    parts.getJSONObject(0).getString("text")
                }
                CloudProvider.CLAUDE -> {
                    val contentArray = json.getJSONArray("content")
                    contentArray.getJSONObject(0).getString("text")
                }
                else -> {
                    val choices = json.getJSONArray("choices")
                    val message = choices.getJSONObject(0).getJSONObject("message")
                    message.getString("content")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing sync response: $rawResponse", e)
            "Error parsing response: ${e.message}"
        }
    }
}
