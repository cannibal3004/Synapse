package com.aiassistant.domain.llm

data class OnDeviceLlmSettings(
    val enabled: Boolean = false,
    val modelName: String = OnDeviceLlmEngine.DEFAULT_MODEL_NAME,
    val huggingfaceRepo: String = OnDeviceLlmEngine.DEFAULT_HUGGINGFACE_REPO,
    val systemPrompt: String? = null,
    val temperature: Float? = null,
    val topK: Int? = null,
    val topP: Float? = null
)
