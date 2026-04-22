package com.aiassistant.domain.usecase

import com.aiassistant.data.model.api.ChatMessage as ApiChatMessage
import com.aiassistant.data.model.api.ToolCall as ApiToolCall
import com.aiassistant.domain.model.ScheduledTask
import com.aiassistant.domain.model.TaskExecutionResult
import com.aiassistant.domain.repository.ChatApiRepository
import com.aiassistant.domain.repository.TaskRepository
import com.aiassistant.domain.service.ToolManager
import com.aiassistant.domain.tool.ToolExecutor
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime
import javax.inject.Inject

class TaskExecutor @Inject constructor(
    private val chatApiRepository: ChatApiRepository,
    private val taskRepository: TaskRepository,
    private val toolExecutor: ToolExecutor
) {
    private val gson = Gson()

    suspend fun executeTask(
        taskId: String,
        apiKey: String,
        baseUrl: String?,
        defaultModel: String,
        defaultSystemPrompt: String
    ): TaskExecutionResult {
        val task = taskRepository.getTaskById(taskId)
            ?: return TaskExecutionResult(
                taskId = taskId,
                taskTitle = "Unknown",
                success = false,
                response = "Task not found",
                startedAt = System.currentTimeMillis(),
                completedAt = System.currentTimeMillis(),
                error = "Task not found"
            )

        val startedAt = System.currentTimeMillis()
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
                val nextRunAt = calculateNextRunAt(task, completedAt)

                taskRepository.updateTaskRunState(
                    id = task.id,
                    lastRunAt = completedAt,
                    nextRunAt = nextRunAt,
                    lastError = null
                )

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
