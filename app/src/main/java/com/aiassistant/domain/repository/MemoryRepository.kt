package com.aiassistant.domain.repository

import com.aiassistant.domain.model.MemoryEntry
import kotlinx.coroutines.flow.Flow

interface MemoryRepository {
    fun getMemories(conversationId: String): Flow<List<MemoryEntry>>
    suspend fun getMemoriesSync(conversationId: String): List<MemoryEntry>
    suspend fun addMemory(content: String, conversationId: String): String
    suspend fun addMemories(entries: List<MemoryEntry>)
    suspend fun deleteMemories(conversationId: String)
    suspend fun getSimilarMemories(query: String, limit: Int = 5): List<MemoryEntry>
    suspend fun addMemoryFromText(content: String, conversationId: String, embedding: List<Float>)
}
