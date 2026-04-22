package com.aiassistant.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aiassistant.data.model.ConversationEntity
import com.aiassistant.data.model.MemoryEntryEntity
import com.aiassistant.data.model.MessageEntity
import com.aiassistant.data.model.ScheduledTaskEntity
import com.aiassistant.data.model.TaskExecutionHistoryEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntryEntity::class,
        ScheduledTaskEntity::class,
        TaskExecutionHistoryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun taskExecutionHistoryDao(): TaskExecutionHistoryDao
}
