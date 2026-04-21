package com.aiassistant.data.database

import androidx.room.*
import com.aiassistant.data.model.MemoryEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Insert
    suspend fun insertMemory(entry: MemoryEntryEntity): Long

    @Insert
    suspend fun insertMemories(entries: List<MemoryEntryEntity>)

    @Query("SELECT * FROM memory_entries WHERE conversationId = :conversationId ORDER BY timestamp DESC")
    fun getMemoriesByConversation(conversationId: String): Flow<List<MemoryEntryEntity>>

    @Query("SELECT * FROM memory_entries WHERE conversationId = :conversationId ORDER BY timestamp DESC")
    suspend fun getMemoriesByConversationSync(conversationId: String): List<MemoryEntryEntity>

    @Query("DELETE FROM memory_entries WHERE conversationId = :conversationId")
    suspend fun deleteMemoriesByConversation(conversationId: String)

    @Query("SELECT * FROM memory_entries ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMemories(limit: Int): List<MemoryEntryEntity>

    @Query("SELECT * FROM memory_entries LIMIT :limit")
    suspend fun getMemoriesForEmbedding(limit: Int = 100): List<MemoryEntryEntity>
}
