package com.aiassistant.data.repository

import com.aiassistant.data.database.ScheduledTaskDao
import com.aiassistant.data.database.TaskExecutionHistoryDao
import com.aiassistant.data.model.ScheduledTaskEntity
import com.aiassistant.data.model.TaskExecutionHistoryEntity
import com.aiassistant.domain.model.ScheduleType
import com.aiassistant.domain.model.ScheduledTask
import com.aiassistant.domain.model.TaskExecutionHistory
import com.aiassistant.domain.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

class TaskRepositoryImpl @Inject constructor(
    private val taskDao: ScheduledTaskDao,
    private val historyDao: TaskExecutionHistoryDao
) : TaskRepository {

    override fun getAllTasks(): Flow<List<ScheduledTask>> {
        return taskDao.getAllTasks()
            .map { entities ->
                entities.map { it.toDomain() }
            }
    }

    override suspend fun getTaskById(id: String): ScheduledTask? {
        return taskDao.getTaskById(id)?.toDomain()
    }

    override suspend fun insertTask(task: ScheduledTask): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = ScheduledTaskEntity(
            id = id,
            title = task.title,
            prompt = task.prompt,
            scheduleType = task.scheduleType.name,
            cronExpression = task.cronExpression,
            intervalMinutes = task.intervalMinutes,
            maxRunDurationMinutes = task.maxRunDurationMinutes,
            isEnabled = task.isEnabled,
            shouldNotify = task.shouldNotify,
            lastRunAt = task.lastRunAt,
            nextRunAt = task.nextRunAt,
            createdAt = now,
            updatedAt = now,
            runCount = task.runCount,
            lastError = task.lastError,
            systemPrompt = task.systemPrompt,
            model = task.model,
            conversationId = task.conversationId,
            tags = task.tags.joinToString(","),
            onDevice = task.onDevice
        )
        taskDao.insertTask(entity)
        return id
    }

    override suspend fun updateTask(task: ScheduledTask) {
        val entity = ScheduledTaskEntity(
            id = task.id,
            title = task.title,
            prompt = task.prompt,
            scheduleType = task.scheduleType.name,
            cronExpression = task.cronExpression,
            intervalMinutes = task.intervalMinutes,
            maxRunDurationMinutes = task.maxRunDurationMinutes,
            isEnabled = task.isEnabled,
            shouldNotify = task.shouldNotify,
            lastRunAt = task.lastRunAt,
            nextRunAt = task.nextRunAt,
            createdAt = task.createdAt,
            updatedAt = System.currentTimeMillis(),
            runCount = task.runCount,
            lastError = task.lastError,
            systemPrompt = task.systemPrompt,
            model = task.model,
            conversationId = task.conversationId,
            tags = task.tags.joinToString(","),
            onDevice = task.onDevice
        )
        taskDao.updateTask(entity)
    }

    override suspend fun deleteTask(id: String) {
        taskDao.deleteTaskById(id)
    }

    override suspend fun toggleTask(id: String, isEnabled: Boolean) {
        taskDao.toggleTask(id, isEnabled)
    }

    override suspend fun updateTaskRunState(id: String, lastRunAt: Long, nextRunAt: Long, lastError: String?) {
        taskDao.updateTaskRunState(id, lastRunAt, nextRunAt, lastError)
    }

    override fun getPendingTasks(now: Long): Flow<List<ScheduledTask>> {
        return taskDao.getPendingTasks(now)
            .map { entities ->
                entities.map { it.toDomain() }
            }
    }

    override suspend fun getEarliestPendingTask(now: Long): ScheduledTask? {
        return taskDao.getEarliestPendingTask(now)?.toDomain()
    }

    override suspend fun insertExecutionHistory(history: TaskExecutionHistory) {
        val entity = TaskExecutionHistoryEntity(
            id = history.id,
            taskId = history.taskId,
            taskTitle = history.taskTitle,
            success = history.success,
            response = history.response,
            toolCallsUsed = history.toolCallsUsed.joinToString(","),
            error = history.error,
            startedAt = history.startedAt,
            completedAt = history.completedAt,
            createdAt = history.createdAt
        )
        historyDao.insert(entity)
    }

    override fun getExecutionHistory(taskId: String, limit: Int): Flow<List<TaskExecutionHistory>> {
        return historyDao.getHistoryForTask(taskId, limit)
            .map { entities ->
                entities.map { it.toDomain() }
            }
    }

    override fun getExecutionHistoryById(id: String): Flow<TaskExecutionHistory?> {
        return historyDao.getHistoryById(id)
            .map { entity ->
                entity?.toDomain()
            }
    }

    override suspend fun getLastExecution(taskId: String): TaskExecutionHistory? {
        return historyDao.getLastExecution(taskId)?.toDomain()
    }

    override suspend fun getExecutionCount(taskId: String): Int {
        return historyDao.getExecutionCount(taskId)
    }

    override suspend fun deleteExecutionHistory(taskId: String) {
        historyDao.deleteHistoryForTask(taskId)
    }

    private fun ScheduledTaskEntity.toDomain(): ScheduledTask {
        return ScheduledTask(
            id = id,
            title = title,
            prompt = prompt,
            scheduleType = ScheduleType.valueOf(scheduleType),
            cronExpression = cronExpression,
            intervalMinutes = intervalMinutes,
            maxRunDurationMinutes = maxRunDurationMinutes,
            isEnabled = isEnabled,
            shouldNotify = shouldNotify,
            lastRunAt = lastRunAt,
            nextRunAt = nextRunAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
            runCount = runCount,
            lastError = lastError,
            systemPrompt = systemPrompt,
            model = model,
            conversationId = conversationId,
            tags = if (tags.isNotBlank()) tags.split(",") else emptyList(),
            onDevice = onDevice
        )
    }

    private fun TaskExecutionHistoryEntity.toDomain(): TaskExecutionHistory {
        return TaskExecutionHistory(
            id = id,
            taskId = taskId,
            taskTitle = taskTitle,
            success = success,
            response = response,
            toolCallsUsed = if (toolCallsUsed.isNotBlank()) toolCallsUsed.split(",") else emptyList(),
            error = error,
            startedAt = startedAt,
            completedAt = completedAt,
            createdAt = createdAt
        )
    }
}
