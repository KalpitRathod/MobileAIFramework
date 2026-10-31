package com.mobileai.framework

import com.mobileai.framework.multimodal.AudioPayload
import com.mobileai.framework.multimodal.ImagePayload
import com.mobileai.framework.multimodal.MultimodalContext
import com.mobileai.framework.multimodal.ScreenPayload
import org.junit.Assert.*
import org.junit.Test

class MultimodalContextTest {

    @Test
    fun testEmptyContextIsNotMultimodal() {
        val ctx = MultimodalContext(textPrompt = "Hello world")
        assertFalse(ctx.hasVisual)
        assertFalse(ctx.hasAudio)
        assertFalse(ctx.isMultimodal)
    }

    @Test
    fun testCameraPayloadActivatesVisualFlag() {
        val img = ImagePayload(base64Data = "iVBORw0KGgoAAAANSUhEUg==", mimeType = "image/jpeg", width = 640, height = 480)
        val ctx = MultimodalContext(textPrompt = "What is in front of me?", cameraImage = img)

        assertTrue(ctx.hasVisual)
        assertFalse(ctx.hasAudio)
        assertTrue(ctx.isMultimodal)
    }

    @Test
    fun testScreenPayloadActivatesVisualFlag() {
        val img = ImagePayload(base64Data = "dGVzdA==", mimeType = "image/jpeg")
        val screen = ScreenPayload(image = img, displayWidth = 1080, displayHeight = 2400, orientation = 1)
        val ctx = MultimodalContext(textPrompt = "Inspect screen", screenCapture = screen)

        assertTrue(ctx.hasVisual)
        assertTrue(ctx.isMultimodal)
    }

    @Test
    fun testAudioPayloadActivatesAudioFlag() {
        val audio = AudioPayload(base64Data = "UklGRg==", mimeType = "audio/wav", durationMs = 2500)
        val ctx = MultimodalContext(textPrompt = "Listen", audioClip = audio)

        assertFalse(ctx.hasVisual)
        assertTrue(ctx.hasAudio)
        assertTrue(ctx.isMultimodal)
    }

    @Test
    fun testTranscriptActivatesAudioFlag() {
        val ctx = MultimodalContext(textPrompt = "Answer question", audioTranscript = "Explain Schrödinger's cat")

        assertTrue(ctx.hasAudio)
        assertTrue(ctx.isMultimodal)
    }
}

