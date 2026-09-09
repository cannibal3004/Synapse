package com.aiassistant.domain.repository

import com.aiassistant.domain.llm.LlmBackend
import com.aiassistant.domain.llm.OnDeviceLlmEngine
import com.aiassistant.data.repository.DEFAULT_MAX_TOOL_ROUNDS
import com.aiassistant.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface OnDeviceLlmRepository {

    val state: StateFlow<OnDeviceLlmEngine.EngineState>

    suspend fun needsReinitialize(
        modelPath: String,
        systemPrompt: String?,
        temperature: Float?,
        topK: Int?,
        topP: Float?,
        useTools: Boolean,
        enableThinking: Boolean = false,
        thinkingTokenBudget: Int? = null,
        maxOutputTokens: Int? = null,
        backend: LlmBackend = LlmBackend.CPU,
        contextTokens: Int? = null
    ): Boolean

    suspend fun initializeModel(
        modelPath: String,
        systemPrompt: String? = null,
        temperature: Float? = null,
        topK: Int? = null,
        topP: Float? = null,
        useTools: Boolean = true,
        enableThinking: Boolean = false,
        thinkingTokenBudget: Int? = null,
        maxOutputTokens: Int? = null,
        backend: LlmBackend = LlmBackend.CPU,
        contextTokens: Int? = null
    ): Result<Unit>

    fun chatStream(
        messages: List<ChatMessage>,
        maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS
    ): Flow<OnDeviceLlmEngine.ChatEvent>

    suspend fun downloadModel(
        huggingfaceRepo: String = OnDeviceLlmEngine.DEFAULT_HUGGINGFACE_REPO,
        modelName: String = OnDeviceLlmEngine.DEFAULT_MODEL_NAME,
        onProgress: (Float) -> Unit = {}
    ): Result<String>

    fun getModelPath(modelName: String = OnDeviceLlmEngine.DEFAULT_MODEL_NAME): String

    fun isModelAvailable(modelName: String = OnDeviceLlmEngine.DEFAULT_MODEL_NAME): Boolean

    fun deleteModel(modelName: String = OnDeviceLlmEngine.DEFAULT_MODEL_NAME): Boolean

    suspend fun shutdown()

    fun resetConversation()

    fun cancel()
}
