package com.aiassistant.presentation.screen.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import com.aiassistant.domain.model.ScheduleType
import com.aiassistant.domain.model.ScheduledTask
import com.aiassistant.domain.model.TaskExecutionHistory
import com.aiassistant.presentation.screen.chat.MarkdownText
import com.aiassistant.presentation.vm.TaskFormData
import com.aiassistant.presentation.vm.TaskViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(
    onNavigateBack: () -> Unit,
    onToggleDrawer: () -> Unit,
    viewModel: TaskViewModel = hiltViewModel(),
    executionHistoryId: String? = null
) {
    LaunchedEffect(executionHistoryId) {
        if (!executionHistoryId.isNullOrBlank()) {
            viewModel.loadExecutionHistoryForTask(executionHistoryId)
        }
    }
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scheduled Tasks") },
                navigationIcon = {
                    Row {
                        IconButton(onClick = onToggleDrawer) {
                            Icon(Icons.Default.Menu, "Menu")
                        }
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::showCreateTask) {
                        Icon(Icons.Default.Add, "Add Task")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::showCreateTask,
                shape = MaterialTheme.shapes.large
            ) {
                Icon(Icons.Default.Add, "Add Task")
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            uiState.error?.let { error ->
                Snackbar(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    action = {
                        TextButton(onClick = viewModel::clearError) {
                            Text("Dismiss")
                        }
                    }
                ) {
                    Text(error)
                }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(5000)
                    viewModel.clearError()
                }
            }

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (uiState.tasks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No scheduled tasks yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Add a task to let the AI work for you on a schedule",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.tasks, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onToggle = { viewModel.toggleTask(task) },
                            onEdit = { viewModel.editTask(task) },
                            onDelete = { viewModel.deleteTask(task) },
                            onRunNow = { viewModel.runTaskNow(task) },
                            onViewHistory = { viewModel.showHistory(task) },
                            scheduleDescription = viewModel.getScheduleDescription(task),
                            nextRunDescription = viewModel.getNextRunDescription(task),
                            runStatus = viewModel.getRunStatus(task)
                        )
                    }
                }
            }
        }
    }

    if (uiState.showCreateTask) {
        TaskFormDialog(
            task = uiState.editingTask,
            onDismiss = viewModel::hideCreateTask,
            onSave = viewModel::saveTask
        )
    }

    uiState.taskToDelete?.let { task ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDeleteTask,
            title = { Text("Delete Task") },
            text = { Text("Are you sure you want to delete \"${task.title}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmDeleteTask,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDeleteTask) {
                    Text("Cancel")
                }
            }
        )
    }

    if (uiState.showHistory) {
        val viewingTask = uiState.viewingTask ?: return@TaskScreen
        ExecutionHistoryDialog(
            task = viewingTask,
            executionHistory = uiState.executionHistory,
            isHistoryLoading = uiState.isHistoryLoading,
            onDismiss = viewModel::hideHistory,
            onClearHistory = { viewModel.clearExecutionHistory(viewingTask) },
            onViewEntry = { viewModel.viewExecutionHistory(viewingTask, it) }
        )
    }

    (uiState.viewingExecutionHistory != null)
        .let { shouldShowDetail ->
            if (shouldShowDetail) {
                val entry = uiState.viewingExecutionHistory
                val viewingTask = uiState.viewingExecutionTask ?: return@let
                if (entry != null) {
                    TaskExecutionDetailScreen(
                        task = viewingTask,
                        entry = entry,
                        onNavigateBack = viewModel::hideExecutionHistory
                    )
                }
            }
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskCard(
    task: ScheduledTask,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRunNow: () -> Unit,
    onViewHistory: () -> Unit,
    scheduleDescription: String,
    nextRunDescription: String,
    runStatus: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (task.isEnabled) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (task.isEnabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    scheduleDescription,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                        if (task.shouldNotify) {
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        "Notify",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                )
                            )
                        }
                    }
                }

                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (task.isEnabled) Icons.Default.CheckCircle
                        else Icons.Default.Circle,
                        contentDescription = if (task.isEnabled) "Disable" else "Enable",
                        tint = if (task.isEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = task.prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                maxLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = nextRunDescription,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = runStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (task.lastError != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Error: ${task.lastError}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Divider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onRunNow) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Run Now")
                }
                TextButton(onClick = onViewHistory) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("History")
                }
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit")
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskExecutionDetailScreen(
    task: ScheduledTask,
    entry: TaskExecutionHistory,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Execution Result") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onNavigateBack) {
                        Text("Close")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (entry.success)
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    else
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (entry.success) Icons.Default.CheckCircle
                                else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (entry.success)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = if (entry.success) "Success" else "Failed",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (entry.success)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            text = formatTimestamp(entry.createdAt),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Task: ${task.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (entry.startedAt < entry.completedAt) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Duration: ${entry.duration}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (entry.toolCallsUsed.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tools: ${entry.toolCallsUsed.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    entry.error?.let { error ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Error: $error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (entry.response.isNotBlank() && entry.error == null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Response",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        MarkdownText(
                            markdown = entry.response,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskFormDialog(
    task: ScheduledTask?,
    onDismiss: () -> Unit,
    onSave: (TaskFormData) -> Unit
) {
    var formData by remember {
        mutableStateOf(
            TaskFormData(
                title = task?.title ?: "",
                prompt = task?.prompt ?: "",
                scheduleType = task?.scheduleType ?: ScheduleType.ONCE,
                cronExpression = task?.cronExpression ?: "0 */6 * * *",
                intervalMinutes = task?.intervalMinutes ?: 60,
                shouldNotify = task?.shouldNotify ?: true,
                systemPrompt = task?.systemPrompt,
                model = task?.model
            )
        )
    }
    var showCronHelp by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task != null) "Edit Task" else "New Task") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = formData.title,
                    onValueChange = { formData = formData.copy(title = it) },
                    label = { Text("Task Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = formData.prompt,
                    onValueChange = { formData = formData.copy(prompt = it) },
                    label = { Text("Prompt (sent to AI)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )

                Text("Schedule Type", style = MaterialTheme.typography.labelMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ScheduleType.values().forEach { type ->
                        FilterChip(
                            selected = formData.scheduleType == type,
                            onClick = { formData = formData.copy(scheduleType = type) },
                            label = { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }

                when (formData.scheduleType) {
                    ScheduleType.INTERVAL -> {
                        OutlinedTextField(
                            value = formData.intervalMinutes.toString(),
                            onValueChange = {
                                formData = formData.copy(
                                    intervalMinutes = it.toLongOrNull() ?: 0
                                )
                            },
                            label = { Text("Interval (minutes)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions.Default.copy(
                                keyboardType = KeyboardType.Number
                            )
                        )
                    }
                    ScheduleType.CRON -> {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = formData.cronExpression,
                                onValueChange = { formData = formData.copy(cronExpression = it) },
                                label = { Text("Cron Expression") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = { showCronHelp = !showCronHelp }) {
                                Icon(Icons.Default.Help, "Cron Help")
                            }
                        }
                        if (showCronHelp) {
                            HelpText()
                        }
                    }
                    ScheduleType.ONCE -> {}
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = formData.shouldNotify,
                        onCheckedChange = { formData = formData.copy(shouldNotify = it) }
                    )
                    Text("Notify when complete")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (formData.isValid()) {
                        onSave(formData)
                    }
                },
                enabled = formData.isValid()
            ) {
                Text(if (task != null) "Update" else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun HelpText() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Cron Format: minute hour day-of-month month day-of-week",
                style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Examples:", style = MaterialTheme.typography.labelSmall)
            Text("  0 */6 * * * - Every 6 hours", style = MaterialTheme.typography.bodySmall)
            Text("  0 9 * * 1 - 9am every Monday", style = MaterialTheme.typography.bodySmall)
            Text("  */30 * * * * - Every 30 minutes", style = MaterialTheme.typography.bodySmall)
            Text("  0 8 * * * - Every day at 8am", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExecutionHistoryDialog(
    task: ScheduledTask,
    executionHistory: List<TaskExecutionHistory>,
    isHistoryLoading: Boolean,
    onDismiss: () -> Unit,
    onClearHistory: () -> Unit,
    onViewEntry: (TaskExecutionHistory) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Execution History")
                TextButton(onClick = onClearHistory) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                if (isHistoryLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                } else if (executionHistory.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "No execution history yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(executionHistory, key = { it.id }) { entry ->
                            ExecutionHistoryEntry(
                                entry = entry,
                                onViewDetails = { onViewEntry(entry) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ExecutionHistoryEntry(
    entry: TaskExecutionHistory,
    onViewDetails: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onViewDetails),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.success)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (entry.success) Icons.Default.CheckCircle
                        else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (entry.success)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = if (entry.success) "Success" else "Failed",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (entry.success)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = formatTimestamp(entry.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (entry.startedAt < entry.completedAt) {
                Text(
                    text = "Duration: ${entry.duration}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (entry.toolCallsUsed.isNotEmpty()) {
                Text(
                    text = "Tools: ${entry.toolCallsUsed.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            entry.error?.let { error ->
                Text(
                    text = "Error: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (entry.response.isNotBlank()) {
                val preview = if (entry.response.length > 150)
                    entry.response.take(150) + "..."
                else
                    entry.response

                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            TextButton(
                onClick = onViewDetails,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(
                    Icons.Default.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("View Details")
            }
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
