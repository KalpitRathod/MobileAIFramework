package com.mobileai.framework.cloud

import android.util.Base64
import android.util.Log
import com.mobileai.framework.agent.skills.SkillLoader
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Bidirectional streaming client for Google Gemini Live API over WebSockets.
 * Streams microphone audio chunks and camera snapshots directly to Gemini,
 * and receives real-time audio/text responses and tool calls.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val skillLoader: SkillLoader? = null
) {

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val LIVE_WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    interface LiveSessionListener {
        fun onConnected()
        fun onTextChunk(text: String)
        fun onAudioData(pcmBytes: ByteArray)
        fun onToolCall(callId: String, functionName: String, arguments: JSONObject)
        fun onError(message: String)
        fun onClosed()
    }

    private var sessionListener: LiveSessionListener? = null
    var isConnected: Boolean = false
        private set

    /**
     * Connects to Gemini Live WebSocket and sends session configuration.
     */
    fun startSession(listener: LiveSessionListener) {
        sessionListener = listener

        val url = "$LIVE_WS_URL?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                Log.i(TAG, "Gemini Live WebSocket connected")
                sendInitialSetup(ws)
                listener.onConnected()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                Log.e(TAG, "Gemini Live WebSocket failure", t)
                listener.onError("Gemini Live connection error: ${t.localizedMessage ?: t.message}")
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                Log.i(TAG, "Gemini Live closing: $code / $reason")
                listener.onClosed()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                listener.onClosed()
            }
        })
    }

    private fun sendInitialSetup(ws: WebSocket) {
        val skills = skillLoader?.getAllSkills() ?: emptyList()
        val setupJson = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/gemini-2.0-flash-exp")
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply {
                        put("AUDIO")
                        put("TEXT")
                    })
                })

                // Include progressive on-device skills as callable tools
                val toolsJson = JSONArray()
                for (skill in skills) {
                    for (tool in skill.tools) {
                        toolsJson.put(tool.toGeminiToolDeclaration())
                    }
                }
                if (toolsJson.length() > 0) {
                    put("tools", JSONArray().apply {
                        put(JSONObject().apply {
                            put("functionDeclarations", toolsJson)
                        })
                    })
                }
            })
        }

        ws.send(setupJson.toString())
        Log.d(TAG, "Sent setup packet with ${skills.size} registered agent skills")
    }

    /**
     * Streams real-time 16kHz PCM audio chunk to Gemini Live.
     */
    fun sendAudioChunk(pcmChunk: ByteArray) {
        if (!isConnected || webSocket == null) return

        val base64Pcm = Base64.encodeToString(pcmChunk, Base64.NO_WRAP)
        val packet = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Pcm)
                    })
                })
            })
        }
        webSocket?.send(packet.toString())
    }

    /**
     * Streams JPEG image snapshot from camera/viewfinder to Gemini Live.
     */
    fun sendCameraFrame(jpegBytes: ByteArray) {
        if (!isConnected || webSocket == null) return

        val base64Jpeg = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val packet = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("mediaChunks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", base64Jpeg)
                    })
                })
            })
        }
        webSocket?.send(packet.toString())
    }

    /**
     * Returns tool execution response back to Gemini Live.
     */
    fun sendToolResponse(callId: String, functionName: String, resultData: JSONObject) {
        val packet = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", callId)
                        put("name", functionName)
                        put("response", resultData)
                    })
                })
            })
        }
        webSocket?.send(packet.toString())
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val root = JSONObject(text)

            // 1. Check for Server Content (text/audio)
            val serverContent = root.optJSONObject("serverContent")
            val modelTurn = serverContent?.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")

            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    // Text chunk
                    val txt = part.optString("text")
                    if (txt.isNotBlank()) {
                        sessionListener?.onTextChunk(txt)
                    }

                    // Inline audio data
                    val inlineData = part.optJSONObject("inlineData")
                    if (inlineData != null) {
                        val base64Data = inlineData.optString("data")
                        if (base64Data.isNotBlank()) {
                            val pcm = Base64.decode(base64Data, Base64.DEFAULT)
                            sessionListener?.onAudioData(pcm)
                        }
                    }

                    // Function / Tool Call
                    val funcCall = part.optJSONObject("functionCall")
                    if (funcCall != null) {
                        val name = funcCall.optString("name")
                        val callId = funcCall.optString("id", name)
                        val args = funcCall.optJSONObject("args") ?: JSONObject()
                        Log.i(TAG, "Gemini Live requested tool call: $name($args)")
                        sessionListener?.onToolCall(callId, name, args)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Gemini Live message: ${e.message}", e)
        }
    }

    fun close() {
        try {
            webSocket?.close(1000, "Client session closed")
        } catch (_: Exception) {}
        webSocket = null
        isConnected = false
    }
}
