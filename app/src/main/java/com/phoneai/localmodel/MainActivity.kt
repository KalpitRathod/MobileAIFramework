package com.phoneai.localmodel

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mobileai.framework.agent.AgentStep
import com.mobileai.framework.agent.DeviceAgent
import com.mobileai.framework.agent.PhoneControllerAccessibilityService
import com.mobileai.framework.cloud.CloudAiClient
import com.mobileai.framework.cloud.CloudApiKeyStore
import com.mobileai.framework.cloud.CloudProvider
import com.mobileai.framework.multimodal.*
import com.phoneai.localmodel.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dbHelper: MobileAiDatabaseHelper
    private lateinit var aiEngine: LocalAIEngine

    // Adapters
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var agentStepAdapter: AgentStepAdapter

    private val messagesList = mutableListOf<ChatMessage>()
    private val agentStepsList = mutableListOf<AgentStep>()
    private var currentInferenceJob: Job? = null
    private var isGenerating = false

    // Multimodal & Sensory State
    private var pendingMultimodalContext: MultimodalContext? = null
    private var latestCameraBitmap: Bitmap? = null
    private var latestScreenBitmap: Bitmap? = null
    private var isCameraActive = false
    private var isScreenStreamActive = false

    // State
    private var currentTab = Tab.CHAT
    private var currentLensMode = LensMode.DOC_TABLE
    private var selectedModelFile: File? = null

    enum class Tab { CHAT, VISUAL, AGENT, HARDWARE }
    enum class LensMode { DOC_TABLE, HANDWRITING, QC_INSPECT, ASK_AI }

    // File picker contract for importing GGUF models
    private val modelPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            importModelFromUri(uri)
        }
    }

    // Image picker contract for selecting photos/screenshots from gallery
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        latestCameraBitmap = bitmap
                        val payload = ImagePayload.fromBitmap(bitmap, 90)
                        pendingMultimodalContext = (pendingMultimodalContext ?: MultimodalContext()).copy(cameraImage = payload)

                        if (currentTab == Tab.VISUAL) {
                            runHybridVisionOcr(bitmap, payload)
                        }

                        showAttachmentBanner("📷 Image attached (${bitmap.width}x${bitmap.height})")
                        if (binding.etBuddyInput.text.isNullOrBlank()) {
                            binding.etBuddyInput.setText("Convert to text")
                        }
                    } else {
                        Toast.makeText(this, "Could not decode selected image", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error reading image: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            aiEngine.mobileAI.screenManager.startScreenStream(
                result.resultCode,
                result.data!!,
                object : ScreenCaptureManager.ScreenCaptureListener {
                    override fun onScreenFrame(bitmap: Bitmap, screenPayload: ScreenPayload) {
                        latestScreenBitmap = bitmap
                        binding.ivScreenFeedPreview.setImageBitmap(bitmap)
                    }

                    override fun onCaptureStarted() {
                        isScreenStreamActive = true
                        binding.btnSensoryScreenStream.text = "Stop Screen Stream"
                        Toast.makeText(this@MainActivity, "Live screen stream active", Toast.LENGTH_SHORT).show()
                    }

                    override fun onCaptureStopped() {
                        isScreenStreamActive = false
                        binding.btnSensoryScreenStream.text = "Start Screen Stream"
                    }

                    override fun onError(message: String) {
                        Toast.makeText(this@MainActivity, "Screen stream error: $message", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dbHelper = MobileAiDatabaseHelper(this)
        aiEngine = LocalAIEngine(applicationContext)

        setupNavigation()
        setupChat()
        setupSensory()
        setupAgent()
        setupHardware()
        setupModelControls()

        checkAndInitializeModel()
        updateModelSubtitle()
    }

    // ==========================================
    // NAVIGATION & TAB SWITCHING (Chat & Visual Lens)
    // ==========================================
    private fun setupNavigation() {
        binding.tabBuddy.setOnClickListener { switchTab(Tab.CHAT) }
        binding.tabVisual.setOnClickListener { switchTab(Tab.VISUAL) }
        binding.btnHardwareInfo.setOnClickListener { switchTab(Tab.HARDWARE) }
        binding.btnCloudHub.setOnClickListener { showCloudHubDialog() }
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab

        // Toggle Views (Immersive full-viewport Lens when Visual tab is active!)
        val isVisualTab = (tab == Tab.VISUAL)
        binding.topBar.visibility = if (isVisualTab) View.GONE else View.VISIBLE
        binding.topBarDivider.visibility = if (isVisualTab) View.GONE else View.VISIBLE
        binding.viewBuddy.visibility = if (tab == Tab.CHAT) View.VISIBLE else View.GONE
        binding.viewVisual.visibility = if (isVisualTab) View.VISIBLE else View.GONE
        binding.viewAgent.visibility = if (tab == Tab.AGENT) View.VISIBLE else View.GONE
        binding.viewHardware.visibility = if (tab == Tab.HARDWARE) View.VISIBLE else View.GONE

        // Google Brand Colors for Tabs and Selections
        val blue = ContextCompat.getColor(this, R.color.google_blue)
        val red = ContextCompat.getColor(this, R.color.google_red)
        val muted = ContextCompat.getColor(this, R.color.text_secondary)

        // Tab 1: Chat (Google Blue)
        val isChat = (tab == Tab.CHAT)
        binding.ivTabBuddy.setColorFilter(if (isChat) blue else muted)
        binding.tvTabBuddy.setTextColor(if (isChat) blue else muted)
        binding.tvTabBuddy.paint.isFakeBoldText = isChat
        binding.tabBuddyPill.setBackgroundResource(if (isChat) R.drawable.bg_tab_pill_blue else 0)

        // Tab 2: Visual Lens (Google Red)
        val isVisual = (tab == Tab.VISUAL)
        binding.ivTabVisual.setColorFilter(if (isVisual) red else muted)
        binding.tvTabVisual.setTextColor(if (isVisual) red else muted)
        binding.tvTabVisual.paint.isFakeBoldText = isVisual
        binding.tabVisualPill.setBackgroundResource(if (isVisual) R.drawable.bg_tab_pill_red else 0)

        // Refresh data on tab switch
        when (tab) {
            Tab.HARDWARE -> refreshHardwareDiagnostics()
            Tab.AGENT -> updateAccessibilityStatus()
            Tab.VISUAL -> {
                checkPermissions()
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && !isCameraActive) {
                    startCameraFeed()
                }
            }
            Tab.CHAT -> { /* Keep chat as is */ }
        }

        // Pause camera if navigating away from Visual
        if (tab != Tab.VISUAL && isCameraActive) {
            stopCameraFeed()
        }
    }

    // ==========================================
    // 1. CHAT: STREAMING & MULTIMODAL INFERENCE
    // ==========================================
    private fun setupChat() {
        val history = dbHelper.getChatHistory()
        messagesList.clear()
        messagesList.addAll(history)

        chatAdapter = ChatAdapter(messagesList)
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvBuddyMessages.layoutManager = layoutManager
        binding.rvBuddyMessages.adapter = chatAdapter
        // Eliminate flicker & vertical bounce during rapid token-by-token streaming
        binding.rvBuddyMessages.itemAnimator = null

        binding.fabSendBuddy.setOnClickListener {
            if (isGenerating) stopGeneration() else sendChatMessage()
        }

        binding.etBuddyInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendChatMessage()
                true
            } else {
                false
            }
        }

        binding.btnClearChatHistory.setOnClickListener {
            dbHelper.clearChatHistory()
            chatAdapter.clearMessages()
            Toast.makeText(this, "Chat history cleared", Toast.LENGTH_SHORT).show()
        }

        // Multimodal sensory buttons
        binding.btnChatCamera.setOnClickListener {
            val options = arrayOf("📷 Capture Snapshot (Camera)", "🖼️ Pick Image from Gallery")
            MaterialAlertDialogBuilder(this)
                .setTitle("Attach Image / Vision OCR")
                .setItems(options) { _, which ->
                    if (which == 0) {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                        } else {
                            switchTab(Tab.VISUAL)
                        }
                    } else {
                        imagePickerLauncher.launch("image/*")
                    }
                }
                .show()
        }

        binding.btnRemoveAttachment.setOnClickListener {
            pendingMultimodalContext = null
            binding.layoutAttachmentPreview.visibility = View.GONE
        }

        // Prompt Chips
        val promptChips = listOf(
            Pair(binding.chipPrompt1, "Benchmark on-device inference tok/s and matrix compute latency."),
            Pair(binding.chipPrompt2, "Explain why CPU Big-Core thread pinning prevents straggler stalls on mobile."),
            Pair(binding.chipPrompt3, "Analyze RAM headroom and KV-Cache budget for budget phones."),
            Pair(binding.chipPrompt4, "How does the Autonomous Phone Controller agent automate Android UI actions?")
        )

        for ((chip, promptText) in promptChips) {
            chip.setOnClickListener {
                binding.etBuddyInput.setText(promptText)
                binding.etBuddyInput.setSelection(promptText.length)
                sendChatMessage()
            }
        }

        binding.btnSelectChatModelPrompt.setOnClickListener {
            showModelSelectionDialog(ModelRole.CHAT)
        }
    }

    /**
     * Smoothly pins the chat list to the bottom without RecyclerView smoothScrollToPosition bounce/jitter.
     * If force=false (during token streaming), only auto-scrolls when already near the bottom so the user
     * can freely scroll up to read earlier messages without being yanked down.
     */
    private fun scrollChatToBottom(force: Boolean = false) {
        val count = chatAdapter.itemCount
        if (count <= 0) return
        val rv = binding.rvBuddyMessages
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val lastVisible = lm.findLastVisibleItemPosition()
        val isNearBottom = !rv.canScrollVertically(1) || lastVisible >= count - 2
        if (force || isNearBottom) {
            rv.post {
                lm.scrollToPositionWithOffset(count - 1, -100000)
            }
        }
    }

    private fun showAttachmentBanner(text: String) {
        binding.layoutAttachmentPreview.visibility = View.VISIBLE
        binding.tvAttachmentSummary.text = text
    }

    private fun sendChatMessage() {
        if (currentInferenceJob?.isActive == true) {
            Toast.makeText(this, "⏳ Still generating previous response — wait a second or tap Stop.", Toast.LENGTH_SHORT).show()
            return
        }

        val prompt = binding.etBuddyInput.text?.toString()?.trim() ?: ""
        if (prompt.isEmpty() && pendingMultimodalContext == null) return

        val contextPayload = pendingMultimodalContext
        pendingMultimodalContext = null
        binding.layoutAttachmentPreview.visibility = View.GONE

        val cloudStore = aiEngine.mobileAI.cloudApiKeyStore
        val hasActiveCloudKey = cloudStore.getApiKey(cloudStore.getActiveProvider()).isNotBlank()
        val isMultimodalVisual = contextPayload?.cameraImage != null || contextPayload?.screenCapture != null
        val effectivePrompt = if (prompt.isBlank() && isMultimodalVisual) "Convert to text" else prompt

        // On-device anti-hallucination guardrail: reject gibberish / keyboard-mash before LLM or telemetry
        if (!isMultimodalVisual && contextPayload?.audioClip == null) {
            val validation = com.mobileai.framework.router.InputValidator.validateGeneralPrompt(effectivePrompt)
            if (!validation.isValid) {
                Toast.makeText(this, "🚫 ${validation.reason}", Toast.LENGTH_LONG).show()
                return
            }
        }

        // Autonomous Hybrid Router: Evaluates prompt complexity, thermal state, and offline connectivity
        val routingDecision = aiEngine.mobileAI.hybridRouter.route(
            prompt = effectivePrompt,
            multimodalContext = contextPayload
        )

        // Progressive-Disclosure Skill Loader: resolve matched skill compactly
        val matchedSkill = aiEngine.mobileAI.skillLoader.selectSkillsForPrompt(effectivePrompt).firstOrNull()?.id
            ?: if (isMultimodalVisual) "vision-ocr" else "tokenomics-router"

        // Asynchronously stream routing event & tokenomics metrics to Cloud Run / BigQuery
        lifecycleScope.launch(Dispatchers.IO) {
            aiEngine.mobileAI.hybridRouter.streamTelemetryToCloud(
                decision = routingDecision,
                skillUsed = matchedSkill
            )
        }

        val isOnline = com.mobileai.framework.router.DeviceStateProfile.capture(this, aiEngine.mobileAI.thermalGovernor).isOnline
        val useCloud = (isMultimodalVisual && isOnline && hasActiveCloudKey) ||
                (routingDecision.target == com.mobileai.framework.router.RouteTarget.CLOUD_RUN && hasActiveCloudKey) ||
                cloudStore.isCloudPreferred() ||
                (!aiEngine.isInitialized && hasActiveCloudKey && !isMultimodalVisual)

        if (!useCloud && !aiEngine.isInitialized && !isMultimodalVisual) {
            showModelRequiredDialog(ModelRole.CHAT)
            return
        }

        val userDisplay = if (contextPayload?.isMultimodal == true) {
            val tag = when {
                contextPayload.cameraImage != null -> "📷 [Photo] "
                contextPayload.screenCapture != null -> "📱 [Screen] "
                contextPayload.audioClip != null -> "🎙️ [Audio] "
                else -> ""
            }
            "$tag$effectivePrompt"
        } else effectivePrompt

        val userMsg = ChatMessage(text = userDisplay, isUser = true)
        chatAdapter.addMessage(userMsg)
        dbHelper.insertChatMessage(userMsg)
        binding.etBuddyInput.setText("")
        scrollChatToBottom(force = true)

        val botMsg = ChatMessage(text = "", isUser = false, isGenerating = true)
        chatAdapter.addMessage(botMsg)
        scrollChatToBottom(force = true)

        setGeneratingState(true)

        currentInferenceJob = lifecycleScope.launch {
            if (useCloud) {
                val config = cloudStore.getConfig()
                val cloudPrompt = if (isMultimodalVisual && (
                        effectivePrompt.equals("Convert to text", ignoreCase = true) ||
                        effectivePrompt.contains("extract text", ignoreCase = true) ||
                        effectivePrompt.contains("read text", ignoreCase = true)
                    )) {
                    "Transcribe ALL text in this image verbatim — including messy, dirty, cursive, or slanted handwriting and clean printed text, numbers, equations, and symbols (±, Ø, mm). Preserve line breaks cleanly. Do not summarize or invent text."
                } else {
                    effectivePrompt
                }
                val streamFlow = aiEngine.mobileAI.cloudClient.generateStream(cloudPrompt, contextPayload, config)
                var cloudFailed = false

                streamFlow.catch { e ->
                    cloudFailed = true
                    if (!aiEngine.isInitialized) {
                        chatAdapter.streamChunk(
                            accumulatedText = "⚠️ Cloud Error: ${e.localizedMessage ?: e.message}",
                            tokPerSec = 0f,
                            tokenCount = 0,
                            isFinished = true,
                            finalStats = "Cloud Error"
                        )
                        setGeneratingState(false)
                    }
                }.collect { chunk ->
                    if (chunk.isFinished && (chunk.telemetrySummary?.startsWith("API Error") == true || chunk.telemetrySummary == "Network Error") && aiEngine.isInitialized) {
                        cloudFailed = true
                        return@collect
                    }
                    val statsTag = if (chunk.isFinished) "☁️ Cloud Gemini (Score ${routingDecision.complexityScore}/100) • ${chunk.telemetrySummary}" else null
                    chatAdapter.streamChunk(
                        accumulatedText = chunk.text,
                        tokPerSec = chunk.tokensPerSecond,
                        tokenCount = chunk.tokensGenerated,
                        isFinished = chunk.isFinished,
                        finalStats = statsTag
                    )
                    scrollChatToBottom(force = false)

                    if (chunk.isFinished) {
                        val completedMsg = ChatMessage(
                            text = chunk.text,
                            isUser = false,
                            isGenerating = false,
                            statsInfo = statsTag
                        )
                        dbHelper.insertChatMessage(completedMsg)
                        setGeneratingState(false)
                    }
                }

                // True Hybrid Resilience: if Cloud failed while online, run offline OCR on any attached photo then fall back to local LLM!
                if (cloudFailed && aiEngine.isInitialized) {
                    chatAdapter.streamChunk(
                        accumulatedText = "⚡ Cloud unreachable — running offline OCR + On-Device Local Model...",
                        tokPerSec = 0f,
                        tokenCount = 0,
                        isFinished = false
                    )
                    val visualPayload = contextPayload?.cameraImage ?: contextPayload?.screenCapture?.image
                    val localPrompt = if (visualPayload != null) {
                        val ocrText = aiEngine.extractTextFromPayload(visualPayload).getOrDefault("").trim()
                        if (ocrText.isNotBlank() && !ocrText.startsWith("No legible")) {
                            "The text below was extracted from a photo using noisy offline OCR (so some words may have character typos like 'mdifcatten'->'modification' or '€quetren'->'equation'). First mentally repair any OCR glitches using context, then answer concisely.\n\nRaw OCR:\n```\n$ocrText\n```\n\nUser Question: $effectivePrompt"
                        } else {
                            effectivePrompt
                        }
                    } else {
                        effectivePrompt
                    }
                    aiEngine.generateStream(localPrompt, maxTokens = 512).catch { e ->
                        chatAdapter.streamChunk("⚠️ Local Fallback Error: ${e.message}", 0f, 0, true, "Error")
                        setGeneratingState(false)
                    }.collect { chunk ->
                        val statsTag = if (chunk.isFinished) "⚡ Local Fallback ($0) • ${chunk.telemetrySummary}" else null
                        chatAdapter.streamChunk(chunk.text, chunk.tokensPerSecond, chunk.tokensGenerated, chunk.isFinished, statsTag)
                        scrollChatToBottom(force = false)
                        if (chunk.isFinished) {
                            dbHelper.insertChatMessage(ChatMessage(text = chunk.text, isUser = false, isGenerating = false, statsInfo = statsTag))
                            setGeneratingState(false)
                        }
                    }
                }
            } else if (isMultimodalVisual) {
                // High-performance offline ML Kit OCR & Visual Processing
                val visualPayload = contextPayload?.cameraImage ?: contextPayload?.screenCapture?.image
                val isOcrOnlyRequest = effectivePrompt.contains("convert to text", ignoreCase = true) ||
                        effectivePrompt.contains("extract text", ignoreCase = true) ||
                        effectivePrompt.contains("read text", ignoreCase = true) ||
                        effectivePrompt.equals("ocr", ignoreCase = true) ||
                        effectivePrompt.equals("text", ignoreCase = true)

                val ocrStart = System.currentTimeMillis()
                chatAdapter.streamChunk(
                    accumulatedText = "🔍 Scanning image text using On-Device OCR...",
                    tokPerSec = 0f,
                    tokenCount = 0,
                    isFinished = false
                )

                val ocrResult = if (visualPayload != null) {
                    aiEngine.extractTextFromPayload(visualPayload)
                } else {
                    Result.failure(IllegalArgumentException("No visual image payload found"))
                }
                val ocrDuration = System.currentTimeMillis() - ocrStart

                if (ocrResult.isFailure) {
                    val err = ocrResult.exceptionOrNull()?.localizedMessage ?: "OCR failed"
                    val errText = "⚠️ On-Device OCR Error: $err"
                    chatAdapter.streamChunk(
                        accumulatedText = errText,
                        tokPerSec = 0f,
                        tokenCount = 0,
                        isFinished = true,
                        finalStats = "OCR Error"
                    )
                    val completedMsg = ChatMessage(
                        text = errText,
                        isUser = false,
                        isGenerating = false,
                        statsInfo = "OCR Error"
                    )
                    dbHelper.insertChatMessage(completedMsg)
                    setGeneratingState(false)
                    return@launch
                }

                val extractedText = ocrResult.getOrNull()?.trim() ?: ""

                if (isOcrOnlyRequest || !aiEngine.isInitialized) {
                    val resultText = if (extractedText.isNotBlank() && extractedText != "No legible text detected in image.") {
                        "📷 **On-Device OCR Extracted Text:**\n\n$extractedText"
                    } else {
                        "📷 **On-Device OCR:** No legible text detected in the attached image."
                    }
                    val stats = "ML Kit On-Device OCR • ${ocrDuration}ms • 100% Offline"
                    val wordCount = if (extractedText.isNotBlank()) extractedText.split("\\s+".toRegex()).size else 0
                    chatAdapter.streamChunk(
                        accumulatedText = resultText,
                        tokPerSec = 0f,
                        tokenCount = wordCount,
                        isFinished = true,
                        finalStats = stats
                    )
                    val completedMsg = ChatMessage(
                        text = resultText,
                        isUser = false,
                        isGenerating = false,
                        statsInfo = stats
                    )
                    dbHelper.insertChatMessage(completedMsg)
                    setGeneratingState(false)
                    return@launch
                }

                // If user asked a question about the photo (e.g. "Summarize this", "Explain this equation", etc.)
                // Enrich the prompt for the local LLM
                val enrichedPrompt = "The user attached an image with the following text extracted on-device via OCR:\n```\n$extractedText\n```\n\nUser Question: $effectivePrompt\n\nPlease answer concisely and accurately based on the extracted text."

                val streamFlow = aiEngine.generateStream(enrichedPrompt, maxTokens = 1024)

                streamFlow.catch { e ->
                    chatAdapter.streamChunk(
                        accumulatedText = "⚠️ Error: ${e.localizedMessage ?: e.message}",
                        tokPerSec = 0f,
                        tokenCount = 0,
                        isFinished = true,
                        finalStats = "Error"
                    )
                    setGeneratingState(false)
                }.collect { chunk ->
                    val statsTag = if (chunk.isFinished) "${chunk.telemetrySummary} • OCR (${ocrDuration}ms)" else null
                    chatAdapter.streamChunk(
                        accumulatedText = chunk.text,
                        tokPerSec = chunk.tokensPerSecond,
                        tokenCount = chunk.tokensGenerated,
                        isFinished = chunk.isFinished,
                        finalStats = statsTag
                    )
                    scrollChatToBottom(force = false)

                    if (chunk.isFinished) {
                        val completedMsg = ChatMessage(
                            text = chunk.text,
                            isUser = false,
                            isGenerating = false,
                            statsInfo = statsTag
                        )
                        dbHelper.insertChatMessage(completedMsg)
                        setGeneratingState(false)
                    }
                }
            } else {
                val fullPrompt = if (!contextPayload?.audioTranscript.isNullOrBlank()) {
                    "$prompt\n[Transcribed Audio: ${contextPayload?.audioTranscript}]"
                } else prompt
                val streamFlow = aiEngine.generateStream(fullPrompt, maxTokens = 1024)

                streamFlow.catch { e ->
                    chatAdapter.streamChunk(
                        accumulatedText = "⚠️ Error: ${e.localizedMessage ?: e.message}",
                        tokPerSec = 0f,
                        tokenCount = 0,
                        isFinished = true,
                        finalStats = "Error"
                    )
                    setGeneratingState(false)
                }.collect { chunk ->
                    val statsTag = if (chunk.isFinished) "⚡ Edge Local (Saved ${routingDecision.tokensSaved} tokens) • ${chunk.telemetrySummary}" else null
                    chatAdapter.streamChunk(
                        accumulatedText = chunk.text,
                        tokPerSec = chunk.tokensPerSecond,
                        tokenCount = chunk.tokensGenerated,
                        isFinished = chunk.isFinished,
                        finalStats = statsTag
                    )
                    scrollChatToBottom(force = false)

                    if (chunk.isFinished) {
                        val completedMsg = ChatMessage(
                            text = chunk.text,
                            isUser = false,
                            isGenerating = false,
                            statsInfo = statsTag
                        )
                        dbHelper.insertChatMessage(completedMsg)
                        setGeneratingState(false)
                    }
                }
            }
        }
    }

    private fun stopGeneration() {
        currentInferenceJob?.cancel()
        currentInferenceJob = null
        setGeneratingState(false)
        val lastIndex = chatAdapter.itemCount - 1
        if (lastIndex >= 0) {
            chatAdapter.streamChunk(
                accumulatedText = messagesList[lastIndex].text,
                tokPerSec = 0f,
                tokenCount = 0,
                isFinished = true,
                finalStats = "Stopped by user"
            )
        }
    }

    private fun setGeneratingState(generating: Boolean) {
        isGenerating = generating
        binding.fabSendBuddy.setImageResource(if (generating) R.drawable.ic_stop else R.drawable.ic_send)
        updateEngineStatus(if (generating) "Generating…" else "Ready", if (generating) R.color.status_loading else R.color.status_ready)
    }

    // ==========================================
    // 2. VISUAL LENS: CAMERA & OFFLINE OCR
    // ==========================================
    private fun checkPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startCameraFeed() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            return
        }

        aiEngine.mobileAI.cameraManager.startCamera(binding.cameraTextureView, object : CameraVisionManager.FrameListener {
            override fun onFrameSampled(bitmap: Bitmap, imagePayload: ImagePayload) {
                latestCameraBitmap = bitmap
            }

            override fun onError(message: String) {
                Toast.makeText(this@MainActivity, "Camera: $message", Toast.LENGTH_SHORT).show()
            }
        })
        isCameraActive = true
        binding.tvSensoryVisionStatus.text = "Live"
    }

    private fun stopCameraFeed() {
        if (isCameraActive) {
            aiEngine.mobileAI.cameraManager.stopCamera()
            isCameraActive = false
            binding.tvSensoryVisionStatus.text = "Paused"
        }
    }

    private fun setupSensory() {
        // --- 1. GOOGLE LENS CONTROLS ---
        binding.btnToggleCameraFeed.setOnClickListener {
            if (isCameraActive) {
                stopCameraFeed()
            } else {
                startCameraFeed()
            }
        }

        binding.btnFlipCameraFacing.setOnClickListener {
            if (isCameraActive) {
                aiEngine.mobileAI.cameraManager.switchCamera(binding.cameraTextureView)
            }
        }

        // Lens Shutter: Capture direct from Viewfinder + Hybrid Handwriting & Print Vision OCR
        binding.btnSnapCameraToChat.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                return@setOnClickListener
            }

            val bitmap = binding.cameraTextureView.bitmap
            if (bitmap != null) {
                latestCameraBitmap = bitmap
                val payload = ImagePayload.fromBitmap(bitmap, 90)
                pendingMultimodalContext = (pendingMultimodalContext ?: MultimodalContext()).copy(cameraImage = payload)

                runHybridVisionOcr(bitmap, payload)
                showAttachmentBanner("📷 Camera snapshot captured")
            } else {
                Toast.makeText(this, "Starting camera preview... tap again in a moment", Toast.LENGTH_SHORT).show()
                startCameraFeed()
            }
        }

        // Lens Gallery Button
        binding.btnSensoryPickGallery.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        binding.tvLensModelBadge.setOnClickListener {
            showModelSelectionDialog(ModelRole.CHAT)
        }

        // Transparent Glassmorphic Feature Menu inside Visual Lens
        setupLensTransparentMenu()

        // Lens OCR Bottom Sheet Actions
        binding.btnVisualAskChat.setOnClickListener {
            val ocrText = binding.tvVisualOcrResult.text.toString()
            val query = if (ocrText.isNotBlank() && !ocrText.startsWith("No text") && !ocrText.startsWith("Scanning")) {
                "Explain and summarize this detected text:\n$ocrText"
            } else {
                "Explain what you see in this photo"
            }
            binding.etBuddyInput.setText(query)
            binding.layoutVisualOcrCard.visibility = View.GONE
            switchTab(Tab.CHAT)
            sendChatMessage()
        }

        binding.btnVisualCopyOcr.setOnClickListener {
            val ocrText = binding.tvVisualOcrResult.text.toString()
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("OCR Text", ocrText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnVisualCloseOcr.setOnClickListener {
            binding.layoutVisualOcrCard.visibility = View.GONE
        }

        // ShopFloor AI: Sync Lens Scan to Cloud Run -> BigQuery (Doc->Table OR QC Inspections)
        binding.btnVisualSyncQc.setOnClickListener {
            if (currentLensMode == LensMode.DOC_TABLE) {
                val bmp = latestCameraBitmap
                val payload = pendingMultimodalContext?.cameraImage
                if (bmp != null && payload != null) {
                    runHybridVisionOcr(bmp, payload)
                } else {
                    Toast.makeText(this, "Scan or pick a document first", Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }

            val ocrText = binding.tvVisualOcrResult.text.toString().trim()
            if (ocrText.isBlank() || ocrText.startsWith("Scanning") || ocrText.startsWith("No text") || ocrText.startsWith("⚠️") || ocrText.startsWith("🚫")) {
                Toast.makeText(this, "Scan a valid part tag or dimension report first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // On-device strict QC validation: reject gibberish / non-dimensional text before touching SQLite or BigQuery!
            val scanValidation = com.mobileai.framework.router.InputValidator.validateQcScanText(ocrText)
            if (!scanValidation.isValid) {
                val rejectMsg = "🚫 REJECTED — ${scanValidation.reason}"
                binding.tvVisualOcrResult.text = "$rejectMsg\n\n$ocrText"
                Toast.makeText(this, rejectMsg.take(120), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            binding.btnVisualSyncQc.isEnabled = false
            binding.btnVisualSyncQc.text = "⏳ Verifying & Syncing to BigQuery QC DB..."

            lifecycleScope.launch(Dispatchers.IO) {
                var accepted = true
                var verdictMsg: String
                try {
                    val endpoint = "${aiEngine.mobileAI.hybridRouter.cloudGatewayUrl}/v1/qc/scan-and-update"
                    val payload = org.json.JSONObject().apply {
                        put("ocr_text", ocrText)
                        pendingMultimodalContext?.cameraImage?.base64Data?.let { imgB64 ->
                            put("image_base64", imgB64)
                            put("mime_type", "image/jpeg")
                        }
                        put("source", "ANDROID_LENS_OCR")
                        put("operator", "K. Rathod")
                    }
                    val conn = (java.net.URL(endpoint).openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json")
                        doOutput = true
                        connectTimeout = 8000
                        readTimeout = 20000
                    }
                    conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                    val code = conn.responseCode
                    if (code in 200..299) {
                        val body = conn.inputStream.bufferedReader().readText()
                        val json = org.json.JSONObject(body)
                        accepted = json.optBoolean("accepted", true)
                        verdictMsg = json.optString("verdict", if (accepted) "✅ Logged to Cloud BigQuery QC DB!" else "🚫 Rejected invalid inspection scan.")
                    } else {
                        verdictMsg = "💾 Saved offline (Cloud HTTP $code)"
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    verdictMsg = "✈️ Saved offline in SQLite (Offline Mode)"
                }

                // Persist to local SQLite ONLY if valid & accepted (never pollute local DB with rejected scans!)
                if (accepted) {
                    try {
                        val entry = KnowledgeEntry(
                            title = "QC Scan (${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())})",
                            category = "Notes",
                            summary = verdictMsg.take(80),
                            content = "$verdictMsg\n\nRaw OCR:\n$ocrText",
                            timestamp = "Lens QC"
                        )
                        dbHelper.insertKnowledge(entry)
                    } catch (_: Exception) {}
                }

                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    binding.btnVisualSyncQc.isEnabled = true
                    binding.btnVisualSyncQc.text = "🏭 Log Dimension Scan to Cloud QC BigQuery"
                    binding.tvVisualOcrResult.text = "$verdictMsg\n\n$ocrText"
                    Toast.makeText(this@MainActivity, verdictMsg.take(110), Toast.LENGTH_LONG).show()
                }
            }
        }

        // --- 2. SCREEN CAPTURE & STREAM ---
        binding.btnSensoryScreenStream.setOnClickListener {
            if (isScreenStreamActive) {
                aiEngine.mobileAI.screenManager.stopScreenStream()
                isScreenStreamActive = false
                binding.btnSensoryScreenStream.text = "Start Screen Stream"
            } else {
                val intent = aiEngine.mobileAI.screenManager.createScreenCaptureIntent()
                screenCaptureLauncher.launch(intent)
            }
        }

        binding.btnSensorySendScreenToBuddy.setOnClickListener {
            val bitmap = latestScreenBitmap
            if (bitmap != null) {
                val payload = ImagePayload.fromBitmap(bitmap, 75)
                val screen = ScreenPayload(payload, bitmap.width, bitmap.height, resources.configuration.orientation)
                pendingMultimodalContext = (pendingMultimodalContext ?: MultimodalContext()).copy(screenCapture = screen)
                showAttachmentBanner("📱 Screen capture frame attached")
                if (binding.etBuddyInput.text.isNullOrBlank()) {
                    binding.etBuddyInput.setText("Convert to text")
                }
                switchTab(Tab.CHAT)
            } else {
                Toast.makeText(this, "Start Screen Stream first to capture live frames.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupLensTransparentMenu() {
        val modePills = listOf(
            Triple(binding.chipLensDocTable, binding.tvLensDocTableLabel, LensMode.DOC_TABLE),
            Triple(binding.chipLensHandwriting, binding.tvLensHandwritingLabel, LensMode.HANDWRITING),
            Triple(binding.chipLensQcInspect, binding.tvLensQcLabel, LensMode.QC_INSPECT),
            Triple(binding.chipLensAskAi, binding.tvLensAskAiLabel, LensMode.ASK_AI)
        )

        fun updateLensModeUi(mode: LensMode) {
            currentLensMode = mode
            for ((pill, label, m) in modePills) {
                if (m == mode) {
                    pill.setBackgroundResource(R.drawable.bg_lens_dock_active)
                    label.setTextColor(ContextCompat.getColor(this, R.color.white))
                    label.setTypeface(null, android.graphics.Typeface.BOLD)
                } else {
                    pill.background = null
                    label.setTextColor(0xD9FFFFFF.toInt())
                    label.setTypeface(null, android.graphics.Typeface.NORMAL)
                }
            }
            binding.tvLensModelBadge.text = when (mode) {
                LensMode.DOC_TABLE -> "MobileAI Lens • Doc Table"
                LensMode.HANDWRITING -> "MobileAI Lens • Handwriting"
                LensMode.QC_INSPECT -> "MobileAI Lens • QC Check"
                LensMode.ASK_AI -> "MobileAI Lens • Ask AI"
            }
            binding.btnVisualSyncQc.text = when (mode) {
                LensMode.DOC_TABLE -> "📄 Re-Convert & Save Table to Cloud DB"
                else -> "🏭 Log Dimension Scan to Cloud QC BigQuery"
            }
        }

        for ((pill, _, m) in modePills) {
            pill.setOnClickListener { updateLensModeUi(m) }
        }
        updateLensModeUi(LensMode.DOC_TABLE)
    }

    /**
     * Hybrid Document-to-Table & Handwriting Vision Pipeline driven by the Transparent Lens Menu.
     */
    private fun runHybridVisionOcr(bitmap: Bitmap, payload: ImagePayload) {
        if (currentLensMode == LensMode.ASK_AI) {
            binding.layoutVisualOcrCard.visibility = View.GONE
            if (binding.etBuddyInput.text.isNullOrBlank()) {
                binding.etBuddyInput.setText("Analyze this scanned document and summarize key details")
            }
            switchTab(Tab.CHAT)
            sendChatMessage()
            return
        }

        binding.tvVisualOcrHeader.text = when (currentLensMode) {
            LensMode.DOC_TABLE -> "📄 Converting Document → Abstract Table..."
            LensMode.QC_INSPECT -> "🏭 Checking QC Dimensions & Tolerances..."
            else -> "✨ Reading Handwriting & Print..."
        }
        binding.tvVisualOcrResult.text = "Scanning page with Enhanced ML Kit + Gemini Vision..."
        binding.layoutVisualOcrCard.visibility = View.VISIBLE

        lifecycleScope.launch {
            val offlineResult = aiEngine.extractTextFromBitmap(bitmap)
            val offlineText = offlineResult.getOrDefault("").trim()
            val hasOfflineText = offlineResult.isSuccess && offlineText.isNotBlank() && !offlineText.startsWith("No legible")

            val deviceState = com.mobileai.framework.router.DeviceStateProfile.capture(
                this@MainActivity,
                aiEngine.mobileAI.thermalGovernor
            )

            // 1. DOC -> ABSTRACT TABLE MODE (Default)
            if (currentLensMode == LensMode.DOC_TABLE) {
                if (!deviceState.isOnline) {
                    if (hasOfflineText && aiEngine.isInitialized) {
                        refineOfflineOcrWithGemma(rawOcr = offlineText, asTable = true)
                    } else {
                        binding.tvVisualOcrHeader.text = "✈️ Offline Table Preview"
                        val localTable = formatOfflineLinesAsTable(offlineText)
                        binding.tvVisualOcrResult.text = if (hasOfflineText) localTable else "No text detected offline. Align document cleanly or go online for Gemini Vision."
                    }
                    return@launch
                }

                binding.tvVisualOcrHeader.text = "📄 Extracting Tabular Data → Cloud BigQuery..."
                if (hasOfflineText) binding.tvVisualOcrResult.text = "⏳ Structuring into dynamic table...\n\n$offlineText"

                val tableResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    try {
                        val endpoint = "${aiEngine.mobileAI.hybridRouter.cloudGatewayUrl}/v1/docs/scan-to-table"
                        val bodyJson = org.json.JSONObject().apply {
                            put("ocr_text", if (hasOfflineText) offlineText else "")
                            put("image_base64", payload.base64Data)
                            put("mime_type", "image/jpeg")
                            put("source", "ANDROID_LENS_DOC_SCAN")
                        }
                        val conn = (java.net.URL(endpoint).openConnection() as java.net.HttpURLConnection).apply {
                            requestMethod = "POST"
                            setRequestProperty("Content-Type", "application/json")
                            doOutput = true
                            connectTimeout = 10000
                            readTimeout = 35000
                        }
                        conn.outputStream.use { it.write(bodyJson.toString().toByteArray()) }
                        val code = conn.responseCode
                        val respText = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
                        conn.disconnect()
                        org.json.JSONObject(respText)
                    } catch (e: Exception) {
                        null
                    }
                }

                if (tableResult != null && tableResult.optBoolean("accepted", false)) {
                    val doc = tableResult.optJSONObject("document")
                    val title = doc?.optString("title", "Scanned Document") ?: "Scanned Document"
                    val docId = doc?.optString("doc_id", "") ?: ""
                    val bqFlag = if (tableResult.optBoolean("bigquery_streamed", false)) "BigQuery ✓" else "Cloud Saved ✓"
                    val rendered = formatJsonDocAsTable(doc)
                    binding.tvVisualOcrHeader.text = "✅ $docId • $title ($bqFlag)"
                    binding.tvVisualOcrResult.text = rendered
                    Toast.makeText(this@MainActivity, "✅ Tabular data saved to Cloud DB ($docId)", Toast.LENGTH_SHORT).show()
                } else {
                    if (hasOfflineText && aiEngine.isInitialized) {
                        refineOfflineOcrWithGemma(rawOcr = offlineText, asTable = true)
                    } else {
                        val err = tableResult?.optString("message")?.takeIf { it.isNotBlank() }
                            ?: "Could not convert scan to table."
                        binding.tvVisualOcrHeader.text = "🚫 Document Table Scan"
                        binding.tvVisualOcrResult.text = if (hasOfflineText) "$err\n\nRaw OCR:\n$offlineText" else err
                    }
                }
                return@launch
            }

            // 2. QC INSPECT MODE
            if (currentLensMode == LensMode.QC_INSPECT) {
                binding.tvVisualOcrResult.text = if (hasOfflineText) offlineText else "Scanning inspection dimensions..."
                if (deviceState.isOnline) {
                    binding.btnVisualSyncQc.performClick()
                } else if (hasOfflineText && aiEngine.isInitialized) {
                    refineOfflineOcrWithGemma(rawOcr = offlineText, asTable = true)
                } else {
                    binding.tvVisualOcrHeader.text = "✈️ Offline OCR Preview (Tap Sync when online)"
                }
                return@launch
            }

            // 3. HANDWRITING & PRINT VERBATIM OCR MODE
            val geminiConfig = aiEngine.mobileAI.cloudApiKeyStore.getConfig(com.mobileai.framework.cloud.CloudProvider.GEMINI)
            if (!deviceState.isOnline || geminiConfig.apiKey.isBlank()) {
                if (hasOfflineText && aiEngine.isInitialized) {
                    refineOfflineOcrWithGemma(rawOcr = offlineText, asTable = false)
                } else {
                    binding.tvVisualOcrHeader.text = "✈️ Offline Enhanced OCR (ML Kit)"
                    binding.tvVisualOcrResult.text = if (hasOfflineText) offlineText else "No text detected offline. Connect to Wi-Fi/4G for Gemini Vision dirty-handwriting OCR."
                }
                return@launch
            }

            if (hasOfflineText) {
                binding.tvVisualOcrHeader.text = "✨ Enhancing handwriting with Gemini Vision..."
                binding.tvVisualOcrResult.text = offlineText
            } else {
                binding.tvVisualOcrHeader.text = "✨ Reading handwriting with Gemini Vision..."
                binding.tvVisualOcrResult.text = "⏳ Analyzing messy handwriting & print with Gemini 2.5 Flash Vision..."
            }

            val visionResult = aiEngine.mobileAI.cloudClient.transcribeImageText(payload, geminiConfig)
            visionResult.onSuccess { transcribed ->
                if (transcribed.equals("NO_TEXT_FOUND", ignoreCase = true) || transcribed.isBlank()) {
                    binding.tvVisualOcrHeader.text = "✨ Gemini Vision OCR"
                    binding.tvVisualOcrResult.text = if (hasOfflineText) offlineText else "No readable text found in image."
                } else {
                    binding.tvVisualOcrHeader.text = "✨ Gemini Vision OCR (Handwriting & Print)"
                    binding.tvVisualOcrResult.text = transcribed
                }
            }.onFailure {
                if (hasOfflineText && aiEngine.isInitialized) {
                    refineOfflineOcrWithGemma(rawOcr = offlineText, asTable = false)
                } else {
                    binding.tvVisualOcrHeader.text = "✈️ Offline Enhanced OCR (Cloud Fallback)"
                    binding.tvVisualOcrResult.text = if (hasOfflineText) offlineText else "No text detected. Try better lighting or steady focus."
                }
            }
        }
    }

    /**
     * 2-Stage 100% Offline Pipeline:
     * Raw ML Kit OCR (fast, noisy on dirty handwriting) -> On-Device Google Gemma 2 2B Denoiser & Table Structurer ($0 cloud cost).
     */
    private suspend fun refineOfflineOcrWithGemma(rawOcr: String, asTable: Boolean) {
        val modelShort = if (aiEngine.currentModelName.contains("gemma", ignoreCase = true)) "Gemma 2 2B" else "Local AI"
        binding.tvVisualOcrHeader.text = if (asTable) {
            "✈️ Offline $modelShort • Building Clean Table..."
        } else {
            "✈️ Offline $modelShort • Repairing Handwriting..."
        }
        binding.tvVisualOcrResult.text = "⏳ $modelShort is repairing OCR typos locally ($0 cloud)...\n\n$rawOcr"

        val prompt = if (asTable) {
            """
            The text below came from noisy offline OCR on a handwritten or printed page (so words may have character typos like 'mdifcatten'->'modification', '€quetren'->'equation', 'pluy it baek'->'plug it back').
            1. Fix all obvious OCR character glitches using context.
            2. Organize the repaired information into a clean, concise pipe-separated table (Header | Column 2 | ...).
            3. Output ONLY the clean repaired table — no intro or commentary.

            Noisy OCR:
            $rawOcr
            """.trimIndent()
        } else {
            """
            The text below came from noisy offline OCR on handwritten notes (so words may have character typos like 'mdifcatten'->'modification', 'canplem€toay €quetren'->'complementary equation', 'pluy it baek'->'plug it back').
            Reconstruct the clean, readable English text and math/engineering terms line-by-line using context.
            Output ONLY the repaired clean text — no intro or explanation.

            Noisy OCR:
            $rawOcr
            """.trimIndent()
        }

        val sysPrompt = "You are an accurate on-device OCR reconstruction and document structuring engine. Fix OCR character typos faithfully without inventing unrelated facts."
        try {
            aiEngine.generateStream(prompt = prompt, maxTokens = 384, systemPrompt = sysPrompt)
                .catch {
                    binding.tvVisualOcrHeader.text = "✈️ Offline OCR Preview"
                    binding.tvVisualOcrResult.text = if (asTable) formatOfflineLinesAsTable(rawOcr) else rawOcr
                }
                .collect { chunk ->
                    if (chunk.text.isNotBlank()) {
                        binding.tvVisualOcrResult.text = chunk.text.trim()
                    }
                    if (chunk.isFinished) {
                        val speed = String.format(java.util.Locale.US, "%.1f", chunk.tokensPerSecond)
                        binding.tvVisualOcrHeader.text = if (asTable) {
                            "⚡ Offline $modelShort Table • ${speed} tok/s • $0"
                        } else {
                            "⚡ Offline $modelShort Repaired • ${speed} tok/s • $0"
                        }
                    }
                }
        } catch (_: Exception) {
            binding.tvVisualOcrHeader.text = "✈️ Offline OCR Preview"
            binding.tvVisualOcrResult.text = if (asTable) formatOfflineLinesAsTable(rawOcr) else rawOcr
        }
    }

    private fun formatJsonDocAsTable(doc: org.json.JSONObject?): String {
        if (doc == null) return ""
        val sb = StringBuilder()
        val docType = doc.optString("doc_type", "Abstract Table")
        val summary = doc.optString("summary", "")
        sb.append("[$docType] $summary\n\n")
        val cols = doc.optJSONArray("columns") ?: return sb.toString()
        val rows = doc.optJSONArray("rows") ?: return sb.toString()

        val colList = (0 until cols.length()).map { cols.optString(it) }
        sb.append(colList.joinToString(" | ")).append("\n")
        sb.append(colList.joinToString("-+-") { "-".repeat(it.length.coerceIn(4, 12)) }).append("\n")
        for (i in 0 until rows.length()) {
            val r = rows.optJSONArray(i) ?: continue
            val cells = (0 until r.length()).map { r.optString(it) }
            sb.append(cells.joinToString(" | ")).append("\n")
        }
        return sb.toString().trim()
    }

    private fun formatOfflineLinesAsTable(rawText: String): String {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return "No rows extracted."
        val sb = StringBuilder("Line | Extracted Content\n-----+------------------\n")
        lines.forEachIndexed { idx, line -> sb.append("${idx + 1}    | $line\n") }
        return sb.toString().trim()
    }

    // ==========================================
    // 3. AGENT: AUTONOMOUS PHONE CONTROLLER
    // ==========================================
    private fun setupAgent() {
        agentStepAdapter = AgentStepAdapter(agentStepsList)
        binding.rvAgentSteps.layoutManager = LinearLayoutManager(this)
        binding.rvAgentSteps.adapter = agentStepAdapter

        updateAccessibilityStatus()

        binding.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnRunAgentGoal.setOnClickListener {
            val goal = binding.etAgentGoal.text?.toString()?.trim() ?: ""
            if (goal.isEmpty()) {
                Toast.makeText(this, "Please enter a goal for the agent.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!PhoneControllerAccessibilityService.isServiceRunning) {
                Toast.makeText(this, "Enable Phone Controller Accessibility Service first.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }

            agentStepAdapter.clearSteps()
            binding.btnRunAgentGoal.isEnabled = false

            val cloudStore = aiEngine.mobileAI.cloudApiKeyStore
            val cloudConfig = if (cloudStore.isCloudPreferred() || !aiEngine.isInitialized) {
                cloudStore.getConfig()
            } else null

            aiEngine.mobileAI.deviceAgent.executeGoal(
                goal = goal,
                localEngine = if (aiEngine.isInitialized) aiEngine else null,
                cloudConfig = cloudConfig,
                listener = object : DeviceAgent.AgentListener {
                    override fun onAgentStarted(goal: String) {
                        Toast.makeText(this@MainActivity, "🤖 Agent running: $goal", Toast.LENGTH_SHORT).show()
                    }

                    override fun onStep(step: AgentStep) {
                        agentStepAdapter.addStep(step)
                        binding.rvAgentSteps.smoothScrollToPosition(agentStepAdapter.itemCount - 1)
                    }

                    override fun onAgentFinished(success: Boolean, summary: String) {
                        binding.btnRunAgentGoal.isEnabled = true
                        val icon = if (success) "✓" else "✗"
                        Toast.makeText(this@MainActivity, "$icon $summary", Toast.LENGTH_LONG).show()
                    }

                    override fun onError(message: String) {
                        binding.btnRunAgentGoal.isEnabled = true
                        Toast.makeText(this@MainActivity, "Agent error: $message", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        binding.btnStopAgentGoal.setOnClickListener {
            aiEngine.mobileAI.deviceAgent.cancel()
            binding.btnRunAgentGoal.isEnabled = true
            Toast.makeText(this, "Agent execution stopped.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAccessibilityStatus() {
        val running = PhoneControllerAccessibilityService.isServiceRunning
        val green = ContextCompat.getColor(this, R.color.status_ready)
        val amber = ContextCompat.getColor(this, R.color.royal_gold)
        if (running) {
            binding.tvAccessibilityStatus.text = "Accessibility Service: ACTIVE ✓"
            binding.tvAccessibilityStatus.setTextColor(green)
            binding.btnEnableAccessibility.visibility = View.GONE
        } else {
            binding.tvAccessibilityStatus.text = "Accessibility Service: DISABLED (Tap Enable)"
            binding.tvAccessibilityStatus.setTextColor(amber)
            binding.btnEnableAccessibility.visibility = View.VISIBLE
        }
    }

    // ==========================================
    // 4. HARDWARE & NPU DIAGNOSTICS VIEW
    // ==========================================
    private fun setupHardware() {
        binding.btnRefreshDiagnostics.setOnClickListener {
            refreshHardwareDiagnostics()
            Toast.makeText(this, "Diagnostics updated", Toast.LENGTH_SHORT).show()
        }

        binding.btnRunHardwareBenchmark.setOnClickListener {
            runNpuAndCoreBenchmark()
        }

        refreshHardwareDiagnostics()
    }

    private fun refreshHardwareDiagnostics() {
        val profile = aiEngine.getHardwareProfile()
        val topo = profile.cpuTopology

        val primaryAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        binding.tvSocModelInfo.text = "SoC: ${profile.socModel} • $primaryAbi"
        binding.tvCpuClusterInfo.text = "Clusters: ${topo.rawClusterDescription} (${topo.totalCores} cores)"
        binding.tvCpuPinningStatus.text = "Big-Core Affinity: ${topo.optimalInferenceThreads} Threads Pinned (Optimal)"

        val totalRam = profile.totalRamMb
        val availRam = profile.availableRamMb
        val usedRam = (totalRam - availRam).coerceAtLeast(0)
        val pct = if (totalRam > 0) ((usedRam.toFloat() / totalRam) * 100).toInt() else 0

        binding.tvRamBudgetInfo.text = "Total RAM: ${totalRam} MB | Free: ${availRam} MB (${100 - pct}% Free)"
        binding.tvKvCacheRecommended.text = "Recommended Context: ${profile.recommendedContextSize} tokens (KV-Cache Guard Active)"
        binding.pbRamUsage.progress = pct

        val npuReport = aiEngine.getNpuCapabilities()
        val preferred = npuReport.preferredAccelerator
        val accelName = preferred?.name ?: "CPU NEON"
        val accelType = preferred?.type?.name ?: "CPU"
        binding.tvNpuProbeReport.text = "NPU Backend: $accelName ($accelType)"
        val dataTypes = buildList {
            if (npuReport.supportsInt8Quantization) add("INT8 GEMM")
            if (npuReport.supportsFp16Inference) add("FP16")
        }.ifEmpty { listOf("FP32 NEON") }
        binding.tvNpuSupportedOps.text = "NNAPI: ${if (npuReport.hasNnApi) "v${npuReport.nnApiVersion} Ready" else "CPU Fallback"} • Supported: ${dataTypes.joinToString(", ")}"

        binding.tvThermalStatus.text = "Thermal State: ${aiEngine.mobileAI.thermalGovernor.currentThermalLevel}"
        binding.tvGovernorAction.text = "Active Governor: Adaptive Big-Core Dynamic Thread Scaling"
    }

    private fun runNpuAndCoreBenchmark() {
        binding.tvNpuProbeReport.text = "Benchmarking NPU & Matrix NEON..."
        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            val npuReport = aiEngine.getNpuCapabilities()
            val elapsed = System.currentTimeMillis() - start
            val preferred = npuReport.preferredAccelerator
            val accelName = preferred?.name ?: "CPU NEON"
            binding.tvNpuProbeReport.text = "NPU Probe: $accelName (${elapsed}ms probe)"
            Toast.makeText(this@MainActivity, "NPU & Matrix probe completed in ${elapsed}ms", Toast.LENGTH_SHORT).show()
        }
    }

    // ==========================================
    // 6. CLOUD AI HUB & MULTI-MODEL GATEWAY
    // ==========================================
    private fun showCloudHubDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_cloud_hub)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val store = aiEngine.mobileAI.cloudApiKeyStore
        val spProvider = dialog.findViewById<Spinner>(R.id.spinnerCloudProvider)
        val etApiKey = dialog.findViewById<EditText>(R.id.etCloudApiKey)
        val etModelName = dialog.findViewById<EditText>(R.id.etCloudModelName)
        val etBaseUrl = dialog.findViewById<EditText>(R.id.etCloudBaseUrl)
        val switchCloud = dialog.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchUseCloud)
        val btnTest = dialog.findViewById<Button>(R.id.btnTestCloudConnection)
        val btnSave = dialog.findViewById<Button>(R.id.btnSaveCloudConfig)
        val btnClose = dialog.findViewById<ImageView>(R.id.btnCloseCloudDialog)

        switchCloud.isChecked = store.isCloudPreferred()

        val providers = CloudProvider.values()
        val providerNames = providers.map { "${it.displayName}${if (it.isEuropean) " 🇪🇺" else ""}" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providerNames)
        spProvider.adapter = adapter

        val currentActive = store.getActiveProvider()
        spProvider.setSelection(providers.indexOf(currentActive))

        fun populateFields(p: CloudProvider) {
            etApiKey.setText(store.getApiKey(p))
            etModelName.setText(store.getModelName(p))
            etBaseUrl.setText(store.getBaseUrl(p))
        }
        populateFields(currentActive)

        spProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                populateFields(providers[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        btnTest.setOnClickListener {
            val selectedProvider = providers[spProvider.selectedItemPosition]
            val key = etApiKey.text.toString().trim()
            val model = etModelName.text.toString().trim()
            val url = etBaseUrl.text.toString().trim()

            if (key.isEmpty()) {
                Toast.makeText(this, "Enter API key to test connection", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnTest.isEnabled = false
            btnTest.text = "Testing..."

            lifecycleScope.launch {
                val cfg = com.mobileai.framework.cloud.CloudAiConfig(
                    provider = selectedProvider,
                    apiKey = key,
                    modelName = model,
                    baseUrl = url,
                    maxTokens = 64
                )
                val response = aiEngine.mobileAI.cloudClient.generateSync("Ping test: reply with 'OK'", null, cfg)
                btnTest.isEnabled = true
                btnTest.text = "Test Ping"
                Toast.makeText(this@MainActivity, "Response: ${response.take(120)}", Toast.LENGTH_LONG).show()
            }
        }

        btnSave.setOnClickListener {
            val selectedProvider = providers[spProvider.selectedItemPosition]
            store.setActiveProvider(selectedProvider)
            store.setApiKey(selectedProvider, etApiKey.text.toString().trim())
            store.setModelName(selectedProvider, etModelName.text.toString().trim())
            store.setBaseUrl(selectedProvider, etBaseUrl.text.toString().trim())
            store.setCloudPreferred(switchCloud.isChecked)

            updateModelSubtitle()
            dialog.dismiss()
            Toast.makeText(this, "Active: ${selectedProvider.displayName}", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun updateModelSubtitle() {
        val store = aiEngine.mobileAI.cloudApiKeyStore
        if (store.isCloudPreferred()) {
            val provider = store.getActiveProvider()
            val model = store.getModelName(provider)
            binding.tvModelSubtitle.text = "• ${model.take(12)}"
            updateEngineStatus("Cloud Active", R.color.google_blue)
            binding.layoutChatModelPrompt.visibility = View.GONE
        } else if (aiEngine.isInitialized) {
            val shortName = aiEngine.currentModelName.substringBefore("-it").substringBefore("-Instruct").substringBefore(".gguf").take(14)
            val role = ModelFileManager.getModelRole(aiEngine.currentModelName)
            binding.tvModelSubtitle.text = "• ${role.icon} $shortName"
            updateEngineStatus("Local Ready", R.color.status_ready)

            // Update Tab status badges
            binding.layoutChatModelPrompt.visibility = if (role == ModelRole.CHAT || role == ModelRole.VISUAL) View.GONE else View.VISIBLE
            binding.tvLensModelBadge.text = "📷 Google Lens • Offline OCR"
        } else {
            binding.tvModelSubtitle.text = "• Select Model"
            updateEngineStatus("Select Model", R.color.status_loading)
            binding.layoutChatModelPrompt.visibility = View.VISIBLE
            binding.tvLensModelBadge.text = "📷 Google Lens • Offline OCR"
        }
    }

    // ==========================================
    // 7. MODEL CONTROLS & SWITCHING
    // ==========================================
    private fun setupModelControls() {
        binding.statusPill.setOnClickListener {
            showModelSelectionDialog()
        }

        binding.btnSwitchModel.setOnClickListener {
            showModelSelectionDialog()
        }

        binding.tvModelSubtitle.setOnClickListener {
            showModelSelectionDialog()
        }
    }

    private fun checkAndInitializeModel() {
        val existingModel = ModelFileManager.findExistingModel(this)
        val existingProjector = ModelFileManager.findVisionProjector(this)

        if (existingModel != null) {
            initializeEngineWithModel(existingModel)
        } else if (existingProjector != null) {
            showVisionProjectorDetectedState(existingProjector)
        } else {
            // Auto-detect and import if models are available in /data/local/tmp
            val tmpModel = File("/data/local/tmp", ModelFileManager.DEFAULT_SMOLLM_360M)
            if (tmpModel.exists() && tmpModel.canRead()) {
                scanLocalTmp()
            } else {
                showModelMissingState()
            }
        }
    }

    private fun showVisionProjectorDetectedState(projectorFile: File) {
        val sizeMb = projectorFile.length() / (1024 * 1024)
        updateEngineStatus("Projector Only", R.color.status_loading)
        binding.tvModelSubtitle.text = "• Attach Model"
        binding.tvLensModelBadge.text = "👁️ mmproj Attached"
        Toast.makeText(
            this,
            "👁️ Vision projector '${projectorFile.name}' (${sizeMb}MB) active. Please select a base language model.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun initializeEngineWithModel(modelFile: File) {
        selectedModelFile = modelFile
        val isGguf = modelFile.name.lowercase().endsWith(".gguf")

        val budget = aiEngine.evaluateMemoryBudget(modelFile)
        val role = ModelFileManager.getModelRole(modelFile.name)
        val engineLabel = when {
            !isGguf -> "MediaPipe GPU"
            modelFile.name.contains("gemma", ignoreCase = true) -> "Gemma-2 GGUF"
            modelFile.name.contains("smollm", ignoreCase = true) -> "SmolLM2 GGUF"
            modelFile.name.contains("qwen", ignoreCase = true) -> "Qwen GGUF"
            modelFile.name.contains("whisper", ignoreCase = true) -> "Whisper ASR"
            modelFile.name.contains("minilm", ignoreCase = true) || modelFile.name.contains("bge", ignoreCase = true) -> "Vector Embedding"
            else -> "llama.cpp GGUF"
        }

        if (!budget.isSafeToLoad) {
            Toast.makeText(this, "⚠️ Low RAM Warning: ${budget.advisoryNote}", Toast.LENGTH_LONG).show()
        }

        updateEngineStatus("Loading...", R.color.status_loading)

        lifecycleScope.launch {
            val result = aiEngine.initializeEngine(
                modelPath = modelFile.absolutePath,
                maxTokens = 1024,
                temperature = 0.7f
            )

            result.onSuccess { engineDesc ->
                val sizeMb = modelFile.length() / (1024 * 1024)
                val profile = aiEngine.getHardwareProfile()
                val threadInfo = "${profile.cpuTopology.optimalInferenceThreads}T Big-Core"
                binding.tvModelSubtitle.text = "${role.icon} $engineLabel • ${sizeMb}MB • $threadInfo"
                updateEngineStatus("Ready", R.color.status_ready)
                updateModelSubtitle()
                Toast.makeText(this@MainActivity, "$engineDesc ready for inference!", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                updateEngineStatus("Init Failed", R.color.status_error)
                Toast.makeText(this@MainActivity, "Initialization error: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showModelSelectionDialog(initialFilterRole: ModelRole? = null) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_model_select)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val layoutList = dialog.findViewById<LinearLayout>(R.id.layoutModelsList)
        val btnImport = dialog.findViewById<Button>(R.id.btnImportNewModel)
        val btnScanTmp = dialog.findViewById<Button>(R.id.btnScanTmpDialog)

        val chipAll = dialog.findViewById<TextView>(R.id.chipModelAll)
        val chipChat = dialog.findViewById<TextView>(R.id.chipModelChat)
        val chipVisual = dialog.findViewById<TextView>(R.id.chipModelVisual)

        var activeFilter: ModelRole? = initialFilterRole

        val installedModels = ModelFileManager.getInstalledModels(this)

        fun renderModels() {
            layoutList.removeAllViews()

            val filtered = if (activeFilter == null) {
                installedModels
            } else {
                installedModels.filter { ModelFileManager.getModelRole(it.name) == activeFilter }
            }

            if (filtered.isEmpty()) {
                val tvEmpty = TextView(this).apply {
                    val filterName = activeFilter?.categoryName ?: "matching"
                    text = "No $filterName models found.\nTap '+ Select File' or push via ADB to /data/local/tmp."
                    setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                    textSize = 12f
                    setPadding(16, 24, 16, 24)
                    gravity = android.view.Gravity.CENTER
                }
                layoutList.addView(tvEmpty)
                return
            }

            val grouped = filtered.groupBy { ModelFileManager.getModelRole(it.name) }

            for ((role, models) in grouped) {
                val (roleColorRes, rolePillRes) = when (role) {
                    ModelRole.CHAT -> Pair(R.color.google_blue, R.drawable.bg_tab_pill_blue)
                    ModelRole.VISUAL -> Pair(R.color.google_red, R.drawable.bg_tab_pill_red)
                    ModelRole.AUDIO -> Pair(R.color.google_green, R.drawable.bg_tab_pill_green)
                    ModelRole.EMBEDDING -> Pair(R.color.google_yellow, R.drawable.bg_tab_pill_yellow)
                }

                // Section Header
                val tvSection = TextView(this).apply {
                    text = "${role.icon} ${role.categoryName.uppercase()}"
                    setTextColor(ContextCompat.getColor(context, roleColorRes))
                    textSize = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(4, 12, 4, 6)
                }
                layoutList.addView(tvSection)

                for (file in models) {
                    val sizeMb = file.length() / (1024 * 1024)
                    val isSelected = selectedModelFile?.absolutePath == file.absolutePath
                    val isProjector = ModelFileManager.isVisionProjector(file.name)

                    val itemLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 4, 0, 4) }
                        setBackgroundResource(if (isSelected) R.drawable.bg_card_boxy_gold else R.drawable.bg_card_boxy)
                        setPadding(16, 12, 16, 12)
                        isClickable = true
                        isFocusable = true
                    }

                    val infoLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val tvName = TextView(this).apply {
                        text = file.name
                        setTextColor(ContextCompat.getColor(context, if (isSelected) roleColorRes else R.color.text_primary))
                        textSize = 13f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }

                    val tvInfo = TextView(this).apply {
                        val roleTag = when {
                            isProjector -> "Vision Projector mmproj (Adapter)"
                            role == ModelRole.AUDIO -> "Whisper ASR Speech-to-Text"
                            role == ModelRole.EMBEDDING -> "Dense Vector Embedding Model"
                            file.name.contains("vlm", ignoreCase = true) || file.name.contains("ocr", ignoreCase = true) -> "Vision-Language Base Model"
                            else -> "llama.cpp NEON LLM"
                        }
                        val budgetNote = if (!aiEngine.evaluateMemoryBudget(file).isSafeToLoad) "⚠️ High RAM" else "✓ RAM OK"
                        text = "${sizeMb}MB • $roleTag • $budgetNote"
                        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                        textSize = 11f
                    }

                    infoLayout.addView(tvName)
                    infoLayout.addView(tvInfo)
                    itemLayout.addView(infoLayout)

                    if (isSelected) {
                        val tvActive = TextView(this).apply {
                            text = "ACTIVE"
                            setTextColor(ContextCompat.getColor(context, roleColorRes))
                            textSize = 10f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setBackgroundResource(rolePillRes)
                            setPadding(8, 4, 8, 4)
                        }
                        itemLayout.addView(tvActive)
                    }

                    itemLayout.setOnClickListener {
                        dialog.dismiss()
                        if (isProjector || role == ModelRole.VISUAL) {
                            binding.tvLensModelBadge.text = "📷 Offline ML Kit OCR + Gemini Vision"
                            Toast.makeText(
                                this@MainActivity,
                                "👁️ Visual pipeline uses bundled Offline ML Kit OCR + Cloud Gemini Vision.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            initializeEngineWithModel(file)
                        }
                    }

                    layoutList.addView(itemLayout)
                }
            }
        }

        fun updateChips() {
            val chips = listOf(
                Pair(chipAll, null),
                Pair(chipChat, ModelRole.CHAT),
                Pair(chipVisual, ModelRole.VISUAL)
            )
            for ((chip, role) in chips) {
                if (chip == null) continue
                val isSelected = (activeFilter == role)
                if (isSelected) {
                    val (bgRes, colorRes) = when (role) {
                        ModelRole.VISUAL -> Pair(R.drawable.bg_tab_pill_red, R.color.google_red)
                        else -> Pair(R.drawable.bg_tab_pill_blue, R.color.google_blue)
                    }
                    chip.setBackgroundResource(bgRes)
                    chip.setTextColor(ContextCompat.getColor(this, colorRes))
                } else {
                    chip.setBackgroundResource(R.drawable.bg_pill_outlined)
                    chip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                }
            }
            renderModels()
        }

        chipAll?.setOnClickListener { activeFilter = null; updateChips() }
        chipChat?.setOnClickListener { activeFilter = ModelRole.CHAT; updateChips() }
        chipVisual?.setOnClickListener { activeFilter = ModelRole.VISUAL; updateChips() }

        btnScanTmp?.setOnClickListener {
            dialog.dismiss()
            scanLocalTmp()
        }

        btnImport?.setOnClickListener {
            dialog.dismiss()
            modelPickerLauncher.launch("*/*")
        }

        updateChips()
        dialog.show()
    }

    private fun showModelRequiredDialog(targetRole: ModelRole = ModelRole.CHAT) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("💬 AI Model Needed")
            .setMessage("On-device AI reasoning requires a loaded local language model (e.g. SmolLM2, Qwen2.5, Gemma-2).\n\nWould you like to select a model?")
            .setPositiveButton("Select Model") { _, _ ->
                showModelSelectionDialog(ModelRole.CHAT)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showModelMissingState() {
        updateEngineStatus("Select Model", R.color.status_loading)
        binding.tvModelSubtitle.text = "• Select Model"
    }

    private fun scanLocalTmp() {
        binding.pbModelCopy.visibility = View.VISIBLE
        binding.tvCopyProgress.visibility = View.VISIBLE
        binding.tvCopyProgress.text = "Checking /data/local/tmp/..."

        lifecycleScope.launch {
            val result = ModelFileManager.copyFromLocalTmp(this@MainActivity) { percent, copied, total ->
                binding.pbModelCopy.isIndeterminate = percent < 0
                if (percent >= 0) {
                    binding.pbModelCopy.progress = percent
                    val mbCopied = copied / (1024 * 1024)
                    val mbTotal = total / (1024 * 1024)
                    binding.tvCopyProgress.text = "Importing: $mbCopied / $mbTotal MB ($percent%)"
                }
            }

            binding.pbModelCopy.visibility = View.GONE
            binding.tvCopyProgress.visibility = View.GONE

            result.onSuccess { file ->
                Toast.makeText(this@MainActivity, "Model imported into app storage!", Toast.LENGTH_SHORT).show()
                initializeEngineWithModel(file)
            }.onFailure { error ->
                Toast.makeText(this@MainActivity, error.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importModelFromUri(uri: Uri) {
        binding.pbModelCopy.visibility = View.VISIBLE
        binding.tvCopyProgress.visibility = View.VISIBLE
        binding.tvCopyProgress.text = "Importing model file into app storage..."

        lifecycleScope.launch {
            val result = ModelFileManager.copyFromUri(this@MainActivity, uri) { percent, copied, total ->
                binding.pbModelCopy.isIndeterminate = percent < 0
                if (percent >= 0) {
                    binding.pbModelCopy.progress = percent
                    val mbCopied = copied / (1024 * 1024)
                    val mbTotal = total / (1024 * 1024)
                    binding.tvCopyProgress.text = "Importing: $mbCopied / $mbTotal MB ($percent%)"
                }
            }

            binding.pbModelCopy.visibility = View.GONE
            binding.tvCopyProgress.visibility = View.GONE

            result.onSuccess { file ->
                Toast.makeText(this@MainActivity, "Model imported into app storage!", Toast.LENGTH_SHORT).show()
                initializeEngineWithModel(file)
            }.onFailure { error ->
                Toast.makeText(this@MainActivity, "Import failed: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateEngineStatus(statusText: String, indicatorColorRes: Int) {
        binding.tvEngineStatus.text = statusText
        binding.statusIndicatorDot.backgroundTintList = ContextCompat.getColorStateList(this, indicatorColorRes)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            aiEngine.mobileAI.cameraManager.stopCamera()
            aiEngine.mobileAI.audioManager.release()
            aiEngine.mobileAI.screenManager.stopScreenStream()
            aiEngine.mobileAI.deviceAgent.cancel()
        } catch (ignored: Exception) {}
        aiEngine.close()
    }
}
