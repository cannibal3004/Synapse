package com.aiassistant.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aiassistant.data.model.ConversationEntity
import com.aiassistant.data.model.MemoryEntryEntity
import com.aiassistant.data.model.MessageEntity
import com.aiassistant.data.model.ScheduledTaskEntity
import com.aiassistant.data.model.TaskExecutionHistoryEntity

private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE scheduled_tasks ADD COLUMN onDevice INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntryEntity::class,
        ScheduledTaskEntity::class,
        TaskExecutionHistoryEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun taskExecutionHistoryDao(): TaskExecutionHistoryDao
}
