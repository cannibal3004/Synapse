package com.aiassistant.data.repository

import com.aiassistant.data.api.OpenAIService
import com.aiassistant.data.api.RetrofitClient
import com.aiassistant.data.model.MessageEntity
import com.aiassistant.data.model.api.*
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
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

    suspend fun streamChatResponse(
        apiKey: String,
        model: String,
        baseUrl: String?,
        messages: List<com.aiassistant.data.model.api.ChatMessage>,
        systemPrompt: String? = null,
        temperature: Double? = null
    ): Sequence<String> {
        val service = baseUrl?.let { RetrofitClient.createServiceWithBaseUrl(it) } ?: openAIService
        
        val allMessages = mutableListOf<com.aiassistant.data.model.api.ChatMessage>()

        systemPrompt?.let {
            allMessages.add(com.aiassistant.data.model.api.ChatMessage("system", it))
        }
        allMessages.addAll(messages)

        val request = ChatCompletionRequest(
            model = model,
            messages = allMessages,
            stream = true,
            temperature = temperature
        )

        val responseBody = service.chatCompletionStream(
            authorization = "Bearer $apiKey",
            request = request
        )

        return sequence {
            val reader = BufferedReader(
                InputStreamReader(responseBody.byteStream())
            )
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.startsWith("data: ")) {
                    val data = line!!.substring(6).trim()
                    if (data != "[DONE]") {
                        yield(data)
                    }
                }
            }
        }
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
