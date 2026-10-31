package com.mobileai.framework.agent

import org.json.JSONObject
import java.util.regex.Pattern

enum class ActionType {
    CLICK,
    TYPE,
    SWIPE,
    SCROLL,
    PRESS_KEY,
    LAUNCH_APP,
    WAIT,
    FINISH,
    FAIL
}

data class DeviceAction(
    val type: ActionType,
    val x: Int? = null,
    val y: Int? = null,
    val text: String? = null,
    val targetElementId: String? = null,
    val startX: Int? = null,
    val startY: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null,
    val scrollDirection: String? = null, // "UP", "DOWN", "LEFT", "RIGHT"
    val keyName: String? = null, // "BACK", "HOME", "RECENTS"
    val packageName: String? = null,
    val durationMs: Long = 500,
    val thought: String = "",
    val summary: String? = null
)

/**
 * Robust JSON parser extracting structured device actions from LLM responses.
 * Handles markdown formatting, thoughts, and partial outputs.
 */
object DeviceActionParser {

    private val JSON_BLOCK_PATTERN = Pattern.compile("```(?:json)?\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL)
    private val THINK_TAG_PATTERN = Pattern.compile("<think>(.*?)</think>", Pattern.DOTALL)

    fun parse(modelOutput: String): DeviceAction {
        var cleanOutput = modelOutput.trim()
        var extractedThought = ""

        // Extract reasoning from <think> tags if present (e.g. DeepSeek-R1)
        val thinkMatcher = THINK_TAG_PATTERN.matcher(cleanOutput)
        if (thinkMatcher.find()) {
            extractedThought = thinkMatcher.group(1)?.trim() ?: ""
            cleanOutput = thinkMatcher.replaceAll("").trim()
        }

        // Try extracting JSON from markdown code block
        val jsonMatcher = JSON_BLOCK_PATTERN.matcher(cleanOutput)
        val jsonStr = if (jsonMatcher.find()) {
            jsonMatcher.group(1)
        } else {
            // Find first '{' and last '}'
            val firstBrace = cleanOutput.indexOf('{')
            val lastBrace = cleanOutput.lastIndexOf('}')
            if (firstBrace != -1 && lastBrace > firstBrace) {
                cleanOutput.substring(firstBrace, lastBrace + 1)
            } else {
                null
            }
        }

        if (jsonStr.isNullOrBlank()) {
            // Check if model emitted plain text finish
            return if (cleanOutput.contains("done", ignoreCase = true) || cleanOutput.contains("finish", ignoreCase = true)) {
                DeviceAction(ActionType.FINISH, summary = cleanOutput, thought = extractedThought)
            } else {
                DeviceAction(ActionType.FAIL, summary = "Could not parse action JSON from model output: $cleanOutput", thought = extractedThought)
            }
        }

        return try {
            val json = JSONObject(jsonStr)
            val actionStr = json.optString("action", "FAIL").uppercase()
            val thought = json.optString("thought", extractedThought)

            val actionType = try {
                ActionType.valueOf(actionStr)
            } catch (e: Exception) {
                when {
                    actionStr.contains("CLICK") || actionStr.contains("TAP") -> ActionType.CLICK
                    actionStr.contains("TYPE") || actionStr.contains("INPUT") -> ActionType.TYPE
                    actionStr.contains("SWIPE") -> ActionType.SWIPE
                    actionStr.contains("SCROLL") -> ActionType.SCROLL
                    actionStr.contains("KEY") || actionStr.contains("PRESS") -> ActionType.PRESS_KEY
                    actionStr.contains("LAUNCH") || actionStr.contains("OPEN") -> ActionType.LAUNCH_APP
                    actionStr.contains("WAIT") -> ActionType.WAIT
                    actionStr.contains("FINISH") || actionStr.contains("DONE") -> ActionType.FINISH
                    else -> ActionType.FAIL
                }
            }

            DeviceAction(
                type = actionType,
                x = if (json.has("x")) json.getInt("x") else null,
                y = if (json.has("y")) json.getInt("y") else null,
                text = if (json.has("text") && !json.isNull("text")) json.getString("text") else null,
                targetElementId = if (json.has("target") && !json.isNull("target")) json.getString("target") else null,
                startX = if (json.has("startX")) json.getInt("startX") else null,
                startY = if (json.has("startY")) json.getInt("startY") else null,
                endX = if (json.has("endX")) json.getInt("endX") else null,
                endY = if (json.has("endY")) json.getInt("endY") else null,
                scrollDirection = json.optString("direction", "DOWN"),
                keyName = if (json.has("key") && !json.isNull("key")) json.getString("key") else null,
                packageName = if (json.has("packageName") && !json.isNull("packageName")) json.getString("packageName") else null,
                durationMs = json.optLong("durationMs", 500),
                thought = thought,
                summary = if (json.has("summary") && !json.isNull("summary")) json.getString("summary") else null
            )
        } catch (e: Exception) {
            DeviceAction(
                type = ActionType.FAIL,
                thought = extractedThought,
                summary = "JSON parse exception: ${e.message}"
            )
        }
    }
}

