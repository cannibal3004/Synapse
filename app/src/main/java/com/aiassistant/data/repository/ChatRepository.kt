package com.aiassistant.data.repository

import com.aiassistant.data.api.OpenAIService
import com.aiassistant.data.api.RetrofitClient
import com.aiassistant.data.model.MessageEntity
import com.aiassistant.data.model.api.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader

class ChatRepository(
    private val openAIService: OpenAIService
) {
    private val gson = Gson()

    suspend fun sendChatRequest(
        apiKey: String,
        model: String,
        baseUrl: String?,
        messages: List<com.aiassistant.data.model.api.ChatMessage>,
        systemPrompt: String? = null,
        temperature: Double? = null,
        tools: List<com.aiassistant.data.model.api.Tool> = emptyList()
    ): ChatCompletionResponse {
        val service = baseUrl?.let { RetrofitClient.createServiceWithBaseUrl(it) } ?: openAIService
        
        val allMessages = mutableListOf<com.aiassistant.data.model.api.ChatMessage>()

        systemPrompt?.let {
            allMessages.add(com.aiassistant.data.model.api.ChatMessage("system", it))
        }
        allMessages.addAll(messages)

        val request = ChatCompletionRequest(
            model = model,
            messages = allMessages,
            stream = false,
            temperature = temperature,
            tools = tools
        )

        return service.chatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )
    }

    suspend fun sendChatRequestWithTools(
        apiKey: String,
        model: String,
        baseUrl: String?,
        messages: List<com.aiassistant.data.model.api.ChatMessage>,
        systemPrompt: String? = null,
        temperature: Double? = null,
        tools: List<com.aiassistant.data.model.api.Tool> = emptyList()
    ): ChatCompletionResponse {
        val service = baseUrl?.let { RetrofitClient.createServiceWithBaseUrl(it) } ?: openAIService
        
        val allMessages = mutableListOf<com.aiassistant.data.model.api.ChatMessage>()

        systemPrompt?.let {
            allMessages.add(com.aiassistant.data.model.api.ChatMessage("system", it))
        }
        allMessages.addAll(messages)

        val request = ChatCompletionRequest(
            model = model,
            messages = allMessages,
            stream = false,
            temperature = temperature,
            tools = tools
        )

        return service.chatCompletion(
            authorization = "Bearer $apiKey",
            request = request
        )
    }

    /**
     * A streamed completion, as text deltas followed by one [StreamEvent.Complete].
     *
     * Replaces an earlier `Sequence<String>` version that was never called from anywhere. That
     * one yielded raw `data:` JSON rather than the text inside it, did blocking reads inside a
     * sequence with no dispatcher, and never closed the response body.
     *
     * Tool calls arrive split across frames -- the name in one, the JSON arguments a few
     * characters at a time, keyed only by `index` -- so they are reassembled here and handed
     * over whole. The caller cannot act on half an argument list anyway.
     */
    fun streamChatCompletion(
        apiKey: String,
        model: String,
        baseUrl: String?,
        messages: List<com.aiassistant.data.model.api.ChatMessage>,
        tools: List<com.aiassistant.data.model.api.Tool> = emptyList(),
        temperature: Double? = null
    ): Flow<StreamEvent> = flow {
        val service = baseUrl?.let { RetrofitClient.createServiceWithBaseUrl(it) } ?: openAIService

        val responseBody = service.chatCompletionStream(
            authorization = "Bearer $apiKey",
            request = ChatCompletionRequest(
                model = model,
                messages = messages,
                stream = true,
                temperature = temperature,
                tools = tools.ifEmpty { null }
            )
        )

        val content = StringBuilder()
        val partialCalls = sortedMapOf<Int, PartialToolCall>()
        var finishReason: String? = null

        responseBody.use { body ->
            val reader = BufferedReader(InputStreamReader(body.byteStream()))
            while (true) {
                val line = reader.readLine() ?: break
                // Comments (": keepalive") and blank separators are part of SSE, not errors.
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == DONE_SENTINEL) break

                val chunk = runCatching {
                    gson.fromJson(payload, ChatCompletionChunk::class.java)
                }.getOrNull() ?: continue

                val choice = chunk.choices?.firstOrNull() ?: continue
                choice.finish_reason?.let { finishReason = it }

                choice.delta?.content?.takeIf { it.isNotEmpty() }?.let { text ->
                    content.append(text)
                    emit(StreamEvent.Delta(text))
                }

                choice.delta?.tool_calls?.forEach { delta ->
                    val partial = partialCalls.getOrPut(delta.index) { PartialToolCall() }
                    delta.id?.let { partial.id = it }
                    delta.function?.name?.let { partial.name.append(it) }
                    delta.function?.arguments?.let { partial.arguments.append(it) }
                }
            }
        }

        emit(
            StreamEvent.Complete(
                content = content.toString(),
                toolCalls = partialCalls.map { (index, partial) -> partial.toToolCall(index) },
                finishReason = finishReason
            )
        )
    }.flowOn(Dispatchers.IO)

    private class PartialToolCall {
        var id: String? = null
        val name = StringBuilder()
        val arguments = StringBuilder()

        fun toToolCall(index: Int) = ToolCall(
            // Some providers omit the id on a streamed call; the index is unique within a round
            // and is what the tool result has to be correlated against.
            id = id ?: "call_$index",
            type = "function",
            function = FunctionCall(name.toString(), arguments.toString())
        )
    }

    suspend fun createEmbedding(
        apiKey: String,
        model: String,
        baseUrl: String?,
        text: String
    ): List<Float> {
        val service = baseUrl?.let { RetrofitClient.createServiceWithBaseUrl(it) } ?: openAIService
        
        val request = EmbeddingRequest(
            model = model,
            input = text
        )

        val response = service.createEmbedding(
            authorization = "Bearer $apiKey",
            request = request
        )

        return response.data.first().embedding
    }
}

private const val DONE_SENTINEL = "[DONE]"
