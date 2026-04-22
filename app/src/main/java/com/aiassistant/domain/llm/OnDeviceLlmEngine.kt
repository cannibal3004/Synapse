package com.aiassistant.domain.llm

import android.content.Context
import android.util.Log
import com.aiassistant.domain.model.ChatMessage
import com.aiassistant.domain.model.MessageRole
import com.google.ai.edge.litertlm.*
import com.google.ai.edge.litertlm.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

class OnDeviceLlmEngine(
    private val context: Context
) {

    data class EngineState(
        val isReady: Boolean = false,
        val isLoading: Boolean = false,
        val modelPath: String? = null,
        val error: String? = null
    )

    sealed interface ChatEvent {
        data class Chunk(val text: String) : ChatEvent
        data class Error(val error: String) : ChatEvent
        data class Done(val response: String) : ChatEvent
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var currentModelPath: String? = null
    private var systemPrompt: String? = null
    private var temperature: Float? = null
    private var topK: Int? = null
    private var topP: Float? = null

    fun getState(): EngineState {
        return EngineState(
            isReady = conversation != null,
            isLoading = false,
            modelPath = currentModelPath,
            error = if (conversation == null && currentModelPath != null) "Failed to initialize model" else null
        )
    }

    suspend fun initializeModel(
        modelPath: String,
        systemPrompt: String?,
        temperature: Float?,
        topK: Int?,
        topP: Float?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            this@OnDeviceLlmEngine.systemPrompt = systemPrompt
            this@OnDeviceLlmEngine.temperature = temperature
            this@OnDeviceLlmEngine.topK = topK
            this@OnDeviceLlmEngine.topP = topP

            closeConversation()

            val modelFile = File(modelPath)
            if (!modelFile.exists() || modelFile.length() == 0L) {
                return@withContext Result.failure(IllegalStateException("Model file not found or empty: $modelPath"))
            }

            Log.d("OnDeviceLlmEngine", "Loading model from: $modelPath")
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.path,
            )

            engine = Engine(engineConfig)
            engine!!.initialize()

            val tempFloat: Float = temperature ?: 0.8f
            val topPFloat: Float = topP ?: 0.95f
            val temp: Double = tempFloat.toDouble()
            val topPVal: Double = topPFloat.toDouble()
            val topKVal: Int = topK ?: 10

            val samplerConfig = SamplerConfig(
                topK = topKVal,
                topP = topPVal,
                temperature = temp,
            )

            val conversationConfig = ConversationConfig(
                systemInstruction = systemPrompt?.let { Contents.of(it) },
                samplerConfig = samplerConfig,
            )

            conversation = engine!!.createConversation(conversationConfig)
            currentModelPath = modelPath

            Log.d("OnDeviceLlmEngine", "Model initialized successfully: $modelPath")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("OnDeviceLlmEngine", "Failed to initialize model", e)
            closeEngine()
            Result.failure(e)
        }
    }

    fun chatStream(messages: List<ChatMessage>): Flow<ChatEvent> = callbackFlow {
        try {
            val conv = conversation ?: run {
                trySend(ChatEvent.Error("Model not initialized"))
                close()
                return@callbackFlow
            }

            val lastUserMessage = messages.lastOrNull { it.role == MessageRole.USER }
                ?: run {
                    trySend(ChatEvent.Error("No user message found"))
                    close()
                    return@callbackFlow
                }

            val fullText = lastUserMessage.content

            conv.sendMessageAsync(fullText, object : MessageCallback {
                override fun onMessage(message: Message) {
                    message.contents.contents.filterIsInstance<Content.Text>().forEach {
                        trySend(ChatEvent.Chunk(it.text))
                    }
                }

                override fun onDone() {
                    trySend(ChatEvent.Done(fullText))
                    close()
                }

                override fun onError(throwable: Throwable) {
                    trySend(ChatEvent.Error(throwable.message ?: "Streaming error"))
                    close()
                }
            })
        } catch (e: Exception) {
            trySend(ChatEvent.Error(e.message ?: "Unknown error"))
            close()
        }
    }

    suspend fun chat(
        messages: List<ChatMessage>,
        systemPrompt: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val conv = conversation ?: return@withContext Result.failure(IllegalStateException("Model not initialized"))

            val lastUserMessage = messages.lastOrNull { it.role == MessageRole.USER }
                ?: return@withContext Result.failure(IllegalStateException("No user message found"))

            val response = conv.sendMessage(lastUserMessage.content)
            Log.d("OnDeviceLlmEngine", "Response: $response")
            Result.success(response.toString())
        } catch (e: Exception) {
            Log.e("OnDeviceLlmEngine", "Chat failed", e)
            Result.failure(e)
        }
    }

    fun shutdown() {
        Log.d("OnDeviceLlmEngine", "Shutting down")
        closeConversation()
        closeEngine()
    }

    private fun closeConversation() {
        conversation?.close()
        conversation = null
    }

    private fun closeEngine() {
        engine?.close()
        engine = null
        currentModelPath = null
        systemPrompt = null
    }

    companion object {
        const val MODEL_DIR = "litertlm_models"
        const val DEFAULT_MODEL_NAME = "gemma-3n-E2B-it-int4.litertlm"
        const val DEFAULT_HUGGINGFACE_REPO = "litert-community/gemma-3n-E2B-it-litert-lm"
    }
}
