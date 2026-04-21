package com.aiassistant.data.repository

import com.aiassistant.data.database.MemoryDao
import com.aiassistant.data.model.MemoryEntryEntity
import com.aiassistant.domain.model.MemoryEntry
import com.aiassistant.domain.repository.MemoryRepository
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class MemoryRepositoryImpl(
    private val memoryDao: MemoryDao
) : MemoryRepository {

    override fun getMemories(conversationId: String): Flow<List<MemoryEntry>> {
        return memoryDao.getMemoriesByConversation(conversationId)
            .map { entities ->
                entities.map { it.toDomain() }
            }
    }

    override suspend fun getMemoriesSync(conversationId: String): List<MemoryEntry> {
        return memoryDao.getMemoriesByConversationSync(conversationId)
            .map { it.toDomain() }
    }

    override suspend fun addMemory(content: String, conversationId: String): String {
        val id = UUID.randomUUID().toString()
        val entity = MemoryEntryEntity(
            id = id,
            conversationId = conversationId,
            content = content,
            embedding = "[]",
            timestamp = System.currentTimeMillis()
        )
        memoryDao.insertMemory(entity)
        return id
    }

    override suspend fun addMemories(entries: List<MemoryEntry>) {
        val entities = entries.map {
            MemoryEntryEntity(
                id = it.id,
                conversationId = it.conversationId,
                content = it.content,
                embedding = com.google.gson.Gson().toJson(it.embedding),
                timestamp = it.timestamp,
                metadata = it.metadata?.let { meta -> com.google.gson.Gson().toJson(meta) }
            )
        }
        memoryDao.insertMemories(entities)
    }

    override suspend fun deleteMemories(conversationId: String) {
        memoryDao.deleteMemoriesByConversation(conversationId)
    }

    override suspend fun getSimilarMemories(query: String, limit: Int): List<MemoryEntry> {
        val allMemories = memoryDao.getRecentMemories(limit * 10)
        return allMemories.map { it.toDomain() }
    }

    override suspend fun addMemoryFromText(
        content: String,
        conversationId: String,
        embedding: List<Float>
    ) {
        val id = UUID.randomUUID().toString()
        val entity = MemoryEntryEntity(
            id = id,
            conversationId = conversationId,
            content = content,
            embedding = com.google.gson.Gson().toJson(embedding),
            timestamp = System.currentTimeMillis()
        )
        memoryDao.insertMemory(entity)
    }

    private fun MemoryEntryEntity.toDomain(): MemoryEntry {
        val gson = com.google.gson.Gson()
        return MemoryEntry(
            id = id,
            conversationId = conversationId,
            content = content,
            embedding = gson.fromJson(embedding, Array<Float>::class.java).toList(),
            timestamp = timestamp,
            metadata = metadata?.let { 
                val type = object : TypeToken<Map<String, String>>() {}.type
                gson.fromJson(it, type)
            }
        )
    }
}
