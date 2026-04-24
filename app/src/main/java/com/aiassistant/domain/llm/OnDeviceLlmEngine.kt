package com.aiassistant.domain.llm

import android.content.Context
import android.util.Log
import com.aiassistant.domain.model.ChatMessage
import com.aiassistant.domain.model.MessageRole
import com.aiassistant.domain.tool.OnDeviceToolExecutor
import com.google.ai.edge.litertlm.*
import com.google.ai.edge.litertlm.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined
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
    private var currentSystemPrompt: String? = null
    private var currentTemperature: Float? = null
    private var currentTopK: Int? = null
    private var currentTopP: Float? = null
    private var currentUseTools: Boolean = true

    fun getState(): EngineState {
        val convAlive = conversation?.isAlive == true
        return EngineState(
            isReady = convAlive,
            isLoading = false,
            modelPath = currentModelPath,
            error = if (conversation != null && !convAlive && currentModelPath != null) "Conversation died" else null
        )
    }

    suspend fun needsReinitialize(
        modelPath: String,
        systemPrompt: String?,
        temperature: Float?,
        topK: Int?,
        topP: Float?,
        useTools: Boolean
    ): Boolean {
        return engine == null ||
            conversation?.isAlive != true ||
            modelPath != currentModelPath ||
            systemPrompt != currentSystemPrompt ||
            temperature != currentTemperature ||
            topK != currentTopK ||
            topP != currentTopP ||
            useTools != currentUseTools
    }

   @OptIn(ExperimentalApi::class)
    suspend fun initializeModel(
        modelPath: String,
        systemPrompt: String?,
        temperature: Float?,
        topK: Int?,
        topP: Float?,
        useTools: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            closeEngine()

            val modelFile = File(modelPath)
            if (!modelFile.exists() || modelFile.length() == 0L) {
                return@withContext Result.failure(IllegalStateException("Model file not found or empty: $modelPath"))
            }

          Log.d("OnDeviceLlmEngine", "Loading model from: $modelPath, useTools=$useTools")
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                maxNumTokens = 16384,
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
                tools = if (useTools) OnDeviceToolExecutor(context).getAllTools().map { tool(it) } else emptyList(),
                automaticToolCalling = false,
            )

            conversation = engine!!.createConversation(conversationConfig)
            currentModelPath = modelPath
            currentSystemPrompt = systemPrompt
            currentTemperature = temperature
            currentTopK = topK
            currentTopP = topP
            currentUseTools = useTools

            Log.d("OnDeviceLlmEngine", "Model initialized successfully: $modelPath")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("OnDeviceLlmEngine", "Failed to initialize model", e)
            closeEngine()
            Result.failure(e)
        }
    }

    @OptIn(ExperimentalApi::class)
    fun resetConversation() {
        Log.d("OnDeviceLlmEngine", "Resetting conversation state")
        closeConversation()
        engine?.let { eng ->
            val tempFloat: Float = currentTemperature ?: 0.8f
            val topPFloat: Float = currentTopP ?: 0.95f
            val temp: Double = tempFloat.toDouble()
            val topPVal: Double = topPFloat.toDouble()
            val topKVal: Int = currentTopK ?: 10

            val samplerConfig = SamplerConfig(
                topK = topKVal,
                topP = topPVal,
                temperature = temp,
            )

            val conversationConfig = ConversationConfig(
                systemInstruction = currentSystemPrompt?.let { Contents.of(it) },
                samplerConfig = samplerConfig,
                tools = if (currentUseTools) OnDeviceToolExecutor(context).getAllTools().map { tool(it) } else emptyList(),
                automaticToolCalling = false,
            )

            conversation = eng.createConversation(conversationConfig)
        }
    }

    fun chatStream(messages: List<ChatMessage>): Flow<ChatEvent> = flow {
        val conv = conversation ?: run {
            emit(ChatEvent.Error("Model not initialized"))
            return@flow
        }

        val lastUserMessage = messages.lastOrNull { it.role == MessageRole.USER } ?: run {
            emit(ChatEvent.Error("No user message found"))
            return@flow
        }

        val fullText = lastUserMessage.content
        Log.d("OnDeviceLlmEngine", "Starting chat stream for: ${fullText.take(50)}...")

        try {
            var currentText = fullText
            var round = 0
            val maxRounds = 25
            var finalResponse = ""
            var lastResponse: com.google.ai.edge.litertlm.Message? = null

            do {
                Log.d("OnDeviceLlmEngine", "Round $round: sending message async")
                var roundHasToolCalls = false
                val responses = conv.sendMessageAsync(currentText).toList()
                lastResponse = responses.lastOrNull()

                for (response in responses) {
                    val textParts = response.contents.contents.filterIsInstance<Content.Text>()
                    for (textPart in textParts) {
                        if (textPart.text.isNotBlank()) {
                            emit(ChatEvent.Chunk(textPart.text))
                            finalResponse += textPart.text
                        }
                    }
                    if (response.toolCalls.isNotEmpty()) {
                        roundHasToolCalls = true
                    }
                }

                if (roundHasToolCalls) {
                    Log.d("OnDeviceLlmEngine", "Tool calls detected: ${lastResponse?.toolCalls?.size}")
                    val toolResults = mutableListOf<String>()
                    val tools = if (currentUseTools) OnDeviceToolExecutor(context).getAllTools() else emptyList()

                    for (toolCall in lastResponse?.toolCalls ?: emptyList()) {
                        Log.d("OnDeviceLlmEngine", "Executing tool: ${toolCall.name}")
                        try {
                            val argsJson = convertJsValueToJson(toolCall.arguments)
                            val rawResult = tools.find {
                                it.getToolDescriptionJsonString().contains("\"name\": \"${toolCall.name}\"")
                            }?.execute(argsJson) ?: "Error: Tool not found"
                            val plainText = extractResultFromJson(rawResult)
                            toolResults.add(plainText)
                            Log.d("OnDeviceLlmEngine", "Tool ${toolCall.name} result: ${plainText.take(50)}")
                        } catch (e: Exception) {
                            Log.e("OnDeviceLlmEngine", "Tool execution failed: ${toolCall.name}", e)
                            toolResults.add("Error: ${e.message}")
                        }
                    }

                    currentText = toolResults.joinToString("\n\n")
                    round++
                } else {
                    Log.d("OnDeviceLlmEngine", "Final response: ${finalResponse.take(100)}")
                    round++
                }
            } while (lastResponse?.toolCalls?.isNotEmpty() == true && round < maxRounds)

            if (finalResponse.isNotBlank()) {
                emit(ChatEvent.Chunk(finalResponse))
            }
            emit(ChatEvent.Done(finalResponse))
        } catch (e: Exception) {
            Log.e("OnDeviceLlmEngine", "Chat failed", e)
            emit(ChatEvent.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.Default)

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
        currentSystemPrompt = null
        currentTemperature = null
        currentTopK = null
        currentTopP = null
        currentUseTools = true
    }

    private fun extractResultFromJson(rawResult: String): String {
        val jsonStart = rawResult.indexOf("{")
        if (jsonStart < 0) return rawResult
        
        val resultKey = "\"result\""
        val keyIndex = rawResult.indexOf(resultKey)
        if (keyIndex < 0) return rawResult
        
        val colonIndex = rawResult.indexOf(':', keyIndex)
        if (colonIndex < 0) return rawResult
        
        var quoteIndex = rawResult.indexOf('"', colonIndex + 1)
        if (quoteIndex < 0) return rawResult
        
        val valueStart = quoteIndex + 1
        val sb = StringBuilder()
        var i = valueStart
        while (i < rawResult.length) {
            val ch = rawResult[i]
            if (ch == '\\') {
                sb.append(rawResult[i])
                i++
                if (i < rawResult.length) {
                    sb.append(rawResult[i])
                }
                i++
            } else if (ch == '"') {
                break
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    private fun convertJsValueToJson(value: Any?): String {
        return when (value) {
            null -> "{}"
            is String -> "\"$value\""
            is Number, is Boolean -> value.toString()
            is Scriptable -> {
                if (value is NativeArray) {
                    val items = (0 until value.size).map { i ->
                        val item = value.get(i, value)
                        if (item is Undefined) null else convertJsValueToJson(item)
                    }
                    "[${items.joinToString(",")}]"
                } else {
                    val keys = valueIds(value)
                    val pairs = keys.map { key ->
                        val v = value.get(key, value)
                        val jsonVal = if (v is Undefined) "null" else convertJsValueToJson(v)
                        """"$key":$jsonVal"""
                    }
                    "{${pairs.joinToString(",")}}"
                }
            }
            is Map<*, *> -> {
                val pairs = value.map { (k, v) ->
                    val keyStr = if (k is String) k else """"$k""""
                    val jsonVal = convertJsValueToJson(v)
                    "$keyStr:$jsonVal"
                }
                "{${pairs.joinToString(",")}}"
            }
            is Array<*> -> {
                val items = value.map { convertJsValueToJson(it) }
                "[${items.joinToString(",")}]"
            }
            is Collection<*> -> {
                val items = value.map { convertJsValueToJson(it) }
                "[${items.joinToString(",")}]"
            }
            else -> "\"$value\""
        }
    }

    private fun valueIds(obj: Scriptable): List<String> {
        return obj.getIds().map { it.toString() }
    }

    companion object {
        const val MODEL_DIR = "litertlm_models"
        const val DEFAULT_MODEL_NAME = "gemma-4-E2B-it.litertlm"
        const val DEFAULT_HUGGINGFACE_REPO = "litert-community/gemma-4-E2B-it-litert-lm"
    }
}
