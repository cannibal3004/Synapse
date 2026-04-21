package com.aiassistant.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aiassistant.data.model.ConversationEntity
import com.aiassistant.data.model.MessageEntity
import com.aiassistant.data.model.MemoryEntryEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
}
