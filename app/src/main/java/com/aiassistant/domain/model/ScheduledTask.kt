package com.aiassistant.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

@Parcelize
data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val prompt: String,
    val scheduleType: ScheduleType,
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
    val tags: List<String> = emptyList(),
    val onDevice: Boolean = false
) : Parcelable

enum class ScheduleType {
    ONCE,
    INTERVAL,
    CRON
}

@Parcelize
data class TaskExecutionResult(
    val taskId: String,
    val taskTitle: String,
    val success: Boolean,
    val response: String,
    val startedAt: Long,
    val completedAt: Long,
    val toolCallsUsed: List<String> = emptyList(),
    val error: String? = null
) : Parcelable
