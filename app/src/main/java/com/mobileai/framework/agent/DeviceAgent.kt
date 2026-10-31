package com.mobileai.framework.agent

import android.content.Context
import android.util.Log
import com.mobileai.framework.cloud.CloudAiClient
import com.mobileai.framework.cloud.CloudAiConfig
import com.phoneai.localmodel.LocalAIEngine
import kotlinx.coroutines.*

data class AgentStep(
    val stepNumber: Int,
    val thought: String,
    val action: DeviceAction,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Autonomous Device Agent executing multi-step goals on Android.
 * Orchestrates screen observation, reasoning (local or cloud models), and accessibility actions.
 */
class DeviceAgent(
    private val context: Context,
    private val cloudClient: CloudAiClient = CloudAiClient()
) {

    companion object {
        private const val TAG = "DeviceAgent"
        private const val MAX_STEPS = 15
        private const val ACTION_DELAY_MS = 1200L
    }

    interface AgentListener {
        fun onAgentStarted(goal: String)
        fun onStep(step: AgentStep)
        fun onAgentFinished(success: Boolean, summary: String)
        fun onError(message: String)
    }

    private var executionJob: Job? = null
    private var isRunning = false

    fun isAgentRunning(): Boolean = isRunning

    fun cancel() {
        isRunning = false
        executionJob?.cancel()
        executionJob = null
    }

    fun executeGoal(
        goal: String,
        localEngine: LocalAIEngine?,
        cloudConfig: CloudAiConfig?,
        listener: AgentListener
    ) {
        val service = PhoneControllerAccessibilityService.instance
        if (service == null) {
            listener.onError("Accessibility Service is not enabled. Please enable 'Autonomous Phone Controller' in Android Accessibility Settings.")
            return
        }

        if (isRunning) {
            listener.onError("Agent is already executing a task.")
            return
        }

        isRunning = true
        listener.onAgentStarted(goal)

        executionJob = CoroutineScope(Dispatchers.Default).launch {
            val actionHistory = mutableListOf<String>()

            try {
                for (stepIndex in 1..MAX_STEPS) {
                    if (!isRunning || !isActive) break

                    // 1. Observe current screen
                    val elements = service.dumpScreenElements()
                    val elementsSummary = elements.take(30).joinToString("\n") { el ->
                        "- [idx: ${el.index}] \"${el.displayLabel}\" (clickable=${el.isClickable}, editable=${el.isEditable}) at center(${el.centerX}, ${el.centerY})"
                    }

                    // 2. Build Agent System Prompt & State
                    val prompt = buildAgentPrompt(goal, elementsSummary, actionHistory, stepIndex)

                    // 3. Model Reasoning: Cloud AI or Local Engine
                    val modelResponse = if (cloudConfig != null && cloudConfig.apiKey.isNotBlank()) {
                        cloudClient.generateSync(prompt, null, cloudConfig)
                    } else if (localEngine != null && localEngine.isReady()) {
                        localEngine.generate(prompt)
                    } else {
                        withContext(Dispatchers.Main) {
                            listener.onError("No model available (neither Local Engine ready nor Cloud API configured).")
                        }
                        break
                    }

                    // 4. Parse Action
                    val action = DeviceActionParser.parse(modelResponse)
                    val step = AgentStep(
                        stepNumber = stepIndex,
                        thought = action.thought,
                        action = action
                    )

                    withContext(Dispatchers.Main) {
                        listener.onStep(step)
                    }

                    actionHistory.add("Step $stepIndex: ${action.type} -> ${action.thought.take(60)}")

                    // 5. Execute Action
                    when (action.type) {
                        ActionType.CLICK -> {
                            val targetX = (action.x ?: elements.firstOrNull { it.index == action.x }?.centerX ?: 500).toFloat()
                            val targetY = (action.y ?: elements.firstOrNull { it.index == action.x }?.centerY ?: 1000).toFloat()
                            service.performClick(targetX, targetY)
                        }
                        ActionType.TYPE -> {
                            val text = action.text ?: ""
                            service.inputText(text, action.targetElementId)
                        }
                        ActionType.SWIPE -> {
                            val sx = (action.startX ?: 500).toFloat()
                            val sy = (action.startY ?: 1200).toFloat()
                            val ex = (action.endX ?: 500).toFloat()
                            val ey = (action.endY ?: 400).toFloat()
                            service.performSwipe(sx, sy, ex, ey, action.durationMs)
                        }
                        ActionType.SCROLL -> {
                            val sy = if (action.scrollDirection?.equals("UP", ignoreCase = true) == true) 600f else 1200f
                            val ey = if (action.scrollDirection?.equals("UP", ignoreCase = true) == true) 1200f else 600f
                            service.performSwipe(500f, sy, 500f, ey, 350)
                        }
                        ActionType.PRESS_KEY -> {
                            when (action.keyName?.uppercase()) {
                                "HOME" -> service.pressHome()
                                "BACK" -> service.pressBack()
                                "RECENTS" -> service.pressRecents()
                                "NOTIFICATIONS" -> service.pressNotifications()
                                else -> service.pressBack()
                            }
                        }
                        ActionType.LAUNCH_APP -> {
                            val pkg = action.packageName ?: ""
                            if (pkg.isNotEmpty()) {
                                service.launchApp(pkg)
                            }
                        }
                        ActionType.WAIT -> {
                            delay(action.durationMs.coerceAtLeast(500))
                        }
                        ActionType.FINISH -> {
                            isRunning = false
                            withContext(Dispatchers.Main) {
                                listener.onAgentFinished(true, action.summary ?: "Task successfully accomplished.")
                            }
                            return@launch
                        }
                        ActionType.FAIL -> {
                            isRunning = false
                            withContext(Dispatchers.Main) {
                                listener.onAgentFinished(false, action.summary ?: "Agent encountered an unresolvable issue.")
                            }
                            return@launch
                        }
                    }

                    // Delay to allow UI animations / app transitions
                    delay(ACTION_DELAY_MS)
                }

                if (isRunning) {
                    isRunning = false
                    withContext(Dispatchers.Main) {
                        listener.onAgentFinished(false, "Step limit reached ($MAX_STEPS steps).")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Agent execution error", e)
                isRunning = false
                withContext(Dispatchers.Main) {
                    listener.onError("Execution interrupted: ${e.message}")
                }
            }
        }
    }

    private fun buildAgentPrompt(
        goal: String,
        screenElements: String,
        history: List<String>,
        step: Int
    ): String {
        return """
You are MobileAI Autonomous Device Controller. Your task is to operate the Android smartphone to accomplish the user's goal.
Current Step: $step / $MAX_STEPS
User Goal: "$goal"

Previous Actions Taken:
${if (history.isEmpty()) "None yet." else history.joinToString("\n")}

Visible Interactive Elements on Screen:
$screenElements

Respond with ONLY a valid JSON object matching one of these formats:
1. Click element:
{"action": "CLICK", "x": <center_x>, "y": <center_y>, "thought": "<reasoning>"}
2. Type into field:
{"action": "TYPE", "text": "<text_to_type>", "thought": "<reasoning>"}
3. Scroll:
{"action": "SCROLL", "direction": "UP|DOWN|LEFT|RIGHT", "thought": "<reasoning>"}
4. Press navigation key:
{"action": "PRESS_KEY", "key": "BACK|HOME|RECENTS", "thought": "<reasoning>"}
5. Launch app:
{"action": "LAUNCH_APP", "packageName": "<com.example.app>", "thought": "<reasoning>"}
6. Task completed:
{"action": "FINISH", "summary": "<description of result>", "thought": "<goal satisfied>"}

JSON:
""".trimIndent()
    }
}

