package com.aiassistant.domain.llm

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aiassistant.domain.model.ChatMessage
import com.aiassistant.domain.model.MessageRole
import com.aiassistant.domain.tool.OnDeviceToolExecutor
import com.google.ai.edge.litertlm.*
import com.google.ai.edge.litertlm.Backend
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
  
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

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
                maxNumTokens = 32768,
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

    fun chatStream(messages: List<ChatMessage>): Flow<ChatEvent> = kotlinx.coroutines.flow.flow {
        val conv = conversation
        if (conv == null) {
            emit(ChatEvent.Error("Model not initialized"))
            return@flow
        }

        val lastUserMessage = messages.lastOrNull { it.role == MessageRole.USER }
        if (lastUserMessage == null) {
            emit(ChatEvent.Error("No user message found"))
            return@flow
        }

        val fullText = lastUserMessage.content
        Log.d("OnDeviceLlmEngine", "Starting chat stream for: ${fullText.take(50)}...")

        val isMainThread = Looper.myLooper() == Looper.getMainLooper()

        try {
            var currentText = fullText
            var round = 0
            val maxRounds = 25
            var finalResponse = ""
            var lastResponse: com.google.ai.edge.litertlm.Message? = null

            do {
                Log.d("OnDeviceLlmEngine", "Round $round: sending message (mainThread=$isMainThread)")
                
                val response = if (isMainThread) {
                    conv.sendMessage(currentText)
                } else {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var result: com.google.ai.edge.litertlm.Message? = null
                    var exception: Exception? = null
                    
                    mainHandler.post {
                        try {
                            result = conv.sendMessage(currentText)
                        } catch (e: Exception) {
                            exception = e
                        } finally {
                            latch.countDown()
                        }
                    }
                    
                    latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
                    exception?.let { throw it }
                    result!!
                }
                lastResponse = response
                
                if (response.toolCalls.isNotEmpty()) {
                    Log.d("OnDeviceLlmEngine", "Tool calls detected: ${response.toolCalls.size}")
                    val toolResults = mutableListOf<String>()
                    val tools = if (currentUseTools) OnDeviceToolExecutor(context).getAllTools() else emptyList()
                    
                    for (toolCall in response.toolCalls) {
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
                    val textParts = mutableListOf<String>()
                    response.contents.contents.filterIsInstance<Content.Text>().forEach {
                        textParts.add(it.text)
                    }
                    finalResponse = textParts.joinToString("")
                    Log.d("OnDeviceLlmEngine", "Final response: ${finalResponse.take(100)}")
                    round++
                }
            } while (response.toolCalls.isNotEmpty() && round < maxRounds)

            if (finalResponse.isNotBlank()) {
                emit(ChatEvent.Chunk(finalResponse))
            }
            emit(ChatEvent.Done(finalResponse))
        } catch (e: Exception) {
            Log.e("OnDeviceLlmEngine", "Chat failed", e)
            emit(ChatEvent.Error(e.message ?: "Unknown error"))
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
