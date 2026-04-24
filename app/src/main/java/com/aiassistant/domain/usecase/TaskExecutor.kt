package com.aiassistant.domain.usecase

import android.util.Log
import com.aiassistant.data.model.api.ChatMessage as ApiChatMessage
import com.aiassistant.data.model.api.ToolCall as ApiToolCall
import android.content.Context
import com.aiassistant.data.llm.OnDeviceLlmSettingsManager
import com.aiassistant.domain.llm.OnDeviceLlmEngine
import com.aiassistant.domain.model.ChatMessage
import com.aiassistant.domain.model.MessageRole
import com.aiassistant.domain.model.ScheduledTask
import com.aiassistant.domain.model.TaskExecutionResult
import com.aiassistant.domain.repository.ChatApiRepository
import com.aiassistant.domain.repository.MessageRepository
import com.aiassistant.domain.repository.OnDeviceLlmRepository
import com.aiassistant.domain.repository.TaskRepository
import com.aiassistant.domain.service.ToolManager
import com.aiassistant.domain.tool.OnDeviceToolExecutor
import com.aiassistant.domain.tool.ToolExecutor
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime
import javax.inject.Inject

class TaskExecutor @Inject constructor(
    private val chatApiRepository: ChatApiRepository,
    private val taskRepository: TaskRepository,
    private val toolExecutor: ToolExecutor,
    private val onDeviceLlmRepository: OnDeviceLlmRepository,
    private val onDeviceLlmSettingsManager: OnDeviceLlmSettingsManager,
    private val messageRepository: MessageRepository,
    @ApplicationContext private val applicationContext: Context
) {
    private val gson = Gson()
    private val onDeviceToolExecutor = OnDeviceToolExecutor(applicationContext)

    suspend fun executeTask(
        taskId: String,
        apiKey: String,
        baseUrl: String?,
        defaultModel: String,
        defaultSystemPrompt: String,
        onDevice: Boolean = false,
        onDeviceConversationId: String? = null,
        onProgress: ((progress: Float, message: String) -> Unit) = { _, _ -> }
    ): TaskExecutionResult {
        val task = taskRepository.getTaskById(taskId)
            ?: run {
                onProgress(0f, "Task not found")
                return TaskExecutionResult(
                    taskId = taskId,
                    taskTitle = "Unknown",
                    success = false,
                    response = "Task not found",
                    startedAt = System.currentTimeMillis(),
                    completedAt = System.currentTimeMillis(),
                    error = "Task not found"
                )
            }

        val startedAt = System.currentTimeMillis()
        onProgress(0f, "Starting task: ${task.title}")

        return if (onDevice) {
            executeOnDeviceTask(task, onDeviceConversationId, startedAt, onProgress)
        } else {
            executeCloudTask(task, apiKey, baseUrl, defaultModel, defaultSystemPrompt, startedAt, onProgress)
        }
    }

    private suspend fun executeCloudTask(
        task: com.aiassistant.domain.model.ScheduledTask,
        apiKey: String,
        baseUrl: String?,
        defaultModel: String,
        defaultSystemPrompt: String,
        startedAt: Long,
        onProgress: ((progress: Float, message: String) -> Unit)
    ): TaskExecutionResult {
        var toolCallsUsed = mutableListOf<String>()
        val maxRounds = 10

        return withContext(Dispatchers.IO) {
            try {
                val messages = buildTaskMessages(task, defaultSystemPrompt)
                val tools = ToolManager.buildToolDefinitions()
                var assistantContent = ""
                var round = 0
                var toolCalls: List<ApiToolCall>? = null

                do {
                    onProgress(30f + (round * 10f).coerceAtMost(20f), "Calling AI model...")
                    val response = chatApiRepository.sendChatRequest(
                        apiKey = apiKey,
                        model = task.model ?: defaultModel,
                        baseUrl = baseUrl,
                        messages = messages,
                        tools = tools
                    )

                    val assistantMessage = response.choices.firstOrNull()?.message
                    toolCalls = assistantMessage?.tool_calls

                    if (toolCalls != null && toolCalls.isNotEmpty()) {
                        onProgress(50f + (toolCalls.size * 10).toFloat().coerceAtMost(35f), "Processing ${toolCalls.size} tool call${if (toolCalls.size > 1) "s" else ""}...")
                        val domainToolCalls = toolCalls.map {
                            com.aiassistant.domain.model.ToolCall(
                                id = it.id,
                                name = it.function.name,
                                arguments = it.function.arguments
                            )
                        }
                        toolCallsUsed.addAll(domainToolCalls.map { it.name })

                        val toolResults = domainToolCalls.map { toolCall ->
                            val result = toolExecutor.executeTool(toolCall.name, toolCall.arguments)
                            com.aiassistant.domain.model.ToolResult(
                                toolCallId = toolCall.id,
                                name = toolCall.name,
                                result = result
                            )
                        }

                        toolResults.forEach { result ->
                            messages.add(
                                ApiChatMessage(
                                    role = "tool",
                                    content = result.result,
                                    tool_call_id = result.toolCallId
                                )
                            )
                        }

                        round++
                    } else {
                        assistantContent = assistantMessage?.content ?: ""
                    }
                } while (toolCalls != null && toolCalls.isNotEmpty() && round < maxRounds)

                val completedAt = System.currentTimeMillis()
                onProgress(90f, "Finalizing results...")
                val nextRunAt = calculateNextRunAt(task, completedAt)

                taskRepository.updateTaskRunState(
                    id = task.id,
                    lastRunAt = completedAt,
                    nextRunAt = nextRunAt,
                    lastError = null
                )

                onProgress(100f, "Task complete: ${task.title}")
                TaskExecutionResult(
                    taskId = task.id,
                    taskTitle = task.title,
                    success = true,
                    response = assistantContent,
                    startedAt = startedAt,
                    completedAt = completedAt,
                    toolCallsUsed = toolCallsUsed.toList(),
                    error = null
                )
            } catch (e: Exception) {
                onProgress(0f, "Task failed: ${e.message}")
                val completedAt = System.currentTimeMillis()
                val nextRunAt = calculateNextRunAt(task, completedAt)

                taskRepository.updateTaskRunState(
                    id = task.id,
                    lastRunAt = completedAt,
                    nextRunAt = nextRunAt,
                    lastError = e.message ?: "Unknown error"
                )

                TaskExecutionResult(
                    taskId = task.id,
                    taskTitle = task.title,
                    success = false,
                    response = "",
                    startedAt = startedAt,
                    completedAt = completedAt,
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }

    private suspend fun executeOnDeviceTask(
        task: com.aiassistant.domain.model.ScheduledTask,
        conversationId: String?,
        startedAt: Long,
        onProgress: ((progress: Float, message: String) -> Unit)
    ): TaskExecutionResult {
        return withContext(Dispatchers.IO) {
            try {
                val onDeviceSettings = onDeviceLlmSettingsManager.getSettings()

                onProgress(10f, "Checking model availability...")
                val modelPath = if (onDeviceLlmRepository.isModelAvailable(onDeviceSettings.modelName)) {
                    onDeviceLlmRepository.getModelPath(onDeviceSettings.modelName)
                } else {
                    onProgress(10f, "Downloading model: ${onDeviceSettings.modelName}...")
                    val result = onDeviceLlmRepository.downloadModel(
                        huggingfaceRepo = onDeviceSettings.huggingfaceRepo,
                        modelName = onDeviceSettings.modelName
                    ) { progress ->
                        onProgress(10f + progress * 50f, "Downloading model... ${String.format("%.0f", progress * 100)}%")
                    }
                    result.getOrNull() ?: run {
                        return@withContext TaskExecutionResult(
                            taskId = task.id,
                            taskTitle = task.title,
                            success = false,
                            response = "",
                            startedAt = startedAt,
                            completedAt = System.currentTimeMillis(),
                            error = "Failed to download model: ${result.exceptionOrNull()?.message}"
                        )
                    }
                }

               onProgress(60f, "Initializing model...")

               val needsReinit = onDeviceLlmRepository.needsReinitialize(
                    modelPath = modelPath,
                    systemPrompt = onDeviceSettings.systemPrompt,
                    temperature = onDeviceSettings.temperature,
                    topK = onDeviceSettings.topK,
                    topP = onDeviceSettings.topP,
                    useTools = true
                )

              val initResult = if (needsReinit) {
                    onDeviceLlmRepository.initializeModel(
                        modelPath = modelPath,
                        systemPrompt = onDeviceSettings.systemPrompt,
                        temperature = onDeviceSettings.temperature,
                        topK = onDeviceSettings.topK,
                        topP = onDeviceSettings.topP,
                        useTools = true
                    )
                } else {
                    Result.success(Unit)
                }

                if (!initResult.isSuccess) {
                    return@withContext TaskExecutionResult(
                        taskId = task.id,
                        taskTitle = task.title,
                        success = false,
                        response = "",
                        startedAt = startedAt,
                        completedAt = System.currentTimeMillis(),
                        error = "Failed to initialize model: ${initResult.exceptionOrNull()?.message}"
                    )
                }

                val domainMessages = if (conversationId != null) {
                    val history = messageRepository.getMessagesSync(conversationId)
                        .filter { it.role != MessageRole.SYSTEM }
                        .toMutableList()
                    
                    // Prepend task system prompt as system message
                    val systemPrompt = task.systemPrompt ?: onDeviceSettings.systemPrompt
                    if (!systemPrompt.isNullOrBlank()) {
                        val zdt = ZonedDateTime.now()
                        val currentDateTime = "${zdt.format(DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy 'at' hh:mm a z"))} (UTC${zdt.offset})"
                        val effectivePrompt = systemPrompt.replace("[CURRENT_DATE_TIME]", currentDateTime)
                        history.add(0, ChatMessage(
                            id = "task_system_${System.currentTimeMillis()}",
                            conversationId = conversationId,
                            role = MessageRole.SYSTEM,
                            content = effectivePrompt,
                            timestamp = System.currentTimeMillis()
                        ))
                    }
                    history
                } else {
                    // No conversation history - create a single user message from the task prompt
                    val zdt = ZonedDateTime.now()
                    val currentDateTime = "${zdt.format(DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy 'at' hh:mm a z"))} (UTC${zdt.offset})"
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

                onProgress(70f, "Running model inference...")
                var fullResponse = ""
                var chatError: String? = null

                onDeviceLlmRepository.resetConversation()
                onDeviceLlmRepository.chatStream(domainMessages).collect { event ->
                    when (event) {
                        is com.aiassistant.domain.llm.OnDeviceLlmEngine.ChatEvent.Chunk -> {
                            fullResponse += event.text
                            onProgress(70f + (fullResponse.length / 100f).coerceAtMost(20f), "Receiving response...")
                        }
                        is com.aiassistant.domain.llm.OnDeviceLlmEngine.ChatEvent.Done -> {
                            fullResponse = event.response
                            onProgress(90f, "Finalizing results...")
                        }
                        is com.aiassistant.domain.llm.OnDeviceLlmEngine.ChatEvent.Error -> {
                            chatError = event.error
                            onProgress(0f, "Error: ${event.error}")
                        }
                    }
                }

                val completedAt = System.currentTimeMillis()
                val nextRunAt = calculateNextRunAt(task, completedAt)

                taskRepository.updateTaskRunState(
                    id = task.id,
                    lastRunAt = completedAt,
                    nextRunAt = nextRunAt,
                    lastError = chatError
                )

                onProgress(100f, "Task complete: ${task.title}")
                TaskExecutionResult(
                    taskId = task.id,
                    taskTitle = task.title,
                    success = chatError == null,
                    response = fullResponse,
                    startedAt = startedAt,
                    completedAt = completedAt,
                    toolCallsUsed = emptyList(),
                    error = chatError
                )
            } catch (e: Exception) {
                onProgress(0f, "Task failed: ${e.message}")
                val completedAt = System.currentTimeMillis()
                val nextRunAt = calculateNextRunAt(task, completedAt)

                taskRepository.updateTaskRunState(
                    id = task.id,
                    lastRunAt = completedAt,
                    nextRunAt = nextRunAt,
                    lastError = e.message ?: "Unknown error"
                )

                TaskExecutionResult(
                    taskId = task.id,
                    taskTitle = task.title,
                    success = false,
                    response = "",
                    startedAt = startedAt,
                    completedAt = completedAt,
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }

    private fun buildTaskMessages(task: ScheduledTask, defaultSystemPrompt: String): MutableList<ApiChatMessage> {
        val messages = mutableListOf<ApiChatMessage>()

        val zdt = ZonedDateTime.now()
        val currentDateTime = "${zdt.format(DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy 'at' hh:mm a z"))} (UTC${zdt.offset})"
        
        val systemPrompt = task.systemPrompt ?: defaultSystemPrompt
        val effectivePrompt = systemPrompt.replace("[CURRENT_DATE_TIME]", currentDateTime)
        messages.add(ApiChatMessage("system", effectivePrompt))

        messages.add(ApiChatMessage("user", task.prompt))

        return messages
    }

    private fun calculateNextRunAt(task: ScheduledTask, completedAt: Long): Long {
        return when (task.scheduleType) {
            com.aiassistant.domain.model.ScheduleType.ONCE -> 0L
            com.aiassistant.domain.model.ScheduleType.INTERVAL -> {
                completedAt + (task.intervalMinutes * 60 * 1000)
            }
            com.aiassistant.domain.model.ScheduleType.CRON -> {
                try {
                    CronScheduler.nextRun(task.cronExpression ?: "", completedAt)
                } catch (e: Exception) {
                    completedAt + (60 * 60 * 1000)
                }
            }
        }
    }
}
