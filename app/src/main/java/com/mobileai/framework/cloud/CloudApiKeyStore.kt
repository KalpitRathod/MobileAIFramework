package com.mobileai.framework.cloud

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages persistent configuration and API keys for external cloud AI providers.
 */
class CloudApiKeyStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mobile_ai_cloud_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ACTIVE_PROVIDER = "active_provider"
        private const val KEY_PREFIX_API_KEY = "api_key_"
        private const val KEY_PREFIX_MODEL = "model_"
        private const val KEY_PREFIX_BASE_URL = "base_url_"
        private const val KEY_USE_CLOUD_AS_DEFAULT = "use_cloud_as_default"
    }

    fun getActiveProvider(): CloudProvider {
        val name = prefs.getString(KEY_ACTIVE_PROVIDER, CloudProvider.GEMINI.name) ?: CloudProvider.GEMINI.name
        return try {
            CloudProvider.valueOf(name)
        } catch (e: Exception) {
            CloudProvider.GEMINI
        }
    }

    fun setActiveProvider(provider: CloudProvider) {
        prefs.edit().putString(KEY_ACTIVE_PROVIDER, provider.name).apply()
    }

    fun isCloudPreferred(): Boolean {
        return prefs.getBoolean(KEY_USE_CLOUD_AS_DEFAULT, false)
    }

    fun setCloudPreferred(preferred: Boolean) {
        prefs.edit().putBoolean(KEY_USE_CLOUD_AS_DEFAULT, preferred).apply()
    }

    fun getApiKey(provider: CloudProvider): String {
        return prefs.getString(KEY_PREFIX_API_KEY + provider.name, "") ?: ""
    }

    fun setApiKey(provider: CloudProvider, key: String) {
        prefs.edit().putString(KEY_PREFIX_API_KEY + provider.name, key.trim()).apply()
    }

    fun getModelName(provider: CloudProvider): String {
        return prefs.getString(KEY_PREFIX_MODEL + provider.name, provider.defaultModel) ?: provider.defaultModel
    }

    fun setModelName(provider: CloudProvider, modelName: String) {
        prefs.edit().putString(KEY_PREFIX_MODEL + provider.name, modelName.trim()).apply()
    }

    fun getBaseUrl(provider: CloudProvider): String {
        return prefs.getString(KEY_PREFIX_BASE_URL + provider.name, provider.defaultBaseUrl) ?: provider.defaultBaseUrl
    }

    fun setBaseUrl(provider: CloudProvider, url: String) {
        prefs.edit().putString(KEY_PREFIX_BASE_URL + provider.name, url.trim()).apply()
    }

    fun getConfig(provider: CloudProvider = getActiveProvider()): CloudAiConfig {
        return CloudAiConfig(
            provider = provider,
            apiKey = getApiKey(provider),
            modelName = getModelName(provider),
            baseUrl = getBaseUrl(provider)
        )
    }
}

