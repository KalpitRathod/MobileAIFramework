package com.mobileai.framework.router

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import com.mobileai.framework.hardware.ThermalGovernor
import com.mobileai.framework.hardware.ThermalLevel
import com.mobileai.framework.multimodal.MultimodalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * Route targets for hybrid execution.
 */
enum class RouteTarget {
    EDGE_LOCAL,   // On-device llama.cpp / MediaPipe (100% offline, zero cloud tokens)
    CLOUD_RUN,    // FastAPI Cloud Run Hybrid Gateway / Google Gemini 1.5
    GEMINI_LIVE   // WebSocket bidirectional multimodal streaming
}

/**
 * Snapshot of real-time device health & connectivity metrics.
 */
data class DeviceStateProfile(
    val isOnline: Boolean,
    val isWifi: Boolean,
    val batteryPct: Int,
    val isCharging: Boolean,
    val thermalStatus: ThermalLevel,
    val availableRamMb: Long,
    val deviceModel: String = "MobileDevice",
    val osVersion: String = "Android 15"
) {
    companion object {
        fun capture(context: Context?, thermalGovernor: ThermalGovernor? = null): DeviceStateProfile {
            if (context == null) {
                return DeviceStateProfile(
                    isOnline = false,
                    isWifi = false,
                    batteryPct = 100,
                    isCharging = true,
                    thermalStatus = ThermalLevel.NOMINAL,
                    availableRamMb = 3000
                )
            }
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val isOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            val isCharging = bm?.isCharging ?: false

            val mi = android.app.ActivityManager.MemoryInfo()
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.getMemoryInfo(mi)
            val availableRamMb = mi.availMem / (1024 * 1024)

            val thermal = thermalGovernor?.currentThermalLevel ?: ThermalLevel.NOMINAL

            return DeviceStateProfile(
                isOnline = isOnline,
                isWifi = isWifi,
                batteryPct = batteryPct,
                isCharging = isCharging,
                thermalStatus = thermal,
                availableRamMb = availableRamMb
            )
        }
    }
}

/**
 * Structured telemetry and tokenomics output for every routing event.
 */
data class RoutingDecision(
    val target: RouteTarget,
    val reason: String,
    val recommendedModel: String,
    val estimatedTokens: Int,
    val tokensSaved: Int,
    val dollarCostSavedUsd: Double,
    val complexityScore: Int,
    val isOffline: Boolean
) {
    val telemetrySummary: String
        get() = when (target) {
            RouteTarget.EDGE_LOCAL -> "⚡ Edge Local • Saved ${tokensSaved} tokens ($${String.format("%.4f", dollarCostSavedUsd)})"
            RouteTarget.CLOUD_RUN -> "☁️ Cloud Run • $reason"
            RouteTarget.GEMINI_LIVE -> "🎙️ Gemini Live • WebSocket Stream"
        }
}

/**
 * Autonomous Hybrid Router:
 * Dynamically balances inference between edge on-device GGUF/MediaPipe models and
 * Google Cloud Run / Gemini 1.5, maximizing token savings and offline resilience.
 */
class HybridRouter(
    private val context: Context? = null,
    private val thermalGovernor: ThermalGovernor? = null,
    var cloudGatewayUrl: String = "https://mobileai-hybrid-gateway-646100637693.us-central1.run.app"
) {

    companion object {
        private const val TAG = "HybridRouter"
        private const val COST_PER_MILLION_TOKENS_USD = 0.15 // Standard baseline for Gemini Flash
        private const val COMPLEXITY_THRESHOLD = 45
    }

    /**
     * Determines optimal execution target based on prompt complexity,
     * multimodal context, battery, RAM, and thermal state.
     */
    fun route(
        prompt: String,
        multimodalContext: MultimodalContext? = null,
        forcedState: DeviceStateProfile? = null
    ): RoutingDecision {
        val state = forcedState ?: DeviceStateProfile.capture(context, thermalGovernor)
        val cleanPrompt = prompt.trim()
        val words = cleanPrompt.split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
        val estimatedTokens = max(1, (words * 1.33).toInt())

        // ── RULE 1: Offline / Airplane Mode Guarantee ───────────────────────
        if (!state.isOnline) {
            val tokensSaved = estimatedTokens
            val dollarsSaved = (tokensSaved / 1_000_000.0) * COST_PER_MILLION_TOKENS_USD
            return RoutingDecision(
                target = RouteTarget.EDGE_LOCAL,
                reason = "Device is offline / in Airplane Mode (100% on-device execution)",
                recommendedModel = "gemma-2-2b-it-q4_k_m.gguf",
                estimatedTokens = estimatedTokens,
                tokensSaved = tokensSaved,
                dollarCostSavedUsd = dollarsSaved,
                complexityScore = 10,
                isOffline = true
            )
        }

        // ── RULE 2: Real-time Live Multimodal Stream ────────────────────────
        if (multimodalContext?.screenCapture != null ||
            (multimodalContext?.cameraImage != null && cleanPrompt.contains("live", ignoreCase = true))) {
            return RoutingDecision(
                target = RouteTarget.GEMINI_LIVE,
                reason = "Continuous multimodal vision streaming requires Gemini Live WebSocket",
                recommendedModel = "gemini-2.0-flash-realtime",
                estimatedTokens = estimatedTokens + 256,
                tokensSaved = 0,
                dollarCostSavedUsd = 0.0,
                complexityScore = 90,
                isOffline = false
            )
        }

        // ── RULE 3: Thermal Throttle Mitigation ─────────────────────────────
        if (state.thermalStatus == ThermalLevel.SEVERE ||
            state.thermalStatus == ThermalLevel.CRITICAL) {
            return RoutingDecision(
                target = RouteTarget.CLOUD_RUN,
                reason = "Device SoC thermal throttling (${state.thermalStatus}). Escalating to Cloud to protect hardware.",
                recommendedModel = "gemini-1.5-flash",
                estimatedTokens = estimatedTokens,
                tokensSaved = 0,
                dollarCostSavedUsd = 0.0,
                complexityScore = 80,
                isOffline = false
            )
        }

        // ── RULE 3B: Low RAM / Android LMK Protection ───────────────────────
        if (state.availableRamMb in 1..899) {
            return RoutingDecision(
                target = RouteTarget.CLOUD_RUN,
                reason = "MemoryBudgetManager low RAM guard (${state.availableRamMb}MB free). Offloading to Cloud Run to prevent LMK crash.",
                recommendedModel = "gemini-flash-latest",
                estimatedTokens = estimatedTokens,
                tokensSaved = 0,
                dollarCostSavedUsd = 0.0,
                complexityScore = 76,
                isOffline = false
            )
        }

        // ── RULE 4: Low Battery Preservation ────────────────────────────────
        if (state.batteryPct < 15 && !state.isCharging && state.isWifi) {
            return RoutingDecision(
                target = RouteTarget.CLOUD_RUN,
                reason = "Low battery (${state.batteryPct}%) on Wi-Fi. Offloading heavy neural computation.",
                recommendedModel = "gemini-1.5-flash",
                estimatedTokens = estimatedTokens,
                tokensSaved = 0,
                dollarCostSavedUsd = 0.0,
                complexityScore = 70,
                isOffline = false
            )
        }

        // ── RULE 5: Prompt Complexity Scoring (0..100) ──────────────────────
        val complexityScore = evaluateComplexity(cleanPrompt)

        return if (complexityScore < COMPLEXITY_THRESHOLD) {
            val tokensSaved = estimatedTokens
            val dollarsSaved = (tokensSaved / 1_000_000.0) * COST_PER_MILLION_TOKENS_USD
            RoutingDecision(
                target = RouteTarget.EDGE_LOCAL,
                reason = "Low complexity/local task ($complexityScore/100). Executed on-device to save cloud tokens.",
                recommendedModel = "gemma-2-2b-it-q4_k_m.gguf",
                estimatedTokens = estimatedTokens,
                tokensSaved = tokensSaved,
                dollarCostSavedUsd = dollarsSaved,
                complexityScore = complexityScore,
                isOffline = false
            )
        } else {
            RoutingDecision(
                target = RouteTarget.CLOUD_RUN,
                reason = "Complex/online task ($complexityScore/100). Escalated to Google Gemini API.",
                recommendedModel = "gemini-flash-lite-latest",
                estimatedTokens = estimatedTokens,
                tokensSaved = 0,
                dollarCostSavedUsd = 0.0,
                complexityScore = complexityScore,
                isOffline = false
            )
        }
    }

    /**
     * Scores prompt complexity from 0 (simple/local/private) to 100 (heavy coding/reasoning/live world info).
     * Threshold = 45: < 45 runs on-device ($0), >= 45 escalates to Cloud Gemini API.
     */
    fun evaluateComplexity(prompt: String): Int {
        var score = 0
        val p = prompt.lowercase().trim()
        val words = p.split("\\s+".toRegex()).filter { it.isNotEmpty() }.size

        // Length weighting: long multi-sentence prompts need stronger cloud reasoning
        if (words > 60) score += 50
        else if (words > 35) score += 45
        else if (words > 20) score += 20

        // 1. Coding, engineering & debugging -> always escalate when online (+50)
        val codeKeywords = listOf(
            "code", "python", "kotlin", "java", "javascript", "typescript", "c++", "sql",
            "debug", "stacktrace", "exception", "refactor", "algorithm", "function",
            "class ", "async", "coroutine", "script", "program", "api", "regex", "bug", "compile"
        )
        if (codeKeywords.any { p.contains(it) }) score += 50

        // 2. Deep reasoning, comparisons, architecture & math -> escalate (+48)
        val reasoningKeywords = listOf(
            "derive", "prove", "explain why", "step by step", "complex", "synthesize",
            "analyze", "compare", "contrast", "pros and cons", "difference between",
            "architecture", "design a", "strategy", "in detail", "detailed", "calculate",
            "optimize", "root cause", "benchmark", "evaluate", "research", "write an essay", "report"
        )
        if (reasoningKeywords.any { p.contains(it) }) score += 48

        // 3. Live / current world knowledge -> tiny offline SLMs hallucinate these, so escalate (+50)
        val worldLiveKeywords = listOf(
            "today", "latest", "news", "weather", "current", "stock", "price of",
            "2025", "2026", "who is", "who won", "capital of", "population", "history of", "google"
        )
        if (worldLiveKeywords.any { p.contains(it) }) score += 50

        // 4. Local / private / lightweight tasks -> keep on-device ($0 cloud spend)
        val localSimpleKeywords = listOf(
            "hi", "hello", "hey", "good morning", "good evening", "thanks", "thank you",
            "who are you", "read text", "extract text", "ocr", "scan", "my note", "my notes",
            "summarize this note", "summarize my"
        )
        if (localSimpleKeywords.any { p.startsWith(it) || p.contains("my note") || p.contains("extract text") }) {
            score = max(5, score - 35)
        }

        return score.coerceIn(0, 100)
    }

    /**
     * Asynchronously streams routing decision telemetry to Cloud Run / BigQuery.
     */
    suspend fun streamTelemetryToCloud(
        decision: RoutingDecision,
        deviceModel: String = android.os.Build.MODEL,
        skillUsed: String = "tokenomics-router",
        latencyMs: Int = 45
    ) = withContext(Dispatchers.IO) {
        try {
            if (cloudGatewayUrl.contains("placeholder")) return@withContext

            val state = DeviceStateProfile.capture(context, thermalGovernor)
            val endpoint = "$cloudGatewayUrl/v1/telemetry/log"
            val json = JSONObject().apply {
                put("device_id", android.os.Build.ID)
                put("device_model", deviceModel)
                put("os_version", "Android ${android.os.Build.VERSION.RELEASE}")
                put("chipset", android.os.Build.HARDWARE)
                put("route_target", decision.target.name)
                put("routing_reason", decision.reason)
                put("model_name", decision.recommendedModel)
                put("estimated_tokens", decision.estimatedTokens)
                put("tokens_saved", decision.tokensSaved)
                put("dollar_cost_saved_usd", decision.dollarCostSavedUsd)
                put("latency_ms", latencyMs)
                put("thermal_state", state.thermalStatus.name)
                put("battery_level", state.batteryPct)
                put("is_offline", decision.isOffline)
                put("skill_used", skillUsed)
            }

            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.outputStream.use { it.write(json.toString().toByteArray()) }

            val responseCode = conn.responseCode
            Log.d(TAG, "Telemetry stream to $endpoint responded: $responseCode")
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Telemetry logging skipped (offline or gateway unreachable): ${e.message}")
        }
    }
}
