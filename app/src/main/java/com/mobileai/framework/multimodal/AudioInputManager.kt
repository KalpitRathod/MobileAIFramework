package com.mobileai.framework.multimodal

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Manages live audio recording, real-time waveform RMS monitoring,
 * WAV serialization, and offline speech-to-text recognition.
 */
class AudioInputManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioInputManager"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // SpeechRecognizer for real-time speech-to-text
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    interface AudioStreamListener {
        fun onAmplitudeUpdate(rmsNormalized: Float) // 0.0 to 1.0 for visualizer
        fun onRecordingStarted()
        fun onRecordingFinished(wavBytes: ByteArray, durationMs: Long)
        fun onError(message: String)
    }

    interface SpeechRecognitionListener {
        fun onPartialTranscript(partialText: String)
        fun onFinalTranscript(text: String)
        fun onError(error: String)
    }

    /**
     * Start live microphone recording with real-time waveform amplitude callback.
     */
    fun startLiveRecording(listener: AudioStreamListener) {
        if (isRecording) {
            listener.onError("Already recording")
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize <= 0) {
            listener.onError("Invalid buffer size for audio hardware")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                listener.onError("Failed to initialize AudioRecord hardware")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            listener.onRecordingStarted()

            recordingJob = scope.launch {
                val pcmOutputStream = ByteArrayOutputStream()
                val readBuffer = ShortArray(bufferSize / 2)
                val startTime = System.currentTimeMillis()

                try {
                    while (isRecording && isActive) {
                        val shortsRead = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: -1
                        if (shortsRead > 0) {
                            // Calculate RMS amplitude for live VU meter / waveform
                            var sum = 0.0
                            for (i in 0 until shortsRead) {
                                val s = readBuffer[i].toDouble()
                                sum += s * s
                            }
                            val rms = sqrt(sum / shortsRead)
                            val normalizedRms = (rms / 32767.0).toFloat().coerceIn(0f, 1f)
                            
                            withContext(Dispatchers.Main) {
                                listener.onAmplitudeUpdate(normalizedRms)
                            }

                            // Convert short array to PCM byte array
                            val byteBuffer = ByteBuffer.allocate(shortsRead * 2).order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until shortsRead) {
                                byteBuffer.putShort(readBuffer[i])
                            }
                            pcmOutputStream.write(byteBuffer.array())
                        }
                    }

                    val durationMs = System.currentTimeMillis() - startTime
                    val pcmBytes = pcmOutputStream.toByteArray()
                    val wavBytes = addWavHeader(pcmBytes, SAMPLE_RATE, 1, 16)

                    withContext(Dispatchers.Main) {
                        listener.onRecordingFinished(wavBytes, durationMs)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Audio recording stream error", e)
                    withContext(Dispatchers.Main) {
                        listener.onError("Recording error: ${e.message}")
                    }
                } finally {
                    try {
                        audioRecord?.stop()
                        audioRecord?.release()
                    } catch (ignored: Exception) {}
                    audioRecord = null
                    isRecording = false
                }
            }
        } catch (e: SecurityException) {
            listener.onError("RECORD_AUDIO permission not granted")
        } catch (e: Exception) {
            listener.onError("Failed to start audio: ${e.message}")
        }
    }

    /**
     * Stop the live audio recording.
     */
    fun stopLiveRecording() {
        isRecording = false
        recordingJob?.cancel()
    }

    fun isRecordingActive(): Boolean = isRecording

    /**
     * Start live speech-to-text recognition using Android's speech service.
     */
    fun startSpeechToText(listener: SpeechRecognitionListener) {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                listener.onError("Speech recognition not available on this device")
                return@post
            }

            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            val errorMsg = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                                SpeechRecognizer.ERROR_NETWORK -> "Network required for online speech recognizer"
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Audio permission required"
                                else -> "Speech recognition error code: $error"
                            }
                            listener.onError(errorMsg)
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull() ?: ""
                            listener.onFinalTranscript(text)
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull() ?: ""
                            if (text.isNotEmpty()) {
                                listener.onPartialTranscript(text)
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }

                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                listener.onError("Failed to start speech recognizer: ${e.message}")
            }
        }
    }

    /**
     * Stop speech recognizer.
     */
    fun stopSpeechToText() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
            } catch (ignored: Exception) {}
            speechRecognizer = null
        }
    }

    /**
     * Encodes raw PCM bytes to a standard 44-byte WAV header container.
     */
    fun addWavHeader(pcmData: ByteArray, sampleRate: Int, channels: Int, bitDepth: Int): ByteArray {
        val totalAudioLen = pcmData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitDepth / 8).toLong()

        val header = ByteArray(44)
        // RIFF chunk descriptor
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        // WAVE header
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        // fmt subchunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 16 for PCM
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // Audio format 1 = PCM
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitDepth / 8).toByte() // block align
        header[33] = 0
        header[34] = bitDepth.toByte() // bits per sample
        header[35] = 0
        // data subchunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        val wavBuffer = ByteArray(44 + pcmData.size)
        System.arraycopy(header, 0, wavBuffer, 0, 44)
        System.arraycopy(pcmData, 0, wavBuffer, 44, pcmData.size)
        return wavBuffer
    }

    fun release() {
        stopLiveRecording()
        stopSpeechToText()
        scope.cancel()
    }
}

