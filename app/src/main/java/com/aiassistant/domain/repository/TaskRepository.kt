package com.aiassistant.domain.repository

import com.aiassistant.domain.model.ScheduledTask
import com.aiassistant.domain.model.TaskExecutionHistory
import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    fun getAllTasks(): Flow<List<ScheduledTask>>
    suspend fun getTaskById(id: String): ScheduledTask?
    suspend fun insertTask(task: ScheduledTask): String
    suspend fun updateTask(task: ScheduledTask)
    suspend fun deleteTask(id: String)
    suspend fun toggleTask(id: String, isEnabled: Boolean)
    suspend fun updateTaskRunState(id: String, lastRunAt: Long, nextRunAt: Long, lastError: String?)
    fun getPendingTasks(now: Long = System.currentTimeMillis()): Flow<List<ScheduledTask>>
    suspend fun getEarliestPendingTask(now: Long = System.currentTimeMillis()): ScheduledTask?
    
    suspend fun insertExecutionHistory(history: TaskExecutionHistory)
    fun getExecutionHistory(taskId: String, limit: Int = 50): Flow<List<TaskExecutionHistory>>
    fun getExecutionHistoryById(id: String): Flow<TaskExecutionHistory?>
    suspend fun getLastExecution(taskId: String): TaskExecutionHistory?
    suspend fun getExecutionCount(taskId: String): Int
    suspend fun deleteExecutionHistory(taskId: String)
}
