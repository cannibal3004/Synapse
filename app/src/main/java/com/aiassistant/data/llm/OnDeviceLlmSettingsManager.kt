package com.aiassistant.data.llm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.aiassistant.domain.llm.LlmBackend
import com.aiassistant.domain.llm.OnDeviceEmbeddingEngine
import com.aiassistant.domain.llm.OnDeviceLlmEngine
import com.aiassistant.domain.llm.OnDeviceLlmSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnDeviceLlmSettingsManager @Inject constructor(
    @androidx.annotation.Keep
    private val context: Context
) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("on_device_llm", Context.MODE_PRIVATE)
    }

    private val _settings = MutableStateFlow(prefs.loadSettings())
    val settings: Flow<OnDeviceLlmSettings> = _settings.asStateFlow()

    fun getSettings(): OnDeviceLlmSettings = prefs.toSettings()

    fun saveSettings(settings: OnDeviceLlmSettings) {
        with(prefs.edit()) {
            putBoolean("enabled", settings.enabled)
            putString("model_name", settings.modelName)
            putString("huggingface_repo", settings.huggingfaceRepo)
            settings.systemPrompt?.let { putString("system_prompt", it) }
                ?: remove("system_prompt")
            settings.temperature?.let { putFloat("temperature", it) }
                ?: remove("temperature")
            settings.topK?.let { putInt("top_k", it) }
                ?: remove("top_k")
            settings.topP?.let { putFloat("top_p", it) }
                ?: remove("top_p")
            putBoolean("enable_thinking", settings.enableThinking)
            settings.thinkingTokenBudget?.let { putInt("thinking_token_budget", it) }
                ?: remove("thinking_token_budget")
            settings.maxOutputTokens?.let { putInt("max_output_tokens", it) }
                ?: remove("max_output_tokens")
            putString("backend", settings.backend.name)
            settings.contextTokens?.let { putInt("context_tokens", it) }
                ?: remove("context_tokens")
            putBoolean("on_device_embeddings_enabled", settings.onDeviceEmbeddingsEnabled)
            putString("embedding_model_name", settings.embeddingModelName)
            putString("embedding_huggingface_repo", settings.embeddingHuggingfaceRepo)
            apply()
        }
        _settings.value = settings
        Log.d("OnDeviceLlmSettingsManager", "Settings saved: enabled=${settings.enabled}")
    }

    fun enableOnDevice(enabled: Boolean) {
        val current = getSettings()
        saveSettings(current.copy(enabled = enabled))
    }

    fun setModelName(modelName: String) {
        val current = getSettings()
        saveSettings(current.copy(modelName = modelName))
    }

    fun setHuggingfaceRepo(repo: String) {
        val current = getSettings()
        saveSettings(current.copy(huggingfaceRepo = repo))
    }

    fun setSystemPrompt(prompt: String?) {
        val current = getSettings()
        saveSettings(current.copy(systemPrompt = prompt))
    }

    fun setTemperature(temp: Float?) {
        val current = getSettings()
        saveSettings(current.copy(temperature = temp))
    }

    fun setTopK(k: Int?) {
        val current = getSettings()
        saveSettings(current.copy(topK = k))
    }

    fun setTopP(p: Float?) {
        val current = getSettings()
        saveSettings(current.copy(topP = p))
    }

    fun setEnableThinking(enabled: Boolean) {
        val current = getSettings()
        saveSettings(current.copy(enableThinking = enabled))
    }

    fun setThinkingTokenBudget(budget: Int?) {
        val current = getSettings()
        saveSettings(current.copy(thinkingTokenBudget = budget))
    }

    fun setMaxOutputTokens(tokens: Int?) {
        val current = getSettings()
        saveSettings(current.copy(maxOutputTokens = tokens))
    }

    fun setBackend(backend: LlmBackend) {
        val current = getSettings()
        saveSettings(current.copy(backend = backend))
    }

    fun setContextTokens(tokens: Int?) {
        val current = getSettings()
        saveSettings(current.copy(contextTokens = tokens))
    }

    fun setOnDeviceEmbeddingsEnabled(enabled: Boolean) {
        val current = getSettings()
        saveSettings(current.copy(onDeviceEmbeddingsEnabled = enabled))
    }

    fun setEmbeddingModel(modelName: String, repo: String) {
        val current = getSettings()
        saveSettings(current.copy(embeddingModelName = modelName, embeddingHuggingfaceRepo = repo))
    }

    private fun SharedPreferences.toSettings(): OnDeviceLlmSettings {
        return OnDeviceLlmSettings(
            enabled = getBoolean("enabled", false),
            modelName = getString("model_name", OnDeviceLlmEngine.DEFAULT_MODEL_NAME)
                ?: OnDeviceLlmEngine.DEFAULT_MODEL_NAME,
            huggingfaceRepo = getString("huggingface_repo", OnDeviceLlmEngine.DEFAULT_HUGGINGFACE_REPO)
                ?: OnDeviceLlmEngine.DEFAULT_HUGGINGFACE_REPO,
            systemPrompt = getString("system_prompt", null),
            temperature = run {
                val v = getFloat("temperature", -1f)
                if (v >= 0f) v else null
            },
            topK = run {
                // 0 is not a valid topK, so it reads back as "unset" rather than as a value.
                val v = getInt("top_k", -1)
                if (v > 0) v else null
            },
            topP = run {
                val v = getFloat("top_p", -1f)
                if (v > 0f) v else null
            },
            enableThinking = getBoolean("enable_thinking", false),
            thinkingTokenBudget = run {
                val v = getInt("thinking_token_budget", -1)
                if (v > 0) v else null
            },
            maxOutputTokens = run {
                val v = getInt("max_output_tokens", -1)
                if (v > 0) v else null
            },
            backend = LlmBackend.fromName(getString("backend", null)),
            contextTokens = run {
                val v = getInt("context_tokens", -1)
                if (v > 0) v else null
            },
            onDeviceEmbeddingsEnabled = getBoolean("on_device_embeddings_enabled", false),
            embeddingModelName = getString(
                "embedding_model_name",
                OnDeviceEmbeddingEngine.DEFAULT_EMBEDDING_MODEL_NAME
            ) ?: OnDeviceEmbeddingEngine.DEFAULT_EMBEDDING_MODEL_NAME,
            embeddingHuggingfaceRepo = getString(
                "embedding_huggingface_repo",
                OnDeviceEmbeddingEngine.DEFAULT_EMBEDDING_REPO
            ) ?: OnDeviceEmbeddingEngine.DEFAULT_EMBEDDING_REPO
        )
    }

    private fun SharedPreferences.loadSettings(): OnDeviceLlmSettings = toSettings()
}
