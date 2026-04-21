package com.aiassistant.domain.usecase

import com.aiassistant.domain.repository.ChatApiRepository
import kotlinx.coroutines.flow.Flow

class SendChatMessageUseCase(
    private val chatApiRepository: ChatApiRepository
) {
    suspend fun sendMessage(
        apiKey: String,
        model: String,
        baseUrl: String?,
        conversationId: String,
        userMessage: String,
        systemPrompt: String? = null,
        temperature: Double? = null
    ): Flow<ChatApiRepository.ChatEvent> {
        return chatApiRepository.sendChatMessage(
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            conversationId = conversationId,
            userMessage = userMessage,
            systemPrompt = systemPrompt,
            temperature = temperature
        )
    }
}
