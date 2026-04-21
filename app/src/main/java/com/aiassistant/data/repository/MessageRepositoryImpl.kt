package com.aiassistant.data.repository

import com.aiassistant.data.database.MessageDao
import com.aiassistant.data.model.MessageEntity
import com.aiassistant.domain.model.ChatMessage
import com.aiassistant.domain.model.MessageRole
import com.aiassistant.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class MessageRepositoryImpl(
    private val messageDao: MessageDao
) : MessageRepository {

    override fun getMessages(conversationId: String): Flow<List<ChatMessage>> {
        return messageDao.getMessagesByConversation(conversationId)
            .map { entities ->
                entities.map { it.toDomain() }
            }
    }

    override suspend fun getMessagesSync(conversationId: String): List<ChatMessage> {
        return messageDao.getMessagesByConversationSync(conversationId)
            .map { it.toDomain() }
    }

    override suspend fun addMessage(
        conversationId: String,
        role: String,
        content: String
    ): String {
        val id = UUID.randomUUID().toString()
        val entity = MessageEntity(
            id = id,
            conversationId = conversationId,
            role = role,
            content = content,
            timestamp = System.currentTimeMillis()
        )
        messageDao.insertMessage(entity)
        return id
    }

    override suspend fun addMessageWithToolCalls(
        conversationId: String,
        role: String,
        content: String,
        toolCalls: String
    ): String {
        val id = UUID.randomUUID().toString()
        val entity = MessageEntity(
            id = id,
            conversationId = conversationId,
            role = role,
            content = content,
            timestamp = System.currentTimeMillis(),
            toolCalls = toolCalls
        )
        messageDao.insertMessage(entity)
        return id
    }

    override suspend fun addMessages(messages: List<ChatMessage>) {
        val entities = messages.map {
            MessageEntity(
                id = it.id,
                conversationId = it.conversationId,
                role = it.role.name.lowercase(),
                content = it.content,
                timestamp = it.timestamp,
                toolCalls = it.toolCalls?.let { tools -> com.google.gson.Gson().toJson(tools) },
                toolResults = it.toolResults?.let { results -> com.google.gson.Gson().toJson(results) }
            )
        }
        messageDao.insertMessages(entities)
    }

    override suspend fun deleteMessages(conversationId: String) {
        messageDao.deleteMessagesByConversation(conversationId)
    }

    private fun MessageEntity.toDomain(): ChatMessage {
        val gson = com.google.gson.Gson()
        return ChatMessage(
            id = id,
            conversationId = conversationId,
            role = when (role) {
                "system" -> MessageRole.SYSTEM
                "user" -> MessageRole.USER
                "assistant" -> MessageRole.ASSISTANT
                "tool" -> MessageRole.TOOL
                else -> MessageRole.USER
            },
            content = content,
            timestamp = timestamp,
            toolCalls = toolCalls?.let { gson.fromJson(it, Array<com.aiassistant.domain.model.ToolCall>::class.java) }?.toList(),
            toolResults = toolResults?.let { gson.fromJson(it, Array<com.aiassistant.domain.model.ToolResult>::class.java) }?.toList()
        )
    }
}
