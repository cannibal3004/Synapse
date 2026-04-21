package com.aiassistant.domain.repository

import com.aiassistant.data.model.api.ChatMessage as ApiChatMessage
import com.aiassistant.data.model.api.ChatCompletionResponse
import com.aiassistant.data.model.api.ToolCall as ApiToolCall
import com.aiassistant.data.repository.ChatRepository
import com.aiassistant.domain.model.ChatMessage as DomainChatMessage
import com.aiassistant.domain.model.MessageRole
import com.aiassistant.domain.model.ToolCall as DomainToolCall
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.UUID

class ChatApiRepositoryImpl @Inject constructor(
    private val chatRepository: ChatRepository
) : ChatApiRepository {

    override suspend fun sendChatMessage(
        apiKey: String,
        model: String,
        baseUrl: String?,
        conversationId: String,
        userMessage: String,
        systemPrompt: String?,
        temperature: Double?
    ): Flow<ChatApiRepository.ChatEvent> = callbackFlow {
        try {
            trySend(ChatApiRepository.ChatEvent.Loading(conversationId))

            launch {
                try {
                    val response = chatRepository.sendChatRequest(
                        apiKey = apiKey,
                        model = model,
                        baseUrl = baseUrl,
                        messages = emptyList(),
                        systemPrompt = systemPrompt,
                        temperature = temperature
                    )

                    val assistantChoice = response.choices.firstOrNull()
                    val assistantMessage = assistantChoice?.message

                    if (assistantMessage != null) {
                        val content = assistantMessage.content ?: ""
                        val toolCalls = assistantMessage.tool_calls?.map { apiToolCall: ApiToolCall ->
                            DomainToolCall(
                                id = apiToolCall.id,
                                name = apiToolCall.function.name,
                                arguments = apiToolCall.function.arguments
                            )
                        }

                        val messageId = UUID.randomUUID().toString()
                        val domainMessage = DomainChatMessage(
                            id = messageId,
                            conversationId = conversationId,
                            role = MessageRole.ASSISTANT,
                            content = content,
                            timestamp = System.currentTimeMillis(),
                            toolCalls = toolCalls,
                            toolResults = null
                        )

                        trySend(ChatApiRepository.ChatEvent.MessageAdded(domainMessage))
                    }
                } catch (e: Exception) {
                    trySend(ChatApiRepository.ChatEvent.Error(e.message ?: "Unknown error"))
                }
            }

            awaitClose { close() }
        } catch (e: Exception) {
            try {
                trySend(ChatApiRepository.ChatEvent.Error(e.message ?: "Unknown error"))
            } catch (_: Exception) {
                close(e)
            }
        }
    }

    override suspend fun sendChatRequest(
        apiKey: String,
        model: String,
        baseUrl: String?,
        messages: List<ApiChatMessage>,
        tools: List<com.aiassistant.data.model.api.Tool>
    ): ChatCompletionResponse {
        return chatRepository.sendChatRequest(
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            messages = messages,
            tools = tools
        )
    }

    override suspend fun streamChatResponse(
        apiKey: String,
        model: String,
        baseUrl: String?,
        messages: List<ApiChatMessage>
    ): Sequence<String> {
        return chatRepository.streamChatResponse(
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            messages = messages
        )
    }
}
