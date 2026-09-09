package com.aiassistant.presentation.screen.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyRow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aiassistant.domain.model.Attachment
import com.aiassistant.domain.model.AttachmentType
import com.aiassistant.domain.model.ChatMessage
import com.aiassistant.domain.model.MessageRole
import com.aiassistant.domain.model.ToolCall
import com.aiassistant.presentation.vm.ChatViewModel
import com.aiassistant.presentation.vm.ChatUiState
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.PermissionStatus
import java.io.File

data class MessageGroup(
    val isAssistantToolCalls: Boolean,
    val toolCalls: List<ToolCall>,
    val message: ChatMessage?
)

@Composable
private fun AttachFileDialog(
    onDismiss: () -> Unit,
    onPhotosSelected: () -> Unit,
    onCameraSelected: () -> Unit,
    onFilesSelected: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach files") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(onClick = onPhotosSelected)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Photos",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(onClick = onCameraSelected)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Camera",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(onClick = onFilesSelected)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    @Suppress("DEPRECATION")
                    Icon(
                        imageVector = Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Files",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        },
        confirmButton = {}
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onNavigateToSettings: () -> Unit,
    onToggleDrawer: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var userInput by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var pendingAttachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var showAttachDialog by remember { mutableStateOf(false) }
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }

    val imagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        null
    }

    val storagePermission = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(Manifest.permission.READ_EXTERNAL_STORAGE)
    } else {
        null
    }

    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    fun handlePermission(permission: com.google.accompanist.permissions.PermissionState?): Boolean {
        return when {
            permission == null -> true
            permission.status is PermissionStatus.Granted -> true
            else -> {
                permission.launchPermissionRequest()
                false
            }
        }
    }

    fun processUris(uris: List<Uri>) {
        if (uris.isNotEmpty()) {
            viewModel.addAttachments(uris)
            pendingAttachments = pendingAttachments + uris.map { uri ->
                Attachment(
                    uri = uri,
                    type = AttachmentType.IMAGE,
                    fileName = uri.lastPathSegment ?: "file",
                    size = 0
                )
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        processUris(uris)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        processUris(uris)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = cameraPhotoUri
        if (success && uri != null) {
            viewModel.addAttachments(listOf(uri))
            pendingAttachments = pendingAttachments + Attachment(
                uri = uri,
                type = AttachmentType.IMAGE,
                fileName = "photo_${System.currentTimeMillis()}.jpg",
                size = 0
            )
        }
        cameraPhotoUri = null
    }

    fun openPhotoPicker() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                if (handlePermission(imagePermission)) {
                    photoPickerLauncher.launch("image/*")
                }
            }
            else -> {
                if (handlePermission(storagePermission)) {
                    photoPickerLauncher.launch("image/*")
                }
            }
        }
    }

    fun openCamera() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                if (handlePermission(cameraPermission)) {
                    val photoFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        photoFile
                    )
                    cameraPhotoUri = uri
                    cameraLauncher.launch(uri)
                }
            }
            else -> {
                val photoFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    photoFile
                )
                cameraPhotoUri = uri
                cameraLauncher.launch(uri)
            }
        }
    }

    fun openFilePicker() {
        filePickerLauncher.launch("*/*")
    }

    LaunchedEffect(conversationId) {
        if (conversationId == "new") {
            viewModel.createNewConversation(persistToDb = false)
        } else if (uiState.conversationId == null || uiState.conversationId != conversationId) {
            viewModel.loadConversation(conversationId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Assistant") },
                navigationIcon = {
                    IconButton(onClick = onToggleDrawer) {
                        Icon(Icons.Default.Menu, "Menu")
                    }
                },
                actions = {
                    var showMoreMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                    if (uiState.messages.isNotEmpty()) {
                        IconButton(onClick = { showMoreMenu = !showMoreMenu }) {
                            Icon(Icons.Default.MoreVert, "More options")
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Clear chat") },
                                onClick = {
                                    viewModel.clearMessages()
                                    showMoreMenu = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Clear, "Clear")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Conversation settings") },
                                onClick = {
                                    showMoreMenu = false
                                    showSettingsDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Info, "Info")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("New conversation") },
                                onClick = {
                                    viewModel.createNewConversation()
                                    showMoreMenu = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Add, "New")
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            // The greeting and the list are alternatives, not siblings. Both used to claim
            // weight(1f), which is where the dead space top and bottom came from: an empty
            // conversation put the greeting in the upper half and an empty list in the lower.
            val showGreeting = uiState.messages.isEmpty() && !uiState.isLoading
            if (showGreeting) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Lightbulb,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "How can I help you?",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { viewModel.createNewConversation() },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(Icons.Default.Add, "New", modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("New Conversation")
                        }
                    }
                }
            }

            val groupedMessages = remember(uiState.messages) {
                val groups = mutableListOf<MessageGroup>()
                var i = 0
                while (i < uiState.messages.size) {
                    val msg = uiState.messages[i]
                    if (msg.role == MessageRole.ASSISTANT && msg.toolCalls != null && msg.toolCalls.isNotEmpty()) {
                        val allToolCalls = mutableListOf<ToolCall>()
                        var j = i
                        while (j < uiState.messages.size && 
                               uiState.messages[j].role == MessageRole.ASSISTANT && 
                               uiState.messages[j].toolCalls != null && 
                               uiState.messages[j].toolCalls!!.isNotEmpty()) {
                            allToolCalls.addAll(uiState.messages[j].toolCalls!!)
                            j++
                        }
                        groups.add(MessageGroup(isAssistantToolCalls = true, toolCalls = allToolCalls, message = null))
                        i = j
                    } else {
                        groups.add(MessageGroup(isAssistantToolCalls = false, toolCalls = emptyList(), message = msg))
                        i++
                    }
                }
                groups
            }

            val listState = rememberLazyListState()

            // Whether the user is already at the tail. Following the stream unconditionally
            // would yank the list away from them while they read something earlier, which is
            // worse than not following at all.
            val pinnedToTail by remember(listState) {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()
                        ?: return@derivedStateOf true
                    last.index >= info.totalItemsCount - 2
                }
            }

            LaunchedEffect(
                uiState.messages.size,
                uiState.streamingResponse,
                uiState.onDeviceThinking
            ) {
                if (!pinnedToTail) return@LaunchedEffect
                val target = listState.layoutInfo.totalItemsCount - 1
                // Not animated: deltas land about fifteen times a second and an animation per
                // delta never finishes, so the list crawls instead of keeping up.
                if (target >= 0) listState.scrollToItem(target)
            }

            LazyColumn(
                modifier = if (showGreeting) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                },
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(groupedMessages, key = { 
                    if (it.isAssistantToolCalls) "tools-${it.toolCalls.map { tc -> tc.id }.joinToString()}" 
                    else it.message!!.id 
                }) { group ->
                    if (group.isAssistantToolCalls) {
                        ToolCallIndicator(group.toolCalls)
                    } else {
                        group.message?.let { MessageBubble(it) }
                    }
                }

                // Reasoning comes before the answer it produced, and while a turn is in
                // flight this row is the only thing moving on a tool-heavy round -- so it
                // carries the tail of the reasoning rather than just the word "Thinking".
                val thinking = uiState.onDeviceThinking?.takeIf { it.isNotBlank() }
                if (thinking != null || (uiState.isLoading && uiState.streamingResponse.isNullOrBlank())) {
                    item(key = "reasoning") {
                        ReasoningLine(text = thinking.orEmpty())
                    }
                }

                uiState.streamingResponse?.takeIf { it.isNotBlank() }?.let { partial ->
                    item(key = "streaming") {
                        // Rendered exactly like a finished assistant turn, so the hand-off to
                        // the persisted message is invisible.
                        AssistantTurn(content = partial, markdown = false)
                    }
                }

                uiState.onDeviceStats?.takeIf { it.isNotBlank() }?.let { stats ->
                    item(key = "stats") {
                        StatusLine(text = stats)
                    }
                }
            }

            if (uiState.error != null) {
                AlertBanner(
                    message = uiState.error!!,
                    onDismiss = { viewModel.clearError() }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            HorizontalDivider()

            AttachmentPreviewRow(
                attachments = uiState.pendingAttachments,
                onRemove = { uri ->
                    viewModel.removeAttachment(uri)
                },
                onClear = {
                    viewModel.clearAttachments()
                }
            )

            InputArea(
                input = userInput,
                onInputChange = { userInput = it },
                onSend = {
                    if (userInput.isNotBlank() || uiState.pendingAttachments.isNotEmpty()) {
                        viewModel.sendMessage(
                            userMessage = userInput,
                            attachments = uiState.pendingAttachments
                        )
                        userInput = ""
                    }
                },
                onAttachClick = {
                    showAttachDialog = true
                },
                enabled = !uiState.isLoading,
                canSend = userInput.isNotBlank() || uiState.pendingAttachments.isNotEmpty()
            )
        }
    }

    if (showAttachDialog) {
        AttachFileDialog(
            onDismiss = { showAttachDialog = false },
            onPhotosSelected = {
                showAttachDialog = false
                openPhotoPicker()
            },
            onCameraSelected = {
                showAttachDialog = false
                openCamera()
            },
            onFilesSelected = {
                showAttachDialog = false
                openFilePicker()
            }
        )
    }

    if (showSettingsDialog) {
        var systemPromptInput by remember { mutableStateOf(uiState.systemPrompt ?: "") }
        var selectedModel by remember { mutableStateOf(uiState.model) }
        var showModelPicker by remember { mutableStateOf(false) }

        val availableModels = listOf(
            "",
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4",
            "gpt-3.5-turbo",
            "claude-3.5-sonnet",
            "llama-3.1-405b",
            "mistral-large",
            "gemini-pro"
        )

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Conversation Settings") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Customize this conversation",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = systemPromptInput,
                        onValueChange = { systemPromptInput = it },
                        label = { Text("System Prompt") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6
                    )

                    Column {
                        OutlinedTextField(
                            value = selectedModel.ifEmpty { "(use default)" },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Model") },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = { showModelPicker = true }) {
                                    Icon(Icons.Default.ArrowDropDown, "Select model")
                                }
                            }
                        )
                        DropdownMenu(
                            expanded = showModelPicker,
                            onDismissRequest = { showModelPicker = false }
                        ) {
                            availableModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.ifEmpty { "(use default)" }) },
                                    onClick = {
                                        selectedModel = model
                                        showModelPicker = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveConversationSettings(
                        if (systemPromptInput.isBlank()) null else systemPromptInput,
                        selectedModel
                    )
                    showSettingsDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    systemPromptInput = uiState.systemPrompt ?: ""
                    selectedModel = uiState.model
                    showSettingsDialog = false
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * A turn in the transcript.
 *
 * The two roles are shaped differently on purpose. What the user said is short and benefits from
 * being visibly theirs, so it sits in a tinted bubble on the right, capped short of the full
 * column. A reply is long and often carries markdown, code or a table, so it gets the whole width
 * with no container competing with it -- a card around a code block wastes the only space that
 * matters on a phone.
 */
@Composable
fun MessageBubble(message: ChatMessage) {
    if (message.role == MessageRole.USER) UserTurn(message) else AssistantTurn(message.content)
}

@Composable
private fun AssistantTurn(content: String, markdown: Boolean = true) {
    if (content.isBlank()) return
    Box(modifier = Modifier.fillMaxWidth()) {
        if (markdown) {
            MarkdownText(markdown = content, modifier = Modifier.fillMaxWidth())
        } else {
            // A partial document has unbalanced fences and half-written tables, and re-parsing
            // all of it every delta is wasted work at 12 tok/s. The finished turn renders as
            // markdown a moment later.
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Collapsed reasoning. Shows the live tail so a long turn visibly has a pulse. */
@Composable
private fun ReasoningLine(text: String) {
    var expanded by remember { mutableStateOf(false) }
    val canExpand = text.isNotBlank()
    // The tail, not the first line: reasoning arrives as one long run of deltas, and clipping
    // the front would leave a caption that never changes while the model works.
    val tail = remember(text) {
        text.trim().replace(Regex("\\s+"), " ").takeLast(REASONING_TAIL_CHARS)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canExpand) Modifier.clickable { expanded = !expanded } else Modifier)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Hide reasoning" else "Show reasoning",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (expanded || !canExpand) "Thinking\u2026" else tail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp)
            )
        }
    }
}

/** Throughput and other after-the-fact notes, in the same visual key as [ReasoningLine]. */
@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp)
    )
}

private const val REASONING_TAIL_CHARS = 90

@Composable
private fun UserTurn(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(USER_BUBBLE_WIDTH),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (message.attachments.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    message.attachments.forEach { attachment ->
                        if (attachment.type == AttachmentType.IMAGE) {
                            AsyncImage(
                                model = attachment.uri,
                                contentDescription = attachment.fileName,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(MaterialTheme.shapes.medium),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            AttachmentBadge(attachment = attachment)
                        }
                    }
                    if (message.content.isNotBlank()) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            }
        }
    }
}

private const val USER_BUBBLE_WIDTH = 0.85f

@Composable
fun ToolCallIndicator(toolCalls: List<com.aiassistant.domain.model.ToolCall>) {
    val toolCounts = toolCalls.groupBy { it.name }.mapValues { it.value.size }
    val expandedTool = remember { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        toolCounts.forEach {(toolName, count) ->
            ToolCallIcon(
                toolName = toolName,
                count = count,
                isExpanded = expandedTool.value == toolName,
                onClick = {
                    expandedTool.value = if (expandedTool.value == toolName) null else toolName
                },
                toolCalls = toolCalls.filter { it.name == toolName }
            )
        }
    }

    toolCalls.forEach { toolCall ->
        AnimatedVisibility(
            visible = expandedTool.value == toolCall.name,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            ToolCallDetail(toolCall)
        }
    }
}

@Composable
fun ToolCallIcon(
    toolName: String,
    count: Int,
    isExpanded: Boolean,
    onClick: () -> Unit,
    toolCalls: List<com.aiassistant.domain.model.ToolCall>
) {
    val icon = getToolIcon(toolName)
    val tint = if (isExpanded) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                color = if (isExpanded)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "$toolName called $count time${if (count > 1) "s" else ""}",
            tint = tint,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        )

        if (count > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
fun ToolCallDetail(toolCall: com.aiassistant.domain.model.ToolCall) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            Text(
                text = toolCall.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = toolCall.arguments,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun getToolIcon(name: String) = when (name) {
    "web_search" -> Icons.Default.Search
    "calculator" -> Icons.Default.Calculate
    "weather" -> Icons.Default.Thunderstorm
    "web_fetch" -> Icons.Default.Language
    "code_interpreter" -> Icons.Default.Code
    "device_info" -> Icons.Default.Info
    else -> Icons.Default.Build
}

@Composable
fun AlertBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
fun InputArea(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachClick: () -> Unit,
    enabled: Boolean = true,
    canSend: Boolean = true
) {
    Column(
        modifier = Modifier
            .padding(8.dp)
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(
                onClick = onAttachClick,
                enabled = enabled,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = "Attach",
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            TextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    capitalization = KeyboardCapitalization.Sentences
                ),
                keyboardActions = KeyboardActions(
                    onSend = { onSend() }
                ),
                maxLines = 4,
                enabled = enabled,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = MaterialTheme.colorScheme.outline,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                    disabledIndicatorColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            FilledIconButton(
                onClick = onSend,
                // Was `(input.isNotBlank() || true)`, which is always true; an attachment with
                // no text is still sendable, so the caller decides.
                enabled = canSend && enabled
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}

@Composable
fun AttachmentPreviewRow(
    attachments: List<Attachment>,
    onRemove: (Uri) -> Unit,
    onClear: () -> Unit
) {
    if (attachments.isEmpty()) return

    Column(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${attachments.size} attachment${if (attachments.size > 1) "s" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            TextButton(onClick = onClear) {
                Text("Clear all")
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(attachments) { attachment ->
                AttachmentPreviewCard(
                    attachment = attachment,
                    onRemove = { onRemove(attachment.uri) }
                )
            }
        }
    }
}

@Composable
fun AttachmentPreviewCard(
    attachment: Attachment,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(120.dp)
            .height(100.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            if (attachment.type == AttachmentType.IMAGE) {
                AsyncImage(
                    model = attachment.uri,
                    contentDescription = attachment.fileName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = attachment.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onError
                )
            }
        }
    }
}

@Composable
fun AttachmentBadge(attachment: Attachment) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (attachment.type == AttachmentType.IMAGE) Icons.Default.Image else Icons.Default.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = attachment.fileName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
