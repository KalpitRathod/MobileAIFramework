package com.mobileai.framework.cloud

/**
 * Supported Major External AI Cloud Providers.
 */
enum class CloudProvider(
    val displayName: String,
    val defaultModel: String,
    val defaultBaseUrl: String,
    val isEuropean: Boolean = false,
    val isOpenAiCompatible: Boolean = true
) {
    GEMINI(
        displayName = "Google Gemini",
        defaultModel = "gemini-2.0-flash",
        defaultBaseUrl = "https://generativelanguage.googleapis.com",
        isOpenAiCompatible = false
    ),
    CLAUDE(
        displayName = "Anthropic Claude",
        defaultModel = "claude-3-5-sonnet-20241022",
        defaultBaseUrl = "https://api.anthropic.com",
        isOpenAiCompatible = false
    ),
    OPENAI_CHATGPT(
        displayName = "OpenAI ChatGPT",
        defaultModel = "gpt-4o",
        defaultBaseUrl = "https://api.openai.com/v1"
    ),
    XAI_GROK(
        displayName = "xAI (Grok)",
        defaultModel = "grok-2-latest",
        defaultBaseUrl = "https://api.x.ai/v1"
    ),
    DEEPSEEK(
        displayName = "DeepSeek (V3 / R1)",
        defaultModel = "deepseek-chat",
        defaultBaseUrl = "https://api.deepseek.com"
    ),
    MOONSHOT_KIMI(
        displayName = "Moonshot (Kimi)",
        defaultModel = "moonshot-v1-8k",
        defaultBaseUrl = "https://api.moonshot.cn/v1"
    ),
    ZHIPU_ZAI(
        displayName = "Z-AI (GLM-4 / Zhipu)",
        defaultModel = "glm-4",
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4"
    ),
    MISTRAL_AI(
        displayName = "Mistral AI (European Leader)",
        defaultModel = "mistral-large-latest",
        defaultBaseUrl = "https://api.mistral.ai/v1",
        isEuropean = true
    ),
    CUSTOM_EUROPEAN(
        displayName = "Custom / European (Scaleway, Aleph Alpha, OVH)",
        defaultModel = "custom-model",
        defaultBaseUrl = "https://api.scaleway.ai/v1",
        isEuropean = true
    )
}

data class CloudAiConfig(
    val provider: CloudProvider = CloudProvider.GEMINI,
    val apiKey: String = "",
    val modelName: String = provider.defaultModel,
    val baseUrl: String = provider.defaultBaseUrl,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048
)

