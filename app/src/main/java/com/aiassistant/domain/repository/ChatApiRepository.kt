package com.aiassistant.domain.repository

import com.aiassistant.data.model.api.ChatMessage as ApiChatMessage
import com.aiassistant.data.model.api.ChatCompletionResponse
import com.aiassistant.domain.model.ChatMessage as DomainChatMessage
import kotlinx.coroutines.flow.Flow

interface ChatApiRepository {
    suspend fun sendChatMessage(
        apiKey: String,
        model: String,
        baseUrl: String?,
        conversationId: String,
        userMessage: String,
        systemPrompt: String? = null,
        temperature: Double? = null
    ): Flow<ChatEvent>

    suspend fun sendChatRequest(
        apiKey: String,
        model: String,
        baseUrl: String?,
        messages: List<ApiChatMessage>,
        tools: List<com.aiassistant.data.model.api.Tool> = emptyList()
    ): ChatCompletionResponse

    suspend fun streamChatResponse(
        apiKey: String,
        model: String,
        baseUrl: String?,
        messages: List<ApiChatMessage>
    ): Sequence<String>

    sealed interface ChatEvent {
        data class Loading(val conversationId: String) : ChatEvent
        data class MessageAdded(val message: DomainChatMessage) : ChatEvent
        data class Error(val error: String) : ChatEvent
    }
}
