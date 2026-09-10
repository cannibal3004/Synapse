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
import com.aiassistant.domain.model.TurnActivity
import com.google.gson.JsonParser
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
                title = {
                    Text(
                        text = uiState.conversationTitle.ifBlank { "New conversation" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
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
                // Only the top comes from the Scaffold. Its bottom padding is the navigation
                // bar inset, and adding the keyboard to that is a sum where it should be a max:
                // an open keyboard covers the navigation bar, so stacking them left a
                // navigation-bar-sized gap under the input. safeDrawing resolves the two to
                // whichever is larger.
                //
                // This also pairs with android:windowSoftInputMode="adjustResize" on
                // MainActivity and only makes sense alongside it -- left to adjustUnspecified
                // the system panned the whole window up instead, which this then added to.
                .padding(top = paddingValues.calculateTopPadding())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        ) {
            // The greeting and the list are alternatives, not siblings. Both used to claim
            // weight(1f), which is where the dead space top and bottom came from: an empty
            // conversation put the greeting in the upper half and an empty list in the lower.
            val showGreeting = uiState.messages.isEmpty() && !uiState.isLoading
            if (showGreeting) {
                GreetingPane(
                    modifier = Modifier.weight(1f),
                    isOnDevice = uiState.isOnDeviceMode,
                    onDeviceModel = uiState.onDeviceModelName,
                    cloudModel = uiState.model,
                    engineReady = uiState.onDeviceEngineReady,
                    onSuggestion = { userInput = it }
                )
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

            // Whether the tail is actually on screen. Checking the index alone was wrong: a
            // long reply is a single very tall item, so it stays the "last visible item" while
            // the user reads the middle of it -- and every delta then scrolled them back, which
            // is why dragging up snapped straight back down.
            val pinnedToTail by remember(listState) {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()
                        ?: return@derivedStateOf true
                    last.index == info.totalItemsCount - 1 &&
                        last.offset + last.size - info.viewportEndOffset <= AUTOSCROLL_SLACK_PX
                }
            }

            // Sending is a deliberate act, so it always takes you to the new message even if
            // you were reading further up -- unlike following a stream, which defers to wherever
            // you have scrolled. Animated, because it is a jump you asked for.
            val lastSentId = uiState.messages.lastOrNull { it.role == MessageRole.USER }?.id
            LaunchedEffect(lastSentId) {
                if (lastSentId == null) return@LaunchedEffect
                val target = listState.layoutInfo.totalItemsCount - 1
                if (target >= 0) listState.animateScrollToItem(target, SCROLL_TO_END_OFFSET)
            }

            LaunchedEffect(
                uiState.messages.size,
                uiState.streamingResponse,
                uiState.activity
            ) {
                if (!pinnedToTail) return@LaunchedEffect
                val target = listState.layoutInfo.totalItemsCount - 1
                // Not animated: deltas land many times a second and an animation per delta never
                // finishes, so the list crawls instead of keeping up. The offset is deliberately
                // larger than any item, which clamps to the very end -- scrolling to the item
                // alone parks its *top* at the top of the screen and leaves a long reply growing
                // out of sight below.
                if (target >= 0) listState.scrollToItem(target, SCROLL_TO_END_OFFSET)
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
                items(groupedMessages, key = ::messageGroupKey) { group ->
                    MessageGroupRow(group)
                }

                // Live row only while the turn is in flight. Once the reply is persisted it
                // carries its own activity and draws it directly above itself, so the row keeps
                // its position through the hand-off instead of being swapped for something else.
                if (uiState.isLoading) {
                    item(key = "activity") {
                        ActivityRow(activity = uiState.activity, running = true)
                    }
                }

                uiState.streamingResponse?.takeIf { it.isNotBlank() }?.let { partial ->
                    item(key = "streaming") {
                        // Same renderer as a finished turn, so the hand-off to the persisted
                        // message changes nothing on screen.
                        AssistantTurn(content = partial)
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
 * What fills the screen before the first message.
 *
 * No "New Conversation" button: this *is* the new conversation, so the only thing it could do is
 * replace an empty conversation with another one. What is actually worth knowing here is which
 * model the next message will reach, because that changes often and is otherwise buried in
 * Settings -- an answer that arrives in ninety seconds means something different depending on
 * whether it came from a 1.7B on the CPU or from an API.
 */
@Composable
private fun GreetingPane(
    isOnDevice: Boolean,
    onDeviceModel: String,
    cloudModel: String,
    engineReady: Boolean,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val target = when {
        isOnDevice -> onDeviceModel.removeSuffix(".litertlm").ifBlank { "on-device model" }
        cloudModel.isNotBlank() -> cloudModel
        else -> "no model selected"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "How can I help?",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isOnDevice) Icons.Default.PhoneAndroid else Icons.Default.Cloud,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = target,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isOnDevice && engineReady) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "\u00b7 loaded",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Tapping fills the input rather than sending, so a suggestion is a starting point
            // to edit instead of a commitment.
            GREETING_SUGGESTIONS.forEach { suggestion ->
                SuggestionChip(
                    onClick = { onSuggestion(suggestion) },
                    label = {
                        Text(
                            text = suggestion,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

private val GREETING_SUGGESTIONS = listOf(
    "Summarise a web page for me",
    "What can you do?",
    "Remember that I prefer metric units"
)

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

/**
 * A reply, streaming or finished.
 *
 * Markdown either way. Rendering the stream as plain text and the finished message as markdown
 * meant every reply reflowed the instant it landed -- different type sizes, different spacing --
 * which loses your place if you were already reading it. An unterminated code fence simply
 * renders as a code block until it closes, which is what the content is.
 */
@Composable
private fun AssistantTurn(content: String) {
    if (content.isBlank()) return
    MarkdownText(markdown = content, modifier = Modifier.fillMaxWidth())
}

private fun messageGroupKey(group: MessageGroup): Any =
    if (group.isAssistantToolCalls) {
        "tools-" + group.toolCalls.joinToString { it.id }
    } else {
        group.message!!.id
    }

@Composable
private fun MessageGroupRow(group: MessageGroup) {
    if (group.isAssistantToolCalls) {
        // Only reached by conversations recorded before the activity was stored on the reply.
        ToolCallIndicator(group.toolCalls)
        return
    }
    val message = group.message ?: return
    val activity = message.activity
    if (activity.isNullOrEmpty()) {
        MessageBubble(message)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivityRow(activity = activity, running = false)
            MessageBubble(message)
        }
    }
}

/** Slack when deciding "at the bottom", so a pixel of rounding does not stop the follow. */
private const val AUTOSCROLL_SLACK_PX = 64

/** Larger than any single item, so scrolling clamps to the very end of the list. */
private const val SCROLL_TO_END_OFFSET = 1_000_000

/**
 * Everything the model did this turn, in one row that never moves.
 *
 * Collapsed it shows only the latest step -- the tail of the current thought, or the tool being
 * run -- so a long turn always has a pulse without costing height. Expanded it replays the whole
 * turn in order, thoughts as text and tool calls as pills between them, which is the only place
 * the chronology is visible: the transcript shows the answer, not how it was reached.
 */
@Composable
private fun ActivityRow(activity: List<TurnActivity>, running: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    // A thought with nothing in it is not a step: it costs a blank line when expanded and an
    // empty caption when not. Drop those, and if that leaves nothing to say, draw nothing at
    // all rather than a chevron with a gap beside it.
    val steps = remember(activity) {
        activity.filterNot { it is TurnActivity.Thought && it.text.isBlank() }
    }
    if (steps.isEmpty() && !running) return

    val canExpand = steps.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canExpand) Modifier.clickable { expanded = !expanded } else Modifier)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Hide detail" else "Show detail",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            ActivitySummary(activity = steps, running = running, expanded = expanded)
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier.padding(start = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                steps.forEach { step ->
                    when (step) {
                        is TurnActivity.Thought -> Text(
                            text = step.text.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        is TurnActivity.ToolRun -> ToolPill(step)
                    }
                }
            }
        }
    }
}

/** The collapsed caption: the newest step, whatever kind it is. */
@Composable
private fun ActivitySummary(
    activity: List<TurnActivity>,
    running: Boolean,
    expanded: Boolean
) {
    val latest = activity.lastOrNull()
    if (expanded || latest == null) {
        Icon(
            imageVector = Icons.Default.Psychology,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (running) "Thinking\u2026" else "Detail",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        return
    }

    when (latest) {
        is TurnActivity.ToolRun -> {
            Icon(
                imageVector = getToolIcon(latest.name),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = latest.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        is TurnActivity.Thought -> {
            // The tail, not the head: reasoning arrives as one long run of deltas, and clipping
            // the front leaves a caption that never changes while the model works.
            val tail = remember(latest.text) {
                latest.text.trim().replace(Regex("\\s+"), " ").takeLast(REASONING_TAIL_CHARS)
            }
            Icon(
                imageVector = Icons.Default.Psychology,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = tail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ToolPill(tool: TurnActivity.ToolRun) {
    val summary = remember(tool.arguments) {
        parseToolArguments(tool.arguments)
            ?.joinToString(", ") { (key, value) -> "$key: $value" }
            ?.take(TOOL_PILL_MAX_CHARS)
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = getToolIcon(tool.name),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (summary.isNullOrBlank()) tool.name else "${tool.name} \u00b7 $summary",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private const val TOOL_PILL_MAX_CHARS = 60

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

    // Outer box carries no clip and is wider than the icon, so the badge has somewhere to sit.
    // It used to be a child of the clipped circle, which cut the corner off it.
    Box(modifier = Modifier.size(42.dp)) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .align(Alignment.BottomStart)
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
        }

        if (count > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // Sized to its content with a circular floor, so a two-digit count becomes
                    // a pill instead of overflowing a fixed circle.
                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1
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
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = toolCall.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            val fields = remember(toolCall.arguments) { parseToolArguments(toolCall.arguments) }
            if (fields == null) {
                // Arguments stream in fragment by fragment, so a call still being assembled is
                // not valid JSON yet. Showing the raw text beats showing nothing.
                Text(
                    text = toolCall.arguments,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                fields.forEach { (key, value) ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = key,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tool arguments as label/value pairs, or null when they are not a JSON object.
 *
 * Nested objects and arrays keep their JSON form -- there is no generic way to lay those out that
 * beats showing the structure, and in practice they are rare next to a url or an expression.
 */
private fun parseToolArguments(raw: String): List<Pair<String, String>>? = runCatching {
    JsonParser.parseString(raw).asJsonObject.entrySet().map { (key, value) ->
        val rendered = when {
            value.isJsonNull -> "null"
            value.isJsonPrimitive -> value.asString
            else -> value.toString()
        }
        key.replace('_', ' ') to rendered.take(TOOL_ARGUMENT_MAX_CHARS)
    }
}.getOrNull()?.takeIf { it.isNotEmpty() }

private const val TOOL_ARGUMENT_MAX_CHARS = 400

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
