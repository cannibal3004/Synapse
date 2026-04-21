package com.aiassistant.domain.repository

import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun getAllConversations(): Flow<List<Conversation>>
    suspend fun getConversationById(id: String): Conversation?
    suspend fun createConversation(title: String, systemPrompt: String? = null, model: String = "gpt-3.5-turbo"): String
    suspend fun updateConversationTitle(id: String, title: String)
    suspend fun deleteConversation(id: String)
}
