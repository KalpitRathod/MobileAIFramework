package com.mobileai.framework

import com.mobileai.framework.hardware.ThermalLevel
import com.mobileai.framework.multimodal.ImagePayload
import com.mobileai.framework.multimodal.MultimodalContext
import com.mobileai.framework.router.DeviceStateProfile
import com.mobileai.framework.router.HybridRouter
import com.mobileai.framework.router.RouteTarget
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HybridRouterTest {

    private lateinit var router: HybridRouter

    @Before
    fun setUp() {
        router = HybridRouter()
    }

    @Test
    fun testOfflineAlwaysRoutesToEdgeLocal() {
        val offlineProfile = DeviceStateProfile(
            isOnline = false,
            isWifi = false,
            batteryPct = 80,
            isCharging = false,
            thermalStatus = ThermalLevel.NOMINAL,
            availableRamMb = 3000
        )

        val decision = router.route(
            prompt = "Write a complete complex Python async server",
            forcedState = offlineProfile
        )

        assertEquals(RouteTarget.EDGE_LOCAL, decision.target)
        assertTrue(decision.isOffline)
        assertTrue(decision.tokensSaved > 0)
        assertTrue(decision.dollarCostSavedUsd > 0.0)
    }

    @Test
    fun testLowComplexityRoutesToEdgeLocalToSaveTokens() {
        val onlineProfile = DeviceStateProfile(
            isOnline = true,
            isWifi = true,
            batteryPct = 90,
            isCharging = true,
            thermalStatus = ThermalLevel.NOMINAL,
            availableRamMb = 3000
        )

        val decision = router.route(
            prompt = "Hello! Summarize my morning notes.",
            forcedState = onlineProfile
        )

        assertEquals(RouteTarget.EDGE_LOCAL, decision.target)
        assertFalse(decision.isOffline)
        assertTrue(decision.tokensSaved > 0)
        assertEquals("gemma-2-2b-it-q4_k_m.gguf", decision.recommendedModel)
    }

    @Test
    fun testHighComplexityEscalatesToCloudRun() {
        val onlineProfile = DeviceStateProfile(
            isOnline = true,
            isWifi = true,
            batteryPct = 90,
            isCharging = true,
            thermalStatus = ThermalLevel.NOMINAL,
            availableRamMb = 3000
        )

        val decision = router.route(
            prompt = "Derive step by step the mathematical proof of attention mechanism and write the Python PyTorch refactor function.",
            forcedState = onlineProfile
        )

        assertEquals(RouteTarget.CLOUD_RUN, decision.target)
        assertEquals(0, decision.tokensSaved)
        assertEquals("gemini-flash-lite-latest", decision.recommendedModel)
    }

    @Test
    fun testCodingAndCurrentWorldQuestionsEscalateToCloudWhenOnline() {
        val onlineProfile = DeviceStateProfile(
            isOnline = true,
            isWifi = true,
            batteryPct = 85,
            isCharging = false,
            thermalStatus = ThermalLevel.NOMINAL,
            availableRamMb = 3000
        )

        val codeDecision = router.route("Write a Kotlin coroutine example", forcedState = onlineProfile)
        assertEquals(RouteTarget.CLOUD_RUN, codeDecision.target)

        val worldDecision = router.route("What is the weather in Ahmedabad today?", forcedState = onlineProfile)
        assertEquals(RouteTarget.CLOUD_RUN, worldDecision.target)

        val localDecision = router.route("Hello, extract text from my note", forcedState = onlineProfile)
        assertEquals(RouteTarget.EDGE_LOCAL, localDecision.target)
    }

    @Test
    fun testThermalThrottledEscalatesToCloudToCoolDevice() {
        val throttledProfile = DeviceStateProfile(
            isOnline = true,
            isWifi = true,
            batteryPct = 60,
            isCharging = false,
            thermalStatus = ThermalLevel.SEVERE,
            availableRamMb = 2500
        )

        val decision = router.route(
            prompt = "Quick question",
            forcedState = throttledProfile
        )

        assertEquals(RouteTarget.CLOUD_RUN, decision.target)
        assertTrue(decision.reason.contains("thermal", ignoreCase = true))
    }

    @Test
    fun testLiveMultimodalVisionRoutesToGeminiLive() {
        val onlineProfile = DeviceStateProfile(
            isOnline = true,
            isWifi = true,
            batteryPct = 70,
            isCharging = false,
            thermalStatus = ThermalLevel.NOMINAL,
            availableRamMb = 2500
        )

        val context = MultimodalContext(
            cameraImage = ImagePayload(base64Data = "fakeData", mimeType = "image/jpeg")
        )

        val decision = router.route(
            prompt = "Start live camera perception and describe objects",
            multimodalContext = context,
            forcedState = onlineProfile
        )

        assertEquals(RouteTarget.GEMINI_LIVE, decision.target)
        assertEquals("gemini-2.0-flash-realtime", decision.recommendedModel)
    }

    @Test
    fun testLowRamEscalatesToCloudToPreventLmk() {
        val lowRamProfile = DeviceStateProfile(
            isOnline = true,
            isWifi = true,
            batteryPct = 75,
            isCharging = false,
            thermalStatus = ThermalLevel.NOMINAL,
            availableRamMb = 650
        )

        val decision = router.route(
            prompt = "Summarize my notes",
            forcedState = lowRamProfile
        )

        assertEquals(RouteTarget.CLOUD_RUN, decision.target)
        assertTrue(decision.reason.contains("RAM", ignoreCase = true))
    }

    @Test
    fun testInputValidatorRejectsGibberishAndNonDimensionalScans() {
        val validator = com.mobileai.framework.router.InputValidator
        assertFalse(validator.validateGeneralPrompt("asdfghjkl").isValid)
        assertFalse(validator.validateGeneralPrompt("aaaaaaa").isValid)
        assertFalse(validator.validateGeneralPrompt("123456").isValid)
        assertTrue(validator.validateGeneralPrompt("Summarize CNC inspection notes").isValid)

        assertFalse(validator.validateQcScanText("asdfghjkl pizza 123").isValid)
        assertFalse(validator.validateQcScanText("Hello world no numbers").isValid)
        assertTrue(
            validator.validateQcScanText(
                "Project: PRJ-TURB-401 | Part: SHAFT-505 | Nominal: 50.000 ±0.020 mm | Measured: 50.031 mm"
            ).isValid
        )
    }
}
