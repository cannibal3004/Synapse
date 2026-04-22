package com.aiassistant.data.worker

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aiassistant.data.llm.OnDeviceLlmSettingsManager
import com.aiassistant.data.notification.NotificationHelper
import com.aiassistant.data.repository.SettingsDataRepository
import com.aiassistant.domain.llm.OnDeviceLlmEngine
import com.aiassistant.domain.model.ChatMessage
import com.aiassistant.domain.model.MessageRole
import com.aiassistant.domain.model.ScheduleType
import com.aiassistant.domain.model.TaskExecutionHistory
import com.aiassistant.domain.model.TaskExecutionResult
import com.aiassistant.domain.repository.MessageRepository
import com.aiassistant.domain.repository.OnDeviceLlmRepository
import com.aiassistant.domain.repository.TaskRepository
import com.aiassistant.domain.usecase.CronScheduler
import com.aiassistant.domain.usecase.TaskExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class TaskWorker(
    context: Context,
    params: WorkerParameters,
    private val taskExecutor: TaskExecutor,
    private val taskRepository: TaskRepository,
    private val settingsRepository: SettingsDataRepository,
    private val notificationHelper: NotificationHelper,
    private val onDeviceLlmRepository: OnDeviceLlmRepository,
    private val onDeviceLlmSettingsManager: OnDeviceLlmSettingsManager,
    private val messageRepository: MessageRepository
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

        val notificationId = notificationHelper.getRunningTaskNotificationId(taskId)
        notificationHelper.updateRunningTaskNotification(
            context = applicationContext,
            taskId = taskId,
            title = "Running task: ${task.title}",
            text = "AI assistant is working on your task..."
        )

        val baseUrl = settingsRepository.getApiBaseUrl()
        val defaultModel = settingsRepository.getDefaultModel() ?: ""
        val defaultSystemPrompt = settingsRepository.getSystemPrompt() ?: ""
        val apiKey = settingsRepository.getApiKey() ?: ""

        if (!task.onDevice && apiKey.isBlank()) {
            Log.d(TAG, "No API key configured for cloud task $taskId")
            return Result.failure()
        }

        if (task.onDevice) {
            val onDeviceSettings = onDeviceLlmSettingsManager.getSettings()
            if (!onDeviceSettings.enabled) {
                Log.d(TAG, "On-Device mode is disabled globally, skipping on-device task $taskId")
                return Result.failure()
            }
        }

        return try {
            val result = if (task.onDevice) {
                executeOnDeviceTask(task, notificationId)
            } else {
                taskExecutor.executeTask(
                    taskId = taskId,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    defaultModel = defaultModel,
                    defaultSystemPrompt = defaultSystemPrompt,
                    onDevice = false,
                    onDeviceConversationId = null,
                    onProgress = { _, message ->
                        notificationHelper.updateRunningTaskNotification(
                            context = applicationContext,
                            taskId = taskId,
                            title = "Running task: ${task.title}",
                            text = message
                        )
                    }
                )
            }

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
                notificationHelper.cancelRunningTaskNotification(taskId)
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
                notificationHelper.cancelRunningTaskNotification(taskId)
                if (task.shouldNotify) {
                    notificationHelper.notifyTaskFailed(result, errorHistory.id)
                }
                rescheduleTask(taskId, task)
                Result.retry()
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Task $taskId timed out", e)
            notificationHelper.cancelRunningTaskNotification(taskId)
            handleTaskError(task, "Task timed out after ${task.maxRunDurationMinutes} minutes")
            rescheduleTask(taskId, task)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Task $taskId failed with exception", e)
            notificationHelper.cancelRunningTaskNotification(taskId)
            handleTaskError(task, e.message ?: "Unknown error")
            rescheduleTask(taskId, task)
            Result.retry()
        }
    }

    private fun executeOnDeviceTask(
        task: com.aiassistant.domain.model.ScheduledTask,
        notificationId: Int
    ): TaskExecutionResult {
        val startedAt = System.currentTimeMillis()

        Log.d(TAG, "Starting on-device inference via foreground thread for task: ${task.id}")

        val latch = CountDownLatch(1)
        val resultHolder = object {
            var success = false
            var response = ""
            var error: String? = null
            var completedAt = 0L
        }

        val handlerThread = HandlerThread("OnDeviceInference-${task.id}", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }

        try {
            Handler(handlerThread.looper).post(object : Runnable {
                override fun run() {
                    runBlocking {
                        try {
                            val onDeviceSettings = onDeviceLlmSettingsManager.getSettings()

                            notificationHandler(applicationContext, task.title, "Checking model availability...")

                            val modelPath = if (onDeviceLlmRepository.isModelAvailable(onDeviceSettings.modelName)) {
                                onDeviceLlmRepository.getModelPath(onDeviceSettings.modelName)
                            } else {
                                notificationHandler(applicationContext, task.title, "Downloading model...")
                                val result = onDeviceLlmRepository.downloadModel(
                                    huggingfaceRepo = onDeviceSettings.huggingfaceRepo,
                                    modelName = onDeviceSettings.modelName
                                ) { progress ->
                                    notificationHandler(applicationContext, task.title, "Downloading model... ${String.format("%.0f", progress * 100)}%")
                                }
                                result.getOrNull() ?: run {
                                    resultHolder.success = false
                                    resultHolder.response = ""
                                    resultHolder.error = "Failed to download model: ${result.exceptionOrNull()?.message}"
                                    resultHolder.completedAt = System.currentTimeMillis()
                                    latch.countDown()
                                    return@runBlocking
                                }
                            }

                            notificationHandler(applicationContext, task.title, "Initializing model...")

                            val needsReinit = onDeviceLlmRepository.needsReinitialize(
                                modelPath = modelPath,
                                systemPrompt = onDeviceSettings.systemPrompt,
                                temperature = onDeviceSettings.temperature,
                                topK = onDeviceSettings.topK,
                                topP = onDeviceSettings.topP,
                                useTools = true
                            )

                            if (needsReinit) {
                                val initResult = onDeviceLlmRepository.initializeModel(
                                    modelPath = modelPath,
                                    systemPrompt = onDeviceSettings.systemPrompt,
                                    temperature = onDeviceSettings.temperature,
                                    topK = onDeviceSettings.topK,
                                    topP = onDeviceSettings.topP,
                                    useTools = true
                                )

                                if (!initResult.isSuccess) {
                                    resultHolder.success = false
                                    resultHolder.response = ""
                                    resultHolder.error = "Failed to initialize model: ${initResult.exceptionOrNull()?.message}"
                                    resultHolder.completedAt = System.currentTimeMillis()
                                    latch.countDown()
                                    return@runBlocking
                                }
                            }

                            val zdt = java.time.ZonedDateTime.now()
                            val currentDateTime = "${zdt.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy 'at' hh:mm a z"))} (UTC${zdt.offset})"

                            val domainMessages = if (task.conversationId != null) {
                                val history = messageRepository.getMessagesSync(task.conversationId)
                                    .filter { it.role != MessageRole.SYSTEM }
                                    .toMutableList()
                                
                                val systemPrompt = task.systemPrompt ?: onDeviceSettings.systemPrompt
                                if (!systemPrompt.isNullOrBlank()) {
                                    val effectivePrompt = systemPrompt.replace("[CURRENT_DATE_TIME]", currentDateTime)
                                    history.add(0, ChatMessage(
                                        id = "task_system_${System.currentTimeMillis()}",
                                        conversationId = task.conversationId,
                                        role = MessageRole.SYSTEM,
                                        content = effectivePrompt,
                                        timestamp = System.currentTimeMillis()
                                    ))
                                }
                                history
                            } else {
                                val systemPrompt = (task.systemPrompt ?: onDeviceSettings.systemPrompt)
                                    ?.replace("[CURRENT_DATE_TIME]", currentDateTime)
                                    ?: "You are a helpful AI assistant."
                                
                                listOf(
                                    ChatMessage(
                                        id = "task_system_${System.currentTimeMillis()}",
                                        conversationId = "task_${task.id}",
                                        role = MessageRole.SYSTEM,
                                        content = systemPrompt,
                                        timestamp = System.currentTimeMillis()
                                    ),
                                    ChatMessage(
                                        id = "task_user_${System.currentTimeMillis()}",
                                        conversationId = "task_${task.id}",
                                        role = MessageRole.USER,
                                        content = task.prompt,
                                        timestamp = System.currentTimeMillis()
                                    )
                                ).toMutableList()
                            }

                            notificationHandler(applicationContext, task.title, "Running inference...")
                            Log.d(TAG, "Starting chat stream for task: ${task.id}")

                            var fullResponse = ""
                            var chatError: String? = null

                                onDeviceLlmRepository.chatStream(domainMessages).collect { event ->
                                when (event) {
                                    is OnDeviceLlmEngine.ChatEvent.Chunk -> {
                                        fullResponse += event.text
                                        notificationHandler(applicationContext, task.title, "Receiving response...")
                                    }
                                    is OnDeviceLlmEngine.ChatEvent.Done -> {
                                        fullResponse = event.response
                                    }
                                    is OnDeviceLlmEngine.ChatEvent.Error -> {
                                        chatError = event.error
                                    }
                                }
                            }

                            val completedAt = System.currentTimeMillis()
                            notificationHandler(applicationContext, task.title, "Inference complete")

                            Log.d(TAG, "Inference completed for task: ${task.id}, response length: ${fullResponse.length}")

                            if (chatError == null) {
                                taskRepository.updateTaskRunState(
                                    id = task.id,
                                    lastRunAt = completedAt,
                                    nextRunAt = calculateNextRunAt(task, completedAt),
                                    lastError = null
                                )
                            }

                            resultHolder.success = chatError == null
                            resultHolder.response = fullResponse
                            resultHolder.error = chatError
                            resultHolder.completedAt = completedAt

                        } catch (e: Exception) {
                            Log.e(TAG, "Inference failed for task: ${task.id}", e)
                            val completedAt = System.currentTimeMillis()
                            resultHolder.success = false
                            resultHolder.response = ""
                            resultHolder.error = e.message ?: "Unknown error"
                            resultHolder.completedAt = completedAt
                        } finally {
                            latch.countDown()
                        }
                    }
                }
            })

            val completed = latch.await(task.maxRunDurationMinutes.toLong(), TimeUnit.MINUTES)

            if (!completed) {
                Log.e(TAG, "Inference timed out for task: ${task.id}")
                val completedAt = System.currentTimeMillis()
                return TaskExecutionResult(
                    taskId = task.id,
                    taskTitle = task.title,
                    success = false,
                    response = "",
                    startedAt = startedAt,
                    completedAt = completedAt,
                    error = "Task timed out after ${task.maxRunDurationMinutes} minutes"
                )
            }

            return TaskExecutionResult(
                taskId = task.id,
                taskTitle = task.title,
                success = resultHolder.success,
                response = resultHolder.response,
                startedAt = startedAt,
                completedAt = resultHolder.completedAt,
                error = resultHolder.error
            )

        } finally {
            handlerThread.quitSafely()
            handlerThread.join(5000)
        }
    }

    private fun notificationHandler(context: Context, taskTitle: String, text: String) {
        val taskId = "on_device"
        notificationHelper.updateRunningTaskNotification(
            context = context,
            taskId = taskId,
            title = "Running task: $taskTitle",
            text = text
        )
    }

    private fun calculateNextRunAt(task: com.aiassistant.domain.model.ScheduledTask, completedAt: Long): Long {
        return when (task.scheduleType) {
            ScheduleType.ONCE -> 0L
            ScheduleType.INTERVAL -> {
                completedAt + (task.intervalMinutes * 60 * 1000)
            }
            ScheduleType.CRON -> {
                try {
                    CronScheduler.nextRun(task.cronExpression ?: "", completedAt)
                } catch (e: Exception) {
                    completedAt + (60 * 60 * 1000)
                }
            }
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
                    CronScheduler.nextRun(
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
