package com.aiassistant.presentation.vm

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.data.llm.OnDeviceLlmSettingsManager
import com.aiassistant.data.repository.SettingsDataRepository
import com.aiassistant.domain.llm.OnDeviceLlmSettings
import com.aiassistant.domain.tool.TermuxShellTool
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class SettingsUiState(
    val apiKey: String? = null,
    val apiBaseUrl: String? = null,
    val defaultModel: String? = null,
    val systemPrompt: String? = null,
    val embeddingModel: String? = "text-embedding-3-small",
    val exaApiKey: String? = null,
    val onDeviceSettings: OnDeviceLlmSettings? = null,
    val isSaved: Boolean = false
)

const val DEFAULT_SYSTEM_PROMPT = """You are a helpful AI assistant with access to tools. You can:
- Search the web using 'web_search' tool
- Calculate math using 'calculator' tool
- Get weather using 'weather' tool
- Fetch web pages using 'web_fetch' tool
- Run JavaScript using 'code_interpreter' tool
- Get device info using 'device_info' tool

Current date and time: [CURRENT_DATE_TIME]

When a tool returns an error:
1. Acknowledge the error
2. Try a different approach or tool if possible
3. If all tools fail, politely inform the user you couldn't complete the task
4. NEVER keep retrying the same failing tool - try alternatives or give up gracefully"""

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsDataRepository,
    private val onDeviceLlmSettingsManager: OnDeviceLlmSettingsManager,
    private val termuxShellTool: TermuxShellTool,
    @ApplicationContext private val applicationContext: Context
) : ViewModel() {

    val termuxStatus = MutableStateFlow(termuxShellTool.getStatus())

    private val sharedPreferences: SharedPreferences by lazy {
        applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    }

    val settings = settingsRepository.settings
        .combine(onDeviceLlmSettingsManager.settings) { appSettings, onDeviceSettings ->
            val savedExaKey = sharedPreferences.getString("exa_api_key", null)
            SettingsUiState(
                apiKey = appSettings.apiKey,
                apiBaseUrl = appSettings.apiBaseUrl,
                defaultModel = appSettings.defaultModel,
                systemPrompt = appSettings.systemPrompt,
                embeddingModel = appSettings.embeddingModel,
                exaApiKey = savedExaKey ?: appSettings.exaApiKey,
                onDeviceSettings = onDeviceSettings,
                isSaved = false
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsUiState()
        )

    fun saveSettings(
        apiKey: String?,
        apiBaseUrl: String?,
        defaultModel: String?,
        systemPrompt: String?,
        embeddingModel: String?,
        exaApiKey: String?
    ) {
        viewModelScope.launch {
            apiKey?.let { settingsRepository.saveApiKey(it) }
            apiBaseUrl?.let { settingsRepository.saveApiBaseUrl(it) }
            defaultModel?.let { settingsRepository.saveDefaultModel(it) }
            systemPrompt?.let { settingsRepository.saveSystemPrompt(it) }
            embeddingModel?.let { settingsRepository.saveEmbeddingModel(it) }
            exaApiKey?.let { settingsRepository.saveExaApiKey(it) }

            sharedPreferences.edit().apply {
                if (exaApiKey != null) {
                    putString("exa_api_key", exaApiKey)
                } else {
                    remove("exa_api_key")
                }
                apply()
            }
        }
    }

    fun saveOnDeviceSettings(settings: OnDeviceLlmSettings) {
        viewModelScope.launch {
            onDeviceLlmSettingsManager.saveSettings(settings)
        }
    }
}
