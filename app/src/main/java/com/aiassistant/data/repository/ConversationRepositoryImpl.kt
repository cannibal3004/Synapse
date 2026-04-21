package com.aiassistant.data.repository

import com.aiassistant.data.database.ConversationDao
import com.aiassistant.data.model.ConversationEntity
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class ConversationRepositoryImpl(
    private val conversationDao: ConversationDao
) : ConversationRepository {

    override fun getAllConversations(): Flow<List<Conversation>> {
        return conversationDao.getAllConversations()
            .map { entities ->
                entities.map { it.toDomain() }
            }
    }

    override suspend fun getConversationById(id: String): Conversation? {
        return conversationDao.getConversationById(id)?.toDomain()
    }

    override suspend fun createConversation(
        title: String,
        systemPrompt: String?,
        model: String
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = ConversationEntity(
            id = id,
            title = title,
            systemPrompt = systemPrompt,
            createdAt = now,
            updatedAt = now,
            model = model
        )
        conversationDao.insertConversation(entity)
        return id
    }

    override suspend fun updateConversationTitle(id: String, title: String) {
        conversationDao.updateConversationTitle(id, title, System.currentTimeMillis())
    }

    override suspend fun deleteConversation(id: String) {
        val conversation = conversationDao.getConversationById(id)
        conversation?.let {
            conversationDao.deleteConversation(it)
        }
    }

    private fun ConversationEntity.toDomain(): Conversation {
        return Conversation(
            id = id,
            title = title,
            systemPrompt = systemPrompt,
            createdAt = createdAt,
            updatedAt = updatedAt,
            model = model
        )
    }
}
