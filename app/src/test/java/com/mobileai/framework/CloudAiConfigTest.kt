package com.mobileai.framework

import com.mobileai.framework.cloud.CloudAiConfig
import com.mobileai.framework.cloud.CloudProvider
import org.junit.Assert.*
import org.junit.Test

class CloudAiConfigTest {

    @Test
    fun testAllProvidersHaveValidUrlsAndModels() {
        val providers = CloudProvider.values()
        assertTrue(providers.isNotEmpty())

        for (provider in providers) {
            assertNotNull(provider.displayName)
            assertTrue("Provider ${provider.name} must have a non-empty displayName", provider.displayName.isNotBlank())
            assertTrue("Provider ${provider.name} must have a default model", provider.defaultModel.isNotBlank())
            assertTrue("Provider ${provider.name} must have a valid URL", provider.defaultBaseUrl.startsWith("https://"))
        }
    }

    @Test
    fun testCustomEuropeanEndpointConfig() {
        val customConfig = CloudAiConfig(
            provider = CloudProvider.CUSTOM_EUROPEAN,
            apiKey = "sk-custom-scaleway-key",
            modelName = "llama-3.1-70b-instruct",
            baseUrl = "https://api.scaleway.ai/v1",
            temperature = 0.5f,
            maxTokens = 4096
        )

        assertTrue(customConfig.provider.isEuropean)
        assertEquals("sk-custom-scaleway-key", customConfig.apiKey)
        assertEquals("llama-3.1-70b-instruct", customConfig.modelName)
        assertEquals("https://api.scaleway.ai/v1", customConfig.baseUrl)
        assertEquals(0.5f, customConfig.temperature, 0.001f)
        assertEquals(4096, customConfig.maxTokens)
    }

    @Test
    fun testDeepSeekAndMistralCompatibility() {
        assertTrue("DeepSeek must use OpenAI compatible wire protocol", CloudProvider.DEEPSEEK.isOpenAiCompatible)
        assertTrue("Mistral AI must use OpenAI compatible wire protocol", CloudProvider.MISTRAL_AI.isOpenAiCompatible)
        assertTrue("xAI Grok must use OpenAI compatible wire protocol", CloudProvider.XAI_GROK.isOpenAiCompatible)
        assertFalse("Google Gemini uses native Google API", CloudProvider.GEMINI.isOpenAiCompatible)
        assertFalse("Anthropic Claude uses native Anthropic Messages API", CloudProvider.CLAUDE.isOpenAiCompatible)
    }
}

