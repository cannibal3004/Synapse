package com.aiassistant.domain.repository

import com.aiassistant.domain.model.ChatMessage
import com.aiassistant.domain.model.ToolCall
import com.aiassistant.domain.model.ToolResult
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun getMessages(conversationId: String): Flow<List<ChatMessage>>
    suspend fun getMessagesSync(conversationId: String): List<ChatMessage>
    suspend fun addMessage(conversationId: String, role: String, content: String): String
    suspend fun addMessageWithToolCalls(
        conversationId: String,
        role: String,
        content: String,
        toolCalls: String
    ): String
    suspend fun addMessages(messages: List<ChatMessage>)
    suspend fun deleteMessages(conversationId: String)
}
