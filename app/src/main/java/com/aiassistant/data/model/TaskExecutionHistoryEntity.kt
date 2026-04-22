package com.aiassistant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "task_execution_history",
    foreignKeys = [
        ForeignKey(
            entity = ScheduledTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("taskId"), Index("createdAt")]
)
data class TaskExecutionHistoryEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val taskTitle: String,
    val success: Boolean,
    val response: String,
    val toolCallsUsed: String = "",
    val error: String? = null,
    val startedAt: Long,
    val completedAt: Long,
    val createdAt: Long = System.currentTimeMillis()
)
