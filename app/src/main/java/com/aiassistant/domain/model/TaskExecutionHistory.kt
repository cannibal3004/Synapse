package com.aiassistant.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class TaskExecutionHistory(
    val id: String,
    val taskId: String,
    val taskTitle: String,
    val success: Boolean,
    val response: String,
    val toolCallsUsed: List<String> = emptyList(),
    val error: String? = null,
    val startedAt: Long,
    val completedAt: Long,
    val createdAt: Long
) : Parcelable {
    val duration: String
        get() {
            val seconds = (completedAt - startedAt) / 1000
            return when {
                seconds < 60 -> "$seconds s"
                seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
                else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            }
        }
}
