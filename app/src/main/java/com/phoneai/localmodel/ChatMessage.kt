package com.phoneai.localmodel

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    var text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    var isGenerating: Boolean = false,
    var statsInfo: String? = null,
    // ── Streaming telemetry (updated on each chunk) ───────────────────────────
    var streamTokPerSec: Float = 0f,
    var streamTokenCount: Int = 0,
    // ── Multimodal & Agent extensions ─────────────────────────────────────────
    var hasCameraImage: Boolean = false,
    var hasScreenCapture: Boolean = false,
    var hasAudioClip: Boolean = false,
    var isAgentAction: Boolean = false,
    var agentThought: String? = null
)
