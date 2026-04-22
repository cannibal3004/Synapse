package com.aiassistant.presentation.vm

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.data.scheduler.TaskScheduler
import com.aiassistant.domain.model.ScheduleType
import com.aiassistant.domain.model.ScheduledTask
import com.aiassistant.domain.model.TaskExecutionHistory
import com.aiassistant.domain.repository.TaskRepository
import com.aiassistant.domain.usecase.CronScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class TaskUiState(
    val tasks: List<ScheduledTask> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showCreateTask: Boolean = false,
    val editingTask: ScheduledTask? = null,
    val taskToDelete: ScheduledTask? = null,
    val showHistory: Boolean = false,
    val viewingTask: ScheduledTask? = null,
    val executionHistory: List<TaskExecutionHistory> = emptyList(),
    val isHistoryLoading: Boolean = false,
    val viewingExecutionHistory: TaskExecutionHistory? = null,
    val viewingExecutionTask: ScheduledTask? = null
)

data class TaskFormData(
    val title: String = "",
    val prompt: String = "",
    val scheduleType: ScheduleType = ScheduleType.ONCE,
    val cronExpression: String = "0 */6 * * *",
    val intervalMinutes: Long = 60,
    val maxRunDurationMinutes: Long = 30,
    val shouldNotify: Boolean = true,
    val systemPrompt: String? = null,
    val model: String? = null,
    val tags: String = ""
) {
    fun isValid(): Boolean {
        return title.isNotBlank() && prompt.isNotBlank()
    }
}

@HiltViewModel
class TaskViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val taskScheduler: TaskScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskUiState())
    val uiState: StateFlow<TaskUiState> = _uiState.asStateFlow()

    init {
        loadTasks()
    }

    fun loadTasks() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                taskRepository.getAllTasks().collect { tasks ->
                    _uiState.update {
                        it.copy(
                            tasks = tasks,
                            isLoading = false,
                            error = null
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("TaskViewModel", "Error loading tasks", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Error loading tasks: ${e.message}"
                    )
                }
            }
        }
    }

    fun showCreateTask() {
        _uiState.update {
            it.copy(
                showCreateTask = true,
                editingTask = null
            )
        }
    }

    fun hideCreateTask() {
        _uiState.update {
            it.copy(
                showCreateTask = false,
                editingTask = null
            )
        }
    }

    fun editTask(task: ScheduledTask) {
        _uiState.update {
            it.copy(
                showCreateTask = true,
                editingTask = task
            )
        }
    }

    fun deleteTask(task: ScheduledTask) {
        _uiState.update { it.copy(taskToDelete = task) }
    }

    fun confirmDeleteTask() {
        val task = _uiState.value.taskToDelete ?: return
        viewModelScope.launch {
            try {
                taskScheduler.cancelTask(task.id)
                taskRepository.deleteTask(task.id)
                _uiState.update { it.copy(taskToDelete = null) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Error deleting task: ${e.message}")
                }
            }
        }
    }

    fun cancelDeleteTask() {
        _uiState.update { it.copy(taskToDelete = null) }
    }

    fun toggleTask(task: ScheduledTask) {
        viewModelScope.launch {
            try {
                val newState = !task.isEnabled
                taskRepository.toggleTask(task.id, newState)
                if (newState) {
                    val updatedTask = task.copy(isEnabled = true)
                    taskScheduler.scheduleTask(updatedTask)
                } else {
                    taskScheduler.cancelTask(task.id)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Error toggling task: ${e.message}")
                }
            }
        }
    }

    fun runTaskNow(task: ScheduledTask) {
        viewModelScope.launch {
            try {
                taskScheduler.scheduleNow(task)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Error running task: ${e.message}")
                }
            }
        }
    }

    fun saveTask(formData: TaskFormData) {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val nextRunAt = calculateNextRunAt(formData, now)

                val existingTask = _uiState.value.editingTask

                val task = ScheduledTask(
                    id = existingTask?.id ?: UUID.randomUUID().toString(),
                    title = formData.title.trim(),
                    prompt = formData.prompt.trim(),
                    scheduleType = formData.scheduleType,
                    cronExpression = if (formData.scheduleType == ScheduleType.CRON) formData.cronExpression else null,
                    intervalMinutes = if (formData.scheduleType == ScheduleType.INTERVAL) formData.intervalMinutes else 0,
                    maxRunDurationMinutes = formData.maxRunDurationMinutes,
                    isEnabled = existingTask?.isEnabled ?: true,
                    shouldNotify = formData.shouldNotify,
                    lastRunAt = existingTask?.lastRunAt,
                    nextRunAt = nextRunAt,
                    createdAt = existingTask?.createdAt ?: now,
                    updatedAt = now,
                    runCount = existingTask?.runCount ?: 0,
                    lastError = existingTask?.lastError,
                    systemPrompt = formData.systemPrompt,
                    model = formData.model,
                    conversationId = existingTask?.conversationId,
                    tags = if (formData.tags.isNotBlank()) formData.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
                )

                if (existingTask != null) {
                    taskRepository.updateTask(task)
                    taskScheduler.scheduleTask(task)
                } else {
                    val taskId = taskRepository.insertTask(task)
                    taskScheduler.scheduleTask(task)
                }

                hideCreateTask()
            } catch (e: Exception) {
                Log.e("TaskViewModel", "Error saving task", e)
                _uiState.update {
                    it.copy(error = "Error saving task: ${e.message}")
                }
            }
        }
    }

    private fun calculateNextRunAt(formData: TaskFormData, afterMs: Long): Long {
        return when (formData.scheduleType) {
            ScheduleType.ONCE -> afterMs + 60 * 1000
            ScheduleType.INTERVAL -> afterMs + (formData.intervalMinutes * 60 * 1000)
            ScheduleType.CRON -> {
                try {
                    CronScheduler.nextRun(formData.cronExpression, afterMs)
                } catch (e: Exception) {
                    afterMs + (60 * 60 * 1000)
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun showHistory(task: ScheduledTask) {
        _uiState.update {
            it.copy(
                showHistory = true,
                viewingTask = task,
                isHistoryLoading = true,
                executionHistory = emptyList()
            )
        }
        loadExecutionHistory(task.id)
    }

    fun hideHistory() {
        _uiState.update {
            it.copy(
                showHistory = false,
                viewingTask = null,
                executionHistory = emptyList()
            )
        }
    }

    private fun loadExecutionHistory(taskId: String) {
        viewModelScope.launch {
            try {
                taskRepository.getExecutionHistory(taskId).collect { history ->
                    _uiState.update {
                        it.copy(
                            executionHistory = history,
                            isHistoryLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("TaskViewModel", "Error loading execution history", e)
                _uiState.update {
                    it.copy(
                        isHistoryLoading = false,
                        error = "Error loading history: ${e.message}"
                    )
                }
            }
        }
    }

    fun clearExecutionHistory(task: ScheduledTask) {
        viewModelScope.launch {
            try {
                taskRepository.deleteExecutionHistory(task.id)
                _uiState.update {
                    it.copy(
                        error = "Execution history cleared",
                        executionHistory = emptyList()
                    )
                }
                viewModelScope.launch {
                    kotlinx.coroutines.delay(3000)
                    clearError()
                }
            } catch (e: Exception) {
                Log.e("TaskViewModel", "Error clearing history", e)
                _uiState.update {
                    it.copy(error = "Error clearing history: ${e.message}")
                }
            }
        }
    }

    fun getScheduleDescription(task: ScheduledTask): String {
        return when (task.scheduleType) {
            ScheduleType.ONCE -> "One-time"
            ScheduleType.INTERVAL -> "Every ${task.intervalMinutes}m"
            ScheduleType.CRON -> task.cronExpression ?: "Cron"
        }
    }

    fun getNextRunDescription(task: ScheduledTask): String {
        return if (task.nextRunAt > 0) {
            val now = System.currentTimeMillis()
            val diff = task.nextRunAt - now
            if (diff <= 0) "Due now"
            else formatDuration(diff)
        } else {
            "Not scheduled"
        }
    }

    private fun formatDuration(ms: Long): String {
        val minutes = ms / (1000 * 60)
        val hours = ms / (1000 * 60 * 60)
        val days = ms / (1000 * 60 * 60 * 24)

        return when {
            days > 0 -> "$days day${if (days > 1) "s" else ""}"
            hours > 0 -> "$hours hour${if (hours > 1) "s" else ""}"
            minutes > 0 -> "$minutes minute${if (minutes > 1) "s" else ""}"
            else -> "Less than a minute"
        }
    }

    fun getRunStatus(task: ScheduledTask): String {
        return when {
            !task.isEnabled -> "Disabled"
            task.lastRunAt == null -> "Never run"
            else -> "Last run: ${formatTimestamp(task.lastRunAt)}"
        }
    }

    fun viewExecutionHistory(task: ScheduledTask, history: TaskExecutionHistory) {
        _uiState.update {
            it.copy(
                showHistory = false,
                viewingTask = null,
                viewingExecutionHistory = history,
                viewingExecutionTask = task
            )
        }
    }

    fun hideExecutionHistory() {
        _uiState.update {
            it.copy(
                viewingExecutionHistory = null,
                viewingExecutionTask = null
            )
        }
    }

    fun loadExecutionHistoryForTask(executionHistoryId: String) {
        viewModelScope.launch {
            try {
                taskRepository.getExecutionHistoryById(executionHistoryId).collect { history ->
                    if (history != null) {
                        _uiState.update {
                            it.copy(
                                viewingExecutionHistory = history,
                                viewingExecutionTask = _uiState.value.tasks.find { task -> task.id == history.taskId }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TaskViewModel", "Error loading execution history for deep link", e)
            }
        }
    }

    private fun formatTimestamp(ts: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - ts
        return when {
            diff < 60 * 1000 -> "just now"
            diff < 60 * 60 * 1000 -> "${diff / (60 * 1000)}m ago"
            diff < 24 * 60 * 60 * 1000 -> "${diff / (60 * 60 * 1000)}h ago"
            else -> "${diff / (24 * 60 * 60 * 1000)}d ago"
        }
    }
}
