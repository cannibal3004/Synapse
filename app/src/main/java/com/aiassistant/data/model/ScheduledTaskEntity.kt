package com.aiassistant.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val prompt: String,
    val scheduleType: String,
    val cronExpression: String? = null,
    val intervalMinutes: Long = 0,
    val maxRunDurationMinutes: Long = 30,
    val isEnabled: Boolean = true,
    val shouldNotify: Boolean = true,
    val lastRunAt: Long? = null,
    val nextRunAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val runCount: Int = 0,
    val lastError: String? = null,
    val systemPrompt: String? = null,
    val model: String? = null,
    val conversationId: String? = null,
    val tags: String = ""
)
