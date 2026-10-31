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
    private lateinit var knowledgeAdapter: KnowledgeAdapter
    private lateinit var agentStepAdapter: AgentStepAdapter

    private val messagesList = mutableListOf<ChatMessage>()
    private val agentStepsList = mutableListOf<AgentStep>()
    private var currentInferenceJob: Job? = null
    private var isGenerating = false

    // Multimodal & Sensory State
    private var pendingMultimodalContext: MultimodalContext? = null
    private var latestCameraBitmap: Bitmap? = null
    private var latestScreenBitmap: Bitmap? = null
    private var isRecordingAudio = false
    private var isCameraActive = false
    private var isScreenStreamActive = false

    // State
    private var currentTab = Tab.CHAT
    private var currentKnowledgeCategory: String = "All"
    private var selectedModelFile: File? = null

    enum class Tab { CHAT, SENSORY, AGENT, KNOWLEDGE, HARDWARE }

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
                        val payload = ImagePayload.fromBitmap(bitmap, 80)
                        pendingMultimodalContext = (pendingMultimodalContext ?: MultimodalContext()).copy(cameraImage = payload)
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
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (cameraGranted) Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show()
        if (audioGranted) Toast.makeText(this, "Audio permission granted", Toast.LENGTH_SHORT).show()
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
        setupKnowledge()
        setupHardware()
        setupModelControls()

        checkAndInitializeModel()
        updateModelSubtitle()
    }

    // ==========================================
    // NAVIGATION & TAB SWITCHING
    // ==========================================
    private fun setupNavigation() {
        binding.tabBuddy.setOnClickListener { switchTab(Tab.CHAT) }
        binding.tabSensory.setOnClickListener { switchTab(Tab.SENSORY) }
        binding.tabAgent.setOnClickListener { switchTab(Tab.AGENT) }
        binding.tabKnowledge.setOnClickListener { switchTab(Tab.KNOWLEDGE) }
        binding.tabHardware.setOnClickListener { switchTab(Tab.HARDWARE) }
        binding.btnCloudHub.setOnClickListener { showCloudHubDialog() }
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab

        // Toggle Views
        binding.viewBuddy.visibility = if (tab == Tab.CHAT) View.VISIBLE else View.GONE
        binding.viewSensory.visibility = if (tab == Tab.SENSORY) View.VISIBLE else View.GONE
        binding.viewAgent.visibility = if (tab == Tab.AGENT) View.VISIBLE else View.GONE
        binding.viewKnowledge.visibility = if (tab == Tab.KNOWLEDGE) View.VISIBLE else View.GONE
        binding.viewHardware.visibility = if (tab == Tab.HARDWARE) View.VISIBLE else View.GONE

        // Update Tab Colors
        val gold = ContextCompat.getColor(this, R.color.royal_gold)
        val muted = ContextCompat.getColor(this, R.color.text_muted)

        binding.ivTabBuddy.setColorFilter(if (tab == Tab.CHAT) gold else muted)
        binding.tvTabBuddy.setTextColor(if (tab == Tab.CHAT) gold else muted)

        binding.ivTabSensory.setColorFilter(if (tab == Tab.SENSORY) gold else muted)
        binding.tvTabSensory.setTextColor(if (tab == Tab.SENSORY) gold else muted)

        binding.ivTabAgent.setColorFilter(if (tab == Tab.AGENT) gold else muted)
        binding.tvTabAgent.setTextColor(if (tab == Tab.AGENT) gold else muted)

        binding.ivTabKnowledge.setColorFilter(if (tab == Tab.KNOWLEDGE) gold else muted)
        binding.tvTabKnowledge.setTextColor(if (tab == Tab.KNOWLEDGE) gold else muted)

        binding.ivTabHardware.setColorFilter(if (tab == Tab.HARDWARE) gold else muted)
        binding.tvTabHardware.setTextColor(if (tab == Tab.HARDWARE) gold else muted)

        // Refresh data on tab switch
        when (tab) {
            Tab.KNOWLEDGE -> loadKnowledgeEntries()
            Tab.HARDWARE -> refreshHardwareDiagnostics()
            Tab.AGENT -> updateAccessibilityStatus()
            Tab.SENSORY -> checkPermissions()
            Tab.CHAT -> { /* Keep chat as is */ }
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
                            switchTab(Tab.SENSORY)
                        }
                    } else {
                        imagePickerLauncher.launch("image/*")
                    }
                }
                .show()
        }

        binding.btnChatMic.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                return@setOnClickListener
            }
            switchTab(Tab.SENSORY)
        }

        binding.btnChatScreen.setOnClickListener {
            switchTab(Tab.SENSORY)
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
    }

    private fun showAttachmentBanner(text: String) {
        binding.layoutAttachmentPreview.visibility = View.VISIBLE
        binding.tvAttachmentSummary.text = text
    }

    private fun sendChatMessage() {
        val prompt = binding.etBuddyInput.text?.toString()?.trim() ?: ""
        if (prompt.isEmpty() && pendingMultimodalContext == null) return

        val contextPayload = pendingMultimodalContext
        pendingMultimodalContext = null
        binding.layoutAttachmentPreview.visibility = View.GONE

        val cloudStore = aiEngine.mobileAI.cloudApiKeyStore
        val hasActiveCloudKey = cloudStore.getApiKey(cloudStore.getActiveProvider()).isNotBlank()
        val isMultimodalVisual = contextPayload?.cameraImage != null || contextPayload?.screenCapture != null

        // Auto-route to cloud vision if visual frame attached AND cloud is preferred
        val useCloud = cloudStore.isCloudPreferred() ||
                (!aiEngine.isInitialized && hasActiveCloudKey && !isMultimodalVisual)

        if (!useCloud && !aiEngine.isInitialized && !isMultimodalVisual) {
            Toast.makeText(this, "Please select an offline model or configure Cloud AI Hub.", Toast.LENGTH_SHORT).show()
            binding.modelSetupCard.visibility = View.VISIBLE
            return
        }

        val effectivePrompt = if (prompt.isBlank() && isMultimodalVisual) "Convert to text" else prompt

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
        binding.rvBuddyMessages.smoothScrollToPosition(chatAdapter.itemCount - 1)

        val botMsg = ChatMessage(text = "", isUser = false, isGenerating = true)
        chatAdapter.addMessage(botMsg)
        binding.rvBuddyMessages.smoothScrollToPosition(chatAdapter.itemCount - 1)

        setGeneratingState(true)

        currentInferenceJob = lifecycleScope.launch {
            if (useCloud) {
                val config = cloudStore.getConfig()
                val streamFlow = aiEngine.mobileAI.cloudClient.generateStream(effectivePrompt, contextPayload, config)

                streamFlow.catch { e ->
                    chatAdapter.streamChunk(
                        accumulatedText = "⚠️ Cloud Error: ${e.localizedMessage ?: e.message}",
                        tokPerSec = 0f,
                        tokenCount = 0,
                        isFinished = true,
                        finalStats = "Cloud Error"
                    )
                    setGeneratingState(false)
                }.collect { chunk ->
                    chatAdapter.streamChunk(
                        accumulatedText = chunk.text,
                        tokPerSec = chunk.tokensPerSecond,
                        tokenCount = chunk.tokensGenerated,
                        isFinished = chunk.isFinished,
                        finalStats = if (chunk.isFinished) chunk.telemetrySummary else null
                    )
                    binding.rvBuddyMessages.smoothScrollToPosition(chatAdapter.itemCount - 1)

                    if (chunk.isFinished) {
                        val completedMsg = ChatMessage(
                            text = chunk.text,
                            isUser = false,
                            isGenerating = false,
                            statsInfo = chunk.telemetrySummary
                        )
                        dbHelper.insertChatMessage(completedMsg)
                        setGeneratingState(false)
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
                    binding.rvBuddyMessages.smoothScrollToPosition(chatAdapter.itemCount - 1)

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
                    chatAdapter.streamChunk(
                        accumulatedText = chunk.text,
                        tokPerSec = chunk.tokensPerSecond,
                        tokenCount = chunk.tokensGenerated,
                        isFinished = chunk.isFinished,
                        finalStats = if (chunk.isFinished) chunk.telemetrySummary else null
                    )
                    binding.rvBuddyMessages.smoothScrollToPosition(chatAdapter.itemCount - 1)

                    if (chunk.isFinished) {
                        val completedMsg = ChatMessage(
                            text = chunk.text,
                            isUser = false,
                            isGenerating = false,
                            statsInfo = chunk.telemetrySummary
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
    // 2. SENSORY: CAMERA, AUDIO & SCREEN STREAM
    // ==========================================
    private fun checkPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun setupSensory() {
        binding.btnToggleCameraFeed.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                return@setOnClickListener
            }

            if (isCameraActive) {
                aiEngine.mobileAI.cameraManager.stopCamera()
                isCameraActive = false
                binding.btnToggleCameraFeed.text = "Start Camera"
                binding.tvSensoryVisionStatus.text = "Idle"
            } else {
                aiEngine.mobileAI.cameraManager.startCamera(binding.cameraTextureView, object : CameraVisionManager.FrameListener {
                    override fun onFrameSampled(bitmap: Bitmap, imagePayload: ImagePayload) {
                        latestCameraBitmap = bitmap
                    }

                    override fun onError(message: String) {
                        Toast.makeText(this@MainActivity, "Camera: $message", Toast.LENGTH_SHORT).show()
                    }
                })
                isCameraActive = true
                binding.btnToggleCameraFeed.text = "Stop Camera"
                binding.tvSensoryVisionStatus.text = "Streaming"
            }
        }

        binding.btnFlipCameraFacing.setOnClickListener {
            if (isCameraActive) {
                aiEngine.mobileAI.cameraManager.switchCamera(binding.cameraTextureView)
            }
        }

        binding.btnSnapCameraToChat.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                return@setOnClickListener
            }

            aiEngine.mobileAI.cameraManager.captureSnapshot({ bitmap, payload ->
                latestCameraBitmap = bitmap
                pendingMultimodalContext = (pendingMultimodalContext ?: MultimodalContext()).copy(cameraImage = payload)
                showAttachmentBanner("📷 Camera snapshot attached")
                if (binding.etBuddyInput.text.isNullOrBlank()) {
                    binding.etBuddyInput.setText("Convert to text")
                }
                switchTab(Tab.CHAT)
            }, { err ->
                Toast.makeText(this, "Capture error: $err", Toast.LENGTH_SHORT).show()
            })
        }

        binding.btnSensoryRecordMic.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                return@setOnClickListener
            }

            if (isRecordingAudio) {
                aiEngine.mobileAI.audioManager.stopLiveRecording()
                isRecordingAudio = false
                binding.btnSensoryRecordMic.text = "Record Audio"
            } else {
                aiEngine.mobileAI.audioManager.startLiveRecording(object : AudioInputManager.AudioStreamListener {
                    override fun onAmplitudeUpdate(rmsNormalized: Float) {
                        binding.pbAudioWaveform.progress = (rmsNormalized * 100).toInt()
                    }

                    override fun onRecordingStarted() {
                        isRecordingAudio = true
                        binding.btnSensoryRecordMic.text = "Stop Recording"
                        Toast.makeText(this@MainActivity, "Recording live audio...", Toast.LENGTH_SHORT).show()
                    }

                    override fun onRecordingFinished(wavBytes: ByteArray, durationMs: Long) {
                        isRecordingAudio = false
                        binding.btnSensoryRecordMic.text = "Record Audio"
                        val payload = AudioPayload.fromBytes(wavBytes, "audio/wav", durationMs)
                        pendingMultimodalContext = (pendingMultimodalContext ?: MultimodalContext()).copy(audioClip = payload)
                        showAttachmentBanner("🎙️ Audio clip attached (${durationMs / 1000}s)")
                        switchTab(Tab.CHAT)
                    }

                    override fun onError(message: String) {
                        isRecordingAudio = false
                        binding.btnSensoryRecordMic.text = "Record Audio"
                        Toast.makeText(this@MainActivity, "Audio error: $message", Toast.LENGTH_SHORT).show()
                    }
                })
            }
        }

        binding.btnSensorySpeechToText.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                return@setOnClickListener
            }

            aiEngine.mobileAI.audioManager.startSpeechToText(object : AudioInputManager.SpeechRecognitionListener {
                override fun onPartialTranscript(partialText: String) {
                    binding.tvSensoryAudioTranscript.text = partialText
                }

                override fun onFinalTranscript(text: String) {
                    binding.tvSensoryAudioTranscript.text = text
                    pendingMultimodalContext = (pendingMultimodalContext ?: MultimodalContext()).copy(audioTranscript = text)
                    showAttachmentBanner("📝 Speech transcript attached: \"${text.take(30)}...\"")
                }

                override fun onError(error: String) {
                    Toast.makeText(this@MainActivity, "STT error: $error", Toast.LENGTH_SHORT).show()
                }
            })
        }

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
    // 4. KNOWLEDGE: ON-DEVICE VECTOR DATABASE & RAG
    // ==========================================
    private fun setupKnowledge() {
        knowledgeAdapter = KnowledgeAdapter(
            entries = mutableListOf(),
            onQueryRagClick = { entry ->
                queryKnowledgeWithRag(entry)
            },
            onDeleteClick = { entry ->
                deleteKnowledgeEntry(entry)
            }
        )

        binding.rvKnowledgeEntries.layoutManager = LinearLayoutManager(this)
        binding.rvKnowledgeEntries.adapter = knowledgeAdapter

        // Category Filter Chips
        val catChips = listOf(
            Pair(binding.filterCatAll, "All"),
            Pair(binding.filterCatHardware, "Hardware"),
            Pair(binding.filterCatInference, "Inference"),
            Pair(binding.filterCatNpu, "NPU"),
            Pair(binding.filterCatAgents, "Agents"),
            Pair(binding.filterCatRAG, "RAG"),
            Pair(binding.filterCatCloud, "Cloud")
        )

        for ((view, cat) in catChips) {
            view.setOnClickListener {
                currentKnowledgeCategory = cat
                for ((v, c) in catChips) {
                    if (c == cat) {
                        v.setBackgroundResource(R.drawable.bg_card_boxy_gold)
                        v.setTextColor(ContextCompat.getColor(this, R.color.royal_gold))
                    } else {
                        v.setBackgroundResource(R.drawable.bg_tag_boxy)
                        v.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                    }
                }
                loadKnowledgeEntries()
            }
        }

        // Live Search
        binding.etKnowledgeSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                loadKnowledgeEntries(s?.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Re-index Vector DB button
        binding.btnReindexVectors.setOnClickListener {
            reindexKnowledgeVectors()
        }

        // Add Knowledge FAB
        binding.fabNewKnowledge.setOnClickListener {
            showNewKnowledgeDialog()
        }
    }

    private fun loadKnowledgeEntries(query: String? = binding.etKnowledgeSearch.text?.toString()) {
        val entries = dbHelper.getAllKnowledge(query = query, category = currentKnowledgeCategory)
        knowledgeAdapter.updateList(entries)
        binding.tvKnowledgeCountBadge.text = "${entries.size} Knowledge Docs (SQLite Vector Store)"
    }

    private fun reindexKnowledgeVectors() {
        lifecycleScope.launch {
            val allEntries = dbHelper.getAllKnowledge()
            val pairs = allEntries.map { it.id.toString() to "${it.title}. ${it.summary}. ${it.content}" }

            Toast.makeText(this@MainActivity, "Indexing ${pairs.size} docs into vector store...", Toast.LENGTH_SHORT).show()
            binding.tvKnowledgeCountBadge.text = "Indexing vectors..."

            val count = aiEngine.indexKnowledgeEntries(pairs)
            for (entry in allEntries) {
                dbHelper.markKnowledgeEmbedded(entry.id)
            }
            binding.tvKnowledgeCountBadge.text = "$count Vectors Active in On-Device DB"
            Toast.makeText(this@MainActivity, "Successfully indexed $count entries into vector database!", Toast.LENGTH_SHORT).show()
            loadKnowledgeEntries()
        }
    }

    private fun queryKnowledgeWithRag(entry: KnowledgeEntry) {
        val ragPrompt = "Explain and summarize the technical implementation of: ${entry.title}\nContext: ${entry.content}"
        binding.etBuddyInput.setText(ragPrompt)
        binding.etBuddyInput.setSelection(ragPrompt.length)
        switchTab(Tab.CHAT)
        sendChatMessage()
    }

    private fun deleteKnowledgeEntry(entry: KnowledgeEntry) {
        dbHelper.deleteKnowledge(entry.id)
        loadKnowledgeEntries()
        Toast.makeText(this, "Deleted: ${entry.title}", Toast.LENGTH_SHORT).show()
    }

    private fun showNewKnowledgeDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_new_knowledge)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etTitle = dialog.findViewById<EditText>(R.id.etNewKnowledgeTitle)
        val etSummary = dialog.findViewById<EditText>(R.id.etNewKnowledgeSummary)
        val etContent = dialog.findViewById<EditText>(R.id.etNewKnowledgeContent)
        val btnClose = dialog.findViewById<ImageView>(R.id.btnDialogClose)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelKnowledge)
        val btnSave = dialog.findViewById<Button>(R.id.btnSaveKnowledge)

        var selectedCategory = "Hardware"
        val catViews = listOf(
            Pair(dialog.findViewById<TextView>(R.id.catOptHardware), "Hardware"),
            Pair(dialog.findViewById<TextView>(R.id.catOptInference), "Inference"),
            Pair(dialog.findViewById<TextView>(R.id.catOptNpu), "NPU"),
            Pair(dialog.findViewById<TextView>(R.id.catOptAgents), "Agents"),
            Pair(dialog.findViewById<TextView>(R.id.catOptRAG), "RAG"),
            Pair(dialog.findViewById<TextView>(R.id.catOptCloud), "Cloud")
        )

        for ((v, cat) in catViews) {
            v.setOnClickListener {
                selectedCategory = cat
                for ((view, c) in catViews) {
                    if (c == cat) {
                        view.setBackgroundResource(R.drawable.bg_card_boxy_gold)
                        view.setTextColor(ContextCompat.getColor(this, R.color.royal_gold))
                    } else {
                        view.setBackgroundResource(R.drawable.bg_tag_boxy)
                        view.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                    }
                }
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val title = etTitle.text.toString().trim()
            val summary = etSummary.text.toString().trim()
            val content = etContent.text.toString().trim()

            if (title.isEmpty() || content.isEmpty()) {
                Toast.makeText(this, "Title and Content are required.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            val newEntry = KnowledgeEntry(
                title = title,
                category = selectedCategory,
                summary = if (summary.isNotEmpty()) summary else title,
                content = content,
                timestamp = dateFormat.format(Date()),
                isEmbedded = false
            )

            val id = dbHelper.insertKnowledge(newEntry)
            dialog.dismiss()
            loadKnowledgeEntries()
            Toast.makeText(this, "Vector Doc #$id added to database!", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    // ==========================================
    // 5. HARDWARE & NPU DIAGNOSTICS VIEW
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
            binding.tvModelSubtitle.text = "$model • ${provider.displayName}"
            updateEngineStatus("Cloud Active", R.color.royal_blue_light)
        } else if (aiEngine.isInitialized) {
            binding.tvModelSubtitle.text = "${aiEngine.currentModelName} • Local Engine"
            updateEngineStatus("Local Ready", R.color.status_ready)
        } else {
            binding.tvModelSubtitle.text = "No Model Loaded"
            updateEngineStatus("Select Model", R.color.status_loading)
        }
    }

    // ==========================================
    // 7. MODEL CONTROLS & SWITCHING
    // ==========================================
    private fun setupModelControls() {
        binding.statusPill.setOnClickListener {
            switchTab(Tab.HARDWARE)
        }

        binding.btnSwitchModel.setOnClickListener {
            showModelSelectionDialog()
        }

        binding.tvModelSubtitle.setOnClickListener {
            showModelSelectionDialog()
        }

        binding.btnImportModel.setOnClickListener {
            modelPickerLauncher.launch("*/*")
        }

        binding.btnScanTmp.setOnClickListener {
            scanLocalTmp()
        }

        binding.btnDownloadModel.setOnClickListener {
            downloadSmolLmModel()
        }

        binding.btnSetupCloudHub.setOnClickListener {
            showCloudHubDialog()
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
            showModelMissingState()
        }
    }

    private fun showVisionProjectorDetectedState(projectorFile: File) {
        val sizeMb = projectorFile.length() / (1024 * 1024)
        updateEngineStatus("Projector Only", R.color.status_loading)
        binding.modelSetupCard.visibility = View.VISIBLE
        binding.tvModelSetupTitle.text = "👁️ Vision Projector Detected"
        binding.tvModelPromptInstructions.text =
            "Found vision projector '${projectorFile.name}' (${sizeMb}MB).\n\n" +
            "This file is a multimodal vision projection adapter (for OCR / vision reasoning). It requires a companion base language model GGUF (such as SmolLM2-360M, Qwen2.5-0.5B, Gemma-2-2B, or GLM-Edge) to generate text responses.\n\n" +
            "Tap '⚡ Auto-Download SmolLM2' below to automatically fetch a lightweight 258MB base model over Wi-Fi, select a file from storage, or use Cloud AI Hub in the meantime."
    }

    private fun downloadSmolLmModel() {
        binding.pbModelCopy.visibility = View.VISIBLE
        binding.tvCopyProgress.visibility = View.VISIBLE
        binding.btnDownloadModel.isEnabled = false
        binding.btnDownloadModel.text = "Downloading..."

        lifecycleScope.launch {
            val result = ModelFileManager.downloadModel(
                context = this@MainActivity,
                modelUrl = ModelFileManager.URL_SMOLLM_360M,
                destFileName = ModelFileManager.DEFAULT_SMOLLM_360M
            ) { percent, copied, total ->
                binding.pbModelCopy.isIndeterminate = percent < 0
                if (percent >= 0) {
                    binding.pbModelCopy.progress = percent
                    val mbCopied = copied / (1024 * 1024)
                    val mbTotal = total / (1024 * 1024)
                    binding.tvCopyProgress.text = "Downloading SmolLM2: $mbCopied / $mbTotal MB ($percent%)"
                }
            }

            binding.pbModelCopy.visibility = View.GONE
            binding.tvCopyProgress.visibility = View.GONE
            binding.btnDownloadModel.isEnabled = true
            binding.btnDownloadModel.text = "⚡ Auto-Download SmolLM2"

            result.onSuccess { file ->
                Toast.makeText(this@MainActivity, "SmolLM2-360M downloaded successfully!", Toast.LENGTH_SHORT).show()
                initializeEngineWithModel(file)
            }.onFailure { error ->
                Toast.makeText(this@MainActivity, "Download failed: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                binding.tvModelPromptInstructions.text = "Download error: ${error.localizedMessage}\nYou can select a model file from storage or use Cloud AI Hub."
            }
        }
    }

    private fun initializeEngineWithModel(modelFile: File) {
        selectedModelFile = modelFile
        val isGguf = modelFile.name.lowercase().endsWith(".gguf")

        val budget = aiEngine.evaluateMemoryBudget(modelFile)
        val engineLabel = when {
            !isGguf -> "MediaPipe GPU"
            modelFile.name.contains("gemma", ignoreCase = true) -> "Gemma-2 GGUF"
            modelFile.name.contains("smollm", ignoreCase = true) -> "SmolLM2 GGUF"
            modelFile.name.contains("qwen", ignoreCase = true) -> "Qwen GGUF"
            else -> "llama.cpp GGUF"
        }

        if (!budget.isSafeToLoad) {
            Toast.makeText(this, "⚠️ Low RAM Warning: ${budget.advisoryNote}", Toast.LENGTH_LONG).show()
        }

        updateEngineStatus("Loading...", R.color.status_loading)
        binding.modelSetupCard.visibility = View.GONE

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
                binding.tvModelSubtitle.text = "$engineLabel • ${sizeMb}MB • $threadInfo"
                updateEngineStatus("Ready", R.color.status_ready)
                binding.modelSetupCard.visibility = View.GONE
                Toast.makeText(this@MainActivity, "$engineDesc ready for inference!", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                updateEngineStatus("Init Failed", R.color.status_error)
                binding.modelSetupCard.visibility = View.VISIBLE
                binding.tvModelPromptInstructions.text = "Initialization error: ${error.localizedMessage}"
            }
        }
    }

    private fun showModelSelectionDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_model_select)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val layoutList = dialog.findViewById<LinearLayout>(R.id.layoutModelsList)
        val btnImport = dialog.findViewById<Button>(R.id.btnImportNewModel)

        val installedModels = ModelFileManager.getInstalledModels(this)

        if (installedModels.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "No models currently in app private storage.\nTap '+ Import' below or select from storage."
                setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                textSize = 12f
                setPadding(0, 16, 0, 16)
            }
            layoutList.addView(tvEmpty)
        } else {
            val (projectors, baseModels) = installedModels.partition { ModelFileManager.isVisionProjector(it.name) }

            // Section 1: Base Language Models
            if (baseModels.isNotEmpty()) {
                val tvSectionLlms = TextView(this).apply {
                    text = "ACTIVE LANGUAGE MODELS"
                    setTextColor(ContextCompat.getColor(context, R.color.royal_gold))
                    textSize = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(4, 8, 4, 8)
                }
                layoutList.addView(tvSectionLlms)

                for (file in baseModels) {
                    val sizeMb = file.length() / (1024 * 1024)
                    val isSelected = selectedModelFile?.absolutePath == file.absolutePath

                    val itemLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 6, 0, 6) }
                        setBackgroundResource(if (isSelected) R.drawable.bg_card_boxy_gold else R.drawable.bg_card_boxy)
                        setPadding(16, 14, 16, 14)
                        isClickable = true
                        isFocusable = true
                    }

                    val infoLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val tvName = TextView(this).apply {
                        text = file.name
                        setTextColor(ContextCompat.getColor(context, if (isSelected) R.color.royal_gold else R.color.text_primary))
                        textSize = 13f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }

                    val tvInfo = TextView(this).apply {
                        val budgetNote = if (!aiEngine.evaluateMemoryBudget(file).isSafeToLoad) "⚠️ Low RAM" else "✓ RAM OK"
                        text = "${sizeMb}MB • ${if (file.name.endsWith(".gguf")) "llama.cpp CPU" else "MediaPipe"} • $budgetNote"
                        setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                        textSize = 11f
                    }

                    infoLayout.addView(tvName)
                    infoLayout.addView(tvInfo)
                    itemLayout.addView(infoLayout)

                    if (isSelected) {
                        val tvActive = TextView(this).apply {
                            text = "ACTIVE"
                            setTextColor(ContextCompat.getColor(context, R.color.royal_gold))
                            textSize = 10f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setBackgroundResource(R.drawable.bg_tag_boxy)
                            setPadding(8, 4, 8, 4)
                        }
                        itemLayout.addView(tvActive)
                    }

                    itemLayout.setOnClickListener {
                        dialog.dismiss()
                        initializeEngineWithModel(file)
                    }

                    layoutList.addView(itemLayout)
                }
            }

            // Section 2: Multimodal Vision & OCR Projectors
            if (projectors.isNotEmpty()) {
                val tvSectionProj = TextView(this).apply {
                    text = "ATTACHED VISION & OCR PROJECTORS"
                    setTextColor(ContextCompat.getColor(context, R.color.royal_blue_light))
                    textSize = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(4, 16, 4, 8)
                }
                layoutList.addView(tvSectionProj)

                for (file in projectors) {
                    val sizeMb = file.length() / (1024 * 1024)

                    val itemLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 6, 0, 6) }
                        setBackgroundResource(R.drawable.bg_card_boxy)
                        setPadding(16, 14, 16, 14)
                        isClickable = true
                        isFocusable = true
                    }

                    val infoLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val tvName = TextView(this).apply {
                        text = file.name
                        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                        textSize = 13f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }

                    val tvInfo = TextView(this).apply {
                        text = "${sizeMb}MB • Multimodal Vision / OCR Adapter (Attached ✓)"
                        setTextColor(ContextCompat.getColor(context, R.color.royal_blue_light))
                        textSize = 11f
                    }

                    infoLayout.addView(tvName)
                    infoLayout.addView(tvInfo)
                    itemLayout.addView(infoLayout)

                    val tvProj = TextView(this).apply {
                        text = "ATTACHED"
                        setTextColor(ContextCompat.getColor(context, R.color.status_ready))
                        textSize = 9f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setBackgroundResource(R.drawable.bg_tag_boxy)
                        setPadding(6, 3, 6, 3)
                    }
                    itemLayout.addView(tvProj)

                    itemLayout.setOnClickListener {
                        Toast.makeText(
                            this@MainActivity,
                            "👁️ '${file.name}' is attached as your active vision/OCR projector adapter.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    layoutList.addView(itemLayout)
                }
            }
        }

        btnImport.setOnClickListener {
            dialog.dismiss()
            modelPickerLauncher.launch("*/*")
        }

        dialog.show()
    }

    private fun showModelMissingState() {
        updateEngineStatus("No Model", R.color.status_error)
        binding.modelSetupCard.visibility = View.VISIBLE
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
                binding.tvModelPromptInstructions.text = error.message
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
