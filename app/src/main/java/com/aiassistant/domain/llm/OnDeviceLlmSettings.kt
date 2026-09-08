package com.aiassistant.domain.llm

data class OnDeviceLlmSettings(
    val enabled: Boolean = false,
    val modelName: String = OnDeviceLlmEngine.DEFAULT_MODEL_NAME,
    val huggingfaceRepo: String = OnDeviceLlmEngine.DEFAULT_HUGGINGFACE_REPO,
    val systemPrompt: String? = null,
    val temperature: Float? = null,
    val topK: Int? = null,
    val topP: Float? = null,
    val enableThinking: Boolean = false,
    val thinkingTokenBudget: Int? = null,
    val maxOutputTokens: Int? = null,
    val backend: LlmBackend = LlmBackend.CPU,
    /** KV/context budget passed to EngineConfig.maxNumTokens. Null uses the engine default. */
    val contextTokens: Int? = null,
    val onDeviceEmbeddingsEnabled: Boolean = false,
    val embeddingModelName: String = OnDeviceEmbeddingEngine.DEFAULT_EMBEDDING_MODEL_NAME,
    val embeddingHuggingfaceRepo: String = OnDeviceEmbeddingEngine.DEFAULT_EMBEDDING_REPO
)
