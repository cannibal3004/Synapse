package com.aiassistant.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aiassistant.data.notification.NotificationHelper
import com.aiassistant.data.repository.SettingsDataRepository
import com.aiassistant.domain.model.ScheduleType
import com.aiassistant.domain.model.TaskExecutionHistory
import com.aiassistant.domain.model.TaskExecutionResult
import com.aiassistant.domain.repository.TaskRepository
import com.aiassistant.domain.usecase.TaskExecutor
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class TaskWorker(
    context: Context,
    params: WorkerParameters,
    private val taskExecutor: TaskExecutor,
    private val taskRepository: TaskRepository,
    private val settingsRepository: SettingsDataRepository,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_TASK_TITLE = "task_title"
        const val KEY_RESULT = "result"
        const val TAG = "TaskWorker"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return notificationHelper.createTaskNotification(
            title = "Running task: ${getTaskTitle()}",
            text = "AI assistant is working on your task...",
            id = NotificationHelper.NOTIFICATION_CHANNEL_RUNNING_ID
        )
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val task = taskRepository.getTaskById(taskId)
            ?: return Result.failure()

        if (!task.isEnabled) {
            Log.d(TAG, "Task $taskId is disabled, skipping")
            return Result.failure()
        }

        val apiKey = settingsRepository.getApiKey() ?: ""
        if (apiKey.isBlank()) {
            Log.d(TAG, "No API key configured, cannot run task $taskId")
            return Result.failure()
        }

        val baseUrl = settingsRepository.getApiBaseUrl()
        val defaultModel = settingsRepository.getDefaultModel() ?: ""
        val defaultSystemPrompt = settingsRepository.getSystemPrompt() ?: ""

        return try {
            val result = taskExecutor.executeTask(
                taskId = taskId,
                apiKey = apiKey,
                baseUrl = baseUrl,
                defaultModel = defaultModel,
                defaultSystemPrompt = defaultSystemPrompt
            )

            if (result.success) {
                val history = TaskExecutionHistory(
                    id = java.util.UUID.randomUUID().toString(),
                    taskId = taskId,
                    taskTitle = task.title,
                    success = true,
                    response = result.response,
                    toolCallsUsed = result.toolCallsUsed,
                    error = null,
                    startedAt = result.startedAt,
                    completedAt = result.completedAt,
                    createdAt = System.currentTimeMillis()
                )
                taskRepository.insertExecutionHistory(history)

                if (task.shouldNotify) {
                    notificationHelper.notifyTaskComplete(result, history.id)
                }

                if (task.scheduleType == ScheduleType.ONCE) {
                    taskRepository.toggleTask(taskId, false)
                } else {
                    rescheduleTask(taskId, task)
                }

                Log.d(TAG, "Task $taskId completed successfully")
                val outputData = Data.Builder()
                    .putString(KEY_RESULT, com.google.gson.Gson().toJson(result))
                    .build()
                Result.success(outputData)
            } else {
                val errorHistory = TaskExecutionHistory(
                    id = java.util.UUID.randomUUID().toString(),
                    taskId = taskId,
                    taskTitle = task.title,
                    success = false,
                    response = "",
                    toolCallsUsed = emptyList(),
                    error = result.error,
                    startedAt = result.startedAt,
                    completedAt = result.completedAt,
                    createdAt = System.currentTimeMillis()
                )
                taskRepository.insertExecutionHistory(errorHistory)

                Log.e(TAG, "Task $taskId failed: ${result.error}")
                if (task.shouldNotify) {
                    notificationHelper.notifyTaskFailed(result, errorHistory.id)
                }
                rescheduleTask(taskId, task)
                Result.retry()
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Task $taskId timed out", e)
            handleTaskError(task, "Task timed out after ${task.maxRunDurationMinutes} minutes")
            rescheduleTask(taskId, task)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Task $taskId failed with exception", e)
            handleTaskError(task, e.message ?: "Unknown error")
            rescheduleTask(taskId, task)
            Result.retry()
        }
    }

    private suspend fun handleTaskError(task: com.aiassistant.domain.model.ScheduledTask, errorMsg: String) {
        val now = System.currentTimeMillis()
        val errorHistory = TaskExecutionHistory(
            id = java.util.UUID.randomUUID().toString(),
            taskId = task.id,
            taskTitle = task.title,
            success = false,
            response = "",
            toolCallsUsed = emptyList(),
            error = errorMsg,
            startedAt = now,
            completedAt = now,
            createdAt = now
        )
        taskRepository.insertExecutionHistory(errorHistory)

        taskRepository.updateTaskRunState(
            id = task.id,
            lastRunAt = now,
            nextRunAt = calculateNextRunAt(task),
            lastError = errorMsg
        )
        if (task.shouldNotify) {
            val errorResult = TaskExecutionResult(
                taskId = task.id,
                taskTitle = task.title,
                success = false,
                response = "",
                startedAt = now,
                completedAt = now,
                error = errorMsg
            )
            notificationHelper.notifyTaskFailed(errorResult, errorHistory.id)
        }
    }

    private suspend fun rescheduleTask(taskId: String, task: com.aiassistant.domain.model.ScheduledTask) {
        if (!task.isEnabled) return

        val nextRunAt = calculateNextRunAt(task)
        if (nextRunAt == 0L) return

        val workRequest = androidx.work.OneTimeWorkRequestBuilder<TaskWorker>()
            .setInitialDelay(0, TimeUnit.MILLISECONDS)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(
                        if (task.prompt.contains("search", true) || task.prompt.contains("fetch", true)) {
                            androidx.work.NetworkType.CONNECTED
                        } else {
                            androidx.work.NetworkType.UNMETERED
                        }
                    )
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30, TimeUnit.SECONDS
            )
            .setInputData(
                Data.Builder()
                    .putString(KEY_TASK_ID, taskId)
                    .putString(KEY_TASK_TITLE, task.title)
                    .build()
            )
            .addTag("task_$taskId")
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "task_$taskId",
            androidx.work.ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    private fun calculateNextRunAt(task: com.aiassistant.domain.model.ScheduledTask): Long {
        val now = System.currentTimeMillis()
        return when (task.scheduleType) {
            ScheduleType.ONCE -> 0L
            ScheduleType.INTERVAL -> {
                now + (task.intervalMinutes * 60 * 1000)
            }
            ScheduleType.CRON -> {
                try {
                    com.aiassistant.domain.usecase.CronScheduler.nextRun(
                        task.cronExpression ?: "",
                        now
                    )
                } catch (e: Exception) {
                    now + (60 * 60 * 1000)
                }
            }
        }
    }

    private fun getTaskTitle(): String {
        return inputData.getString(KEY_TASK_TITLE) ?: "Task"
    }
}
