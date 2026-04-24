package com.aiassistant.domain.repository

import com.aiassistant.domain.llm.OnDeviceLlmEngine
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
        useTools: Boolean
    ): Boolean

    suspend fun initializeModel(
        modelPath: String,
        systemPrompt: String? = null,
        temperature: Float? = null,
        topK: Int? = null,
        topP: Float? = null,
        useTools: Boolean = true
    ): Result<Unit>

    fun chatStream(messages: List<ChatMessage>): Flow<OnDeviceLlmEngine.ChatEvent>

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
}
