package com.mobileai.framework

import com.mobileai.framework.cloud.CloudProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudAiClientTest {

    @Test
    fun testProviderProperties() {
        assertTrue("Mistral AI should be marked as European", CloudProvider.MISTRAL_AI.isEuropean)
        assertTrue("Custom European should be marked as European", CloudProvider.CUSTOM_EUROPEAN.isEuropean)
        assertFalse("Gemini is not European", CloudProvider.GEMINI.isEuropean)
        assertFalse("Claude is not European", CloudProvider.CLAUDE.isEuropean)

        assertEquals("Google Gemini", CloudProvider.GEMINI.displayName)
        assertEquals("Anthropic Claude", CloudProvider.CLAUDE.displayName)
        assertEquals("DeepSeek (V3 / R1)", CloudProvider.DEEPSEEK.displayName)
        assertEquals("xAI (Grok)", CloudProvider.XAI_GROK.displayName)
        assertEquals("Mistral AI (European Leader)", CloudProvider.MISTRAL_AI.displayName)
    }

    @Test
    fun testOpenAiCompatibleJsonPayloadStructure() {
        val root = JSONObject()
        root.put("model", "mistral-large-latest")
        root.put("stream", true)
        root.put("temperature", 0.7)

        val messages = JSONArray()
        val userMsg = JSONObject()
        userMsg.put("role", "user")
        userMsg.put("content", "Explain general relativity")
        messages.put(userMsg)
        root.put("messages", messages)

        val jsonStr = root.toString()
        val parsed = JSONObject(jsonStr)
        assertEquals("mistral-large-latest", parsed.getString("model"))
        assertTrue(parsed.getBoolean("stream"))
        assertEquals(1, parsed.getJSONArray("messages").length())
        assertEquals("Explain general relativity", parsed.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun testParseOpenAiStreamChunk() {
        val sseData = """{"choices":[{"delta":{"content":"Hello world"}}]}"""
        val json = JSONObject(sseData)
        val choices = json.getJSONArray("choices")
        val delta = choices.getJSONObject(0).getJSONObject("delta")
        val text = delta.getString("content")
        assertEquals("Hello world", text)
    }

    @Test
    fun testParseGeminiStreamChunk() {
        val geminiData = """{"candidates":[{"content":{"parts":[{"text":"Quantum physics"}]}}]}"""
        val json = JSONObject(geminiData)
        val text = json.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        assertEquals("Quantum physics", text)
    }

    @Test
    fun testParseClaudeStreamChunk() {
        val claudeData = """{"type":"content_block_delta","delta":{"type":"text_delta","text":"Wave function"}}"""
        val json = JSONObject(claudeData)
        assertEquals("content_block_delta", json.getString("type"))
        val text = json.getJSONObject("delta").getString("text")
        assertEquals("Wave function", text)
    }
}

