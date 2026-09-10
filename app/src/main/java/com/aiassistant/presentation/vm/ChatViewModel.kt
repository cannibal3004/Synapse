package com.aiassistant.presentation.vm

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.data.llm.OnDeviceLlmSettingsManager
import com.aiassistant.data.model.api.ChatMessage as ApiChatMessage
import com.aiassistant.domain.repository.OnDeviceLlmRepository
import com.aiassistant.domain.service.ActiveConversation
import com.aiassistant.domain.tool.formatMemoryContext
import com.aiassistant.data.model.api.StreamEvent
import com.aiassistant.domain.model.TurnActivity
import com.aiassistant.domain.usecase.MemorySearchUseCase
import com.aiassistant.data.repository.SettingsDataRepository
import com.aiassistant.domain.llm.OnDeviceLlmEngine
import com.aiassistant.domain.model.Attachment
import com.aiassistant.domain.model.AttachmentType
import com.aiassistant.domain.model.ChatMessage
import com.aiassistant.domain.model.MessageRole
import com.aiassistant.domain.repository.ChatApiRepository
import com.aiassistant.domain.repository.ConversationRepository
import com.aiassistant.domain.repository.MessageRepository
import com.aiassistant.domain.service.ToolManager
import com.aiassistant.domain.tool.ToolExecutor
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime
import javax.inject.Inject
import com.aiassistant.data.repository.DEFAULT_MAX_TOOL_ROUNDS

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val conversationId: String? = null,
    val isNewConversation: Boolean = false,
    val systemPrompt: String? = null,
    val model: String = "",
    val pendingAttachments: List<Attachment> = emptyList(),
    val isOnDeviceMode: Boolean = false,
    val onDeviceDownloading: Boolean = false,
    val onDeviceDownloadProgress: Float = 0f,
    val onDeviceEngineReady: Boolean = false,
    /** What the model did this turn, in order. Reasoning and tool calls interleaved. */
    val activity: List<TurnActivity> = emptyList(),
    val onDeviceStats: String? = null,
    /** The reply as far as it has arrived, shown until the finished message is persisted. */
    val streamingResponse: String? = null,
    val onDeviceCapabilities: OnDeviceLlmEngine.ModelCapabilities? = null,
    /** Bundle filename in on-device mode; the cloud model name is [model]. */
    val onDeviceModelName: String = "",
    val conversationTitle: String = ""
)

private const val MEMORY_INJECTION_LIMIT = 5

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatApiRepository: ChatApiRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val toolExecutor: ToolExecutor,
    private val settingsRepository: SettingsDataRepository,
    private val onDeviceLlmRepository: OnDeviceLlmRepository,
    private val onDeviceLlmSettingsManager: OnDeviceLlmSettingsManager,
    private val memorySearchUseCase: MemorySearchUseCase,
    private val activeConversation: ActiveConversation,
    @ApplicationContext private val applicationContext: Context
) : ViewModel() {

    private val _apiKey = MutableStateFlow<String?>(null)
    private val _baseUrl = MutableStateFlow<String?>(null)
    private val _systemPrompt = MutableStateFlow<String?>(null)
    private val _maxToolRounds = MutableStateFlow(DEFAULT_MAX_TOOL_ROUNDS)
    private val _model = MutableStateFlow("")
    private val _isOnDeviceMode = MutableStateFlow(false)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val gson = Gson()

    init {
        Log.d("ChatViewModel", "ViewModel initialized")
        loadSettings()
        loadOnDeviceSettings()
        observeOnDeviceState()
        backfillMemoryEmbeddings()
    }

    /**
     * Gives vectors to memories stored before an embedding model was configured, so they become
     * findable. A no-op when embeddings are disabled or nothing is missing; capped per run.
     */
    private fun backfillMemoryEmbeddings() {
        viewModelScope.launch {
            runCatching { memorySearchUseCase.backfillEmbeddings() }
                .onFailure { Log.w("ChatViewModel", "Memory backfill failed", it) }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _apiKey.value = settings.apiKey
                _baseUrl.value = settings.apiBaseUrl
                _systemPrompt.value = settings.systemPrompt
                _maxToolRounds.value = settings.maxToolRounds
                _model.value = settings.defaultModel ?: ""
                Log.d("ChatViewModel", "Settings loaded: baseUrl=${settings.apiBaseUrl}, model=${settings.defaultModel}")
            }
        }
    }

    private fun loadOnDeviceSettings() {
        viewModelScope.launch {
            onDeviceLlmSettingsManager.settings.collect { settings ->
                _isOnDeviceMode.value = settings.enabled
                // isOnDeviceMode has been on ChatUiState since it was written and was never
                // assigned, so anything reading it saw false regardless of the setting.
                _uiState.value = _uiState.value.copy(
                    isOnDeviceMode = settings.enabled,
                    onDeviceModelName = settings.modelName
                )
                Log.d("ChatViewModel", "On-device settings loaded: enabled=${settings.enabled}")
            }
        }
    }

    private fun observeOnDeviceState() {
        viewModelScope.launch {
            onDeviceLlmRepository.state.collect { state ->
                _uiState.value = _uiState.value.copy(
                    onDeviceDownloading = state.isLoading,
                    onDeviceEngineReady = state.isReady,
                    onDeviceCapabilities = state.capabilities,
                    error = state.error
                )
                Log.d("ChatViewModel", "On-device state: ready=${state.isReady}, loading=${state.isLoading}")
            }
        }
    }

    fun createNewConversation(
        systemPrompt: String? = null,
        persistToDb: Boolean = true
    ) {
        viewModelScope.launch {
            try {
                Log.d("ChatViewModel", "Creating new conversation with model=${_model.value}, persistToDb=$persistToDb")
                if (persistToDb) {
                    val id = conversationRepository.createConversation(
                        title = "New Conversation",
                        systemPrompt = systemPrompt,
                        model = _model.value
                    )
                    messageRepository.deleteMessages(id)
                    _uiState.value = _uiState.value.copy(
                        conversationId = id,
                        isNewConversation = false,
                        messages = emptyList(),
                        // Cleared, not set to the placeholder row title: the top bar shows its
                        // own "New conversation" until the first message names this one.
                        conversationTitle = "",
                        systemPrompt = systemPrompt,
                        model = _model.value,
                        pendingAttachments = emptyList()
                    )
                    Log.d("ChatViewModel", "Conversation created: $id")
                } else {
                    _uiState.value = _uiState.value.copy(
                        conversationId = null,
                        isNewConversation = true,
                        messages = emptyList(),
                        conversationTitle = "",
                        systemPrompt = systemPrompt,
                        model = _model.value,
                        pendingAttachments = emptyList()
                    )
                    _systemPrompt.value = systemPrompt
                    Log.d("ChatViewModel", "New conversation created in memory only")
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error creating conversation", e)
                _uiState.value = _uiState.value.copy(
                    error = "Error: ${e.message}"
                )
            }
        }
    }

    fun loadConversation(conversationId: String) {
        viewModelScope.launch {
            try {
                Log.d("ChatViewModel", "Loading conversation: $conversationId")
                val conversation = conversationRepository.getConversationById(conversationId)
                val messages = messageRepository.getMessagesSync(conversationId)
                _uiState.value = _uiState.value.copy(
                    conversationId = conversationId,
                    messages = messages,
                    systemPrompt = conversation?.systemPrompt,
                    model = conversation?.model ?: _model.value,
                    conversationTitle = conversation?.title.orEmpty()
                )
                _systemPrompt.value = conversation?.systemPrompt
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error loading conversation", e)
                _uiState.value = _uiState.value.copy(
                    error = "Error: ${e.message}"
                )
            }
        }
    }

    fun addAttachments(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val attachments = uris.map { uri ->
                val fileName = uri.lastPathSegment ?: "unknown"
                val size = try {
                    applicationContext.contentResolver.openInputStream(uri)?.use { 
                        it.skip(Long.MAX_VALUE) - Long.MAX_VALUE + it.available().toLong() 
                    } ?: 0L
                } catch (e: Exception) {
                    0L
                }
                val type = when {
                    fileName.endsWith(".pdf") || fileName.endsWith(".txt") || 
                    fileName.endsWith(".md") || fileName.endsWith(".json") || 
                    fileName.endsWith(".xml") || fileName.endsWith(".csv") ||
                    fileName.endsWith(".html") || fileName.endsWith(".js") ||
                    fileName.endsWith(".py") || fileName.endsWith(".java") ||
                    fileName.endsWith(".kt") || fileName.endsWith(".ts") ||
                    fileName.endsWith(".tsx") || fileName.endsWith(".css") -> AttachmentType.DOCUMENT
                    else -> AttachmentType.IMAGE
                }
                Attachment(uri, type, fileName, size)
            }
            _uiState.value = _uiState.value.copy(
                pendingAttachments = _uiState.value.pendingAttachments + attachments
            )
        }
    }

    fun removeAttachment(uri: Uri) {
        val current = _uiState.value.pendingAttachments
        _uiState.value = _uiState.value.copy(
            pendingAttachments = current.filterNot { it.uri == uri }
        )
    }

    fun clearAttachments() {
        _uiState.value = _uiState.value.copy(pendingAttachments = emptyList())
    }

    fun sendMessage(userMessage: String, attachments: List<Attachment> = emptyList()) {
        if (!_uiState.value.isNewConversation && _uiState.value.conversationId == null) {
            return
        }

        viewModelScope.launch {
            val tempConversationId = _uiState.value.conversationId ?: "temp_new_${System.currentTimeMillis()}"
            val userMsg = ChatMessage(
                id = "temp_${System.currentTimeMillis()}",
                conversationId = tempConversationId,
                role = MessageRole.USER,
                content = userMessage,
                timestamp = System.currentTimeMillis(),
                attachments = attachments
            )
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages + userMsg,
                isLoading = true,
                error = null,
                pendingAttachments = emptyList()
            )

            try {
                var effectiveConversationId = tempConversationId
                if (_uiState.value.isNewConversation) {
                    val title = generateTitleFromMessage(userMessage)
                    effectiveConversationId = conversationRepository.createConversation(
                        title = title,
                        systemPrompt = _uiState.value.systemPrompt,
                        model = _uiState.value.model
                    )
                    _uiState.value = _uiState.value.copy(
                        conversationId = effectiveConversationId,
                        isNewConversation = false,
                        conversationTitle = title
                    )
                    Log.d("ChatViewModel", "Persisted new conversation: $effectiveConversationId")
                } else {
                    effectiveConversationId = tempConversationId!!
                }

                val userMessageId = messageRepository.addMessage(
                    conversationId = effectiveConversationId,
                    role = "user",
                    content = userMessage
                )

                // Tools receive only their arguments, so remember_fact reads provenance here.
                activeConversation.set(effectiveConversationId)

                val assistantContent = if (_isOnDeviceMode.value) {
                    getOnDeviceResponse(effectiveConversationId, userMessage, attachments)
                } else {
                    getCloudResponse(
                        effectiveConversationId,
                        userMessage,
                        attachments,
                        userMessageId
                    )
                }

                messageRepository.addMessage(
                    conversationId = effectiveConversationId,
                    role = "assistant",
                    content = assistantContent,
                    activity = _uiState.value.activity
                )

                val updatedMessages = messageRepository.getMessagesSync(effectiveConversationId)
                _uiState.value = _uiState.value.copy(
                    messages = updatedMessages,
                    isLoading = false,
                    streamingResponse = null,
                    activity = emptyList()
                )
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error sending message", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Error: ${e.message}",
                    streamingResponse = null
                )
            }
        }
    }

    private suspend fun getOnDeviceResponse(
        conversationId: String,
        userMessage: String,
        attachments: List<Attachment>
    ): String {
        val onDeviceSettings = onDeviceLlmSettingsManager.getSettings()

        val modelPath = if (onDeviceLlmRepository.isModelAvailable(onDeviceSettings.modelName)) {
            onDeviceLlmRepository.getModelPath(onDeviceSettings.modelName)
        } else {
            _uiState.value = _uiState.value.copy(onDeviceDownloading = true)
            val result = onDeviceLlmRepository.downloadModel(
                huggingfaceRepo = onDeviceSettings.huggingfaceRepo,
                modelName = onDeviceSettings.modelName
            ) { progress ->
                _uiState.value = _uiState.value.copy(onDeviceDownloadProgress = progress)
            }
            _uiState.value = _uiState.value.copy(onDeviceDownloading = false)

            result.getOrNull() ?: run {
                return "Error: Failed to download model - ${result.exceptionOrNull()?.message}"
            }
        }

       val needsReinit = onDeviceLlmRepository.needsReinitialize(
            modelPath = modelPath,
            systemPrompt = onDeviceSettings.systemPrompt,
            temperature = onDeviceSettings.temperature,
            topK = onDeviceSettings.topK,
            topP = onDeviceSettings.topP,
            useTools = true,
            enableThinking = onDeviceSettings.enableThinking,
            thinkingTokenBudget = onDeviceSettings.thinkingTokenBudget,
            maxOutputTokens = onDeviceSettings.maxOutputTokens,
            backend = onDeviceSettings.backend,
            contextTokens = onDeviceSettings.contextTokens
        )

        val initResult = if (needsReinit) {
            onDeviceLlmRepository.initializeModel(
                modelPath = modelPath,
                systemPrompt = onDeviceSettings.systemPrompt,
                temperature = onDeviceSettings.temperature,
                topK = onDeviceSettings.topK,
                topP = onDeviceSettings.topP,
                useTools = true,
                enableThinking = onDeviceSettings.enableThinking,
                thinkingTokenBudget = onDeviceSettings.thinkingTokenBudget,
                maxOutputTokens = onDeviceSettings.maxOutputTokens,
                backend = onDeviceSettings.backend,
                contextTokens = onDeviceSettings.contextTokens
            )
        } else {
            Result.success(Unit)
        }

        if (!initResult.isSuccess) {
            return "Error: Failed to initialize on-device model - ${initResult.exceptionOrNull()?.message}"
        }

        val domainMessages = messageRepository.getMessagesSync(conversationId)
            .filter { it.role != MessageRole.SYSTEM }
            .toMutableList()

        onDeviceLlmRepository.resetConversation()
        _uiState.value = _uiState.value.copy(
            activity = emptyList(),
            onDeviceStats = null,
            streamingResponse = null
        )

        var fullResponse = ""
        var chatError: String? = null
        // Channel content streams in as many small deltas (1124 chars over 229 events for a
        // one-word prompt), so it has to accumulate rather than replace.
        val thinkingText = StringBuilder()

        withContext(Dispatchers.IO) {
            onDeviceLlmRepository.chatStream(domainMessages, _maxToolRounds.value)
                .collect { event ->
                when (event) {
                    is OnDeviceLlmEngine.ChatEvent.Chunk -> {
                        fullResponse += event.text
                        _uiState.value = _uiState.value.copy(streamingResponse = fullResponse)
                    }
                    is OnDeviceLlmEngine.ChatEvent.Thinking -> recordThought(event.text)
                    is OnDeviceLlmEngine.ChatEvent.Done -> {
                        fullResponse = event.response
                        _uiState.value =
                            _uiState.value.copy(onDeviceStats = event.stats?.summary())
                    }
                    is OnDeviceLlmEngine.ChatEvent.Error -> {
                        chatError = event.error
                    }
                }
            }
        }

        return if (chatError != null) {
            "Error: On-device model failed to respond - $chatError"
        } else {
            fullResponse
        }
    }

    private suspend fun getCloudResponse(
        conversationId: String,
        userMessage: String,
        attachments: List<Attachment>,
        userMessageId: String
    ): String {
        val (content, apiAttachments) = processAttachments(userMessage, attachments)

        // The turn is already persisted by the time we get here, so the replay has to leave it
        // out -- it was going out twice, once as the stored plain text and once as the copy
        // built below. The copy is the one worth sending: it carries the images and any text
        // pulled out of an attached document, neither of which is in the stored content.
        val history = buildApiMessages(conversationId, userMessage, skipMessageId = userMessageId)

        val userApiMessage = if (apiAttachments.isNotEmpty()) {
            val contentList = mutableListOf<Map<String, Any>>()
            contentList.add(mapOf("type" to "text", "text" to content))
            contentList.addAll(apiAttachments)
            ApiChatMessage(
                role = "user",
                content = contentList
            )
        } else {
            ApiChatMessage("user", content)
        }
        history.add(userApiMessage)

        val tools = ToolManager.buildToolDefinitions()
        var round = 0
        val maxRounds = _maxToolRounds.value
        var toolCalls: List<com.aiassistant.data.model.api.ToolCall>? = null

        // Accumulated across rounds rather than taken from the last one: whatever was streamed
        // has already been shown, so the persisted message has to include it or the reply
        // changes when it lands.
        val streamed = StringBuilder()
        _uiState.value = _uiState.value.copy(streamingResponse = null, activity = emptyList())

        do {
            var roundToolCalls: List<com.aiassistant.data.model.api.ToolCall> = emptyList()
            var roundContent = ""

            chatApiRepository.streamChatCompletion(
                apiKey = _apiKey.value ?: "",
                model = _model.value,
                baseUrl = _baseUrl.value,
                messages = history,
                tools = tools
            ).collect { event ->
                when (event) {
                    is StreamEvent.Delta -> {
                        streamed.append(event.text)
                        _uiState.value =
                            _uiState.value.copy(streamingResponse = streamed.toString())
                    }
                    is StreamEvent.Reasoning -> recordThought(event.text)
                    is StreamEvent.Complete -> {
                        roundToolCalls = event.toolCalls
                        roundContent = event.content
                    }
                }
            }

            toolCalls = roundToolCalls.ifEmpty { null }

            if (toolCalls != null && toolCalls.isNotEmpty()) {
                val domainToolCalls = toolCalls.map {
                    com.aiassistant.domain.model.ToolCall(
                        id = it.id,
                        name = it.function.name,
                        arguments = it.function.arguments
                    )
                }

                recordToolRuns(domainToolCalls)

                val toolResults = withContext(Dispatchers.IO) {
                    domainToolCalls.map { toolCall ->
                        val result = toolExecutor.executeTool(toolCall.name, toolCall.arguments)
                        com.aiassistant.domain.model.ToolResult(
                            toolCallId = toolCall.id,
                            name = toolCall.name,
                            result = result
                        )
                    }
                }

                // The assistant turn that asked for the calls has to precede their results.
                // The protocol pairs every tool message with the tool_calls that produced it, and
                // a strict endpoint rejects a tool message that answers nothing -- which reads as
                // a model failure rather than a malformed request.
                history.add(
                    ApiChatMessage(
                        role = "assistant",
                        content = roundContent.ifBlank { null },
                        tool_calls = toolCalls
                    )
                )

                toolResults.forEach { result ->
                    history.add(
                        ApiChatMessage(
                            role = "tool",
                            content = result.result,
                            tool_call_id = result.toolCallId
                        )
                    )
                }

                round++
            }
        } while (toolCalls != null && toolCalls.isNotEmpty() && round < maxRounds)

        return streamed.toString()
    }

    /**
     * Retrieves stored facts relevant to [query] for injection into the prompt.
     *
     * Ranking is required here: unranked recent memories would be noise, so this returns null
     * rather than falling back when embeddings are unavailable. The model can still search
     * deliberately with the recall_facts tool.
     */
    /** Appends reasoning, merging into the previous thought so a run of deltas is one entry. */
    private fun recordThought(text: String) {
        val current = _uiState.value.activity
        val last = current.lastOrNull()
        val updated = if (last is TurnActivity.Thought) {
            current.dropLast(1) + TurnActivity.Thought(last.text + text)
        } else {
            current + TurnActivity.Thought(text)
        }
        _uiState.value = _uiState.value.copy(activity = updated)
    }

    private fun recordToolRuns(calls: List<com.aiassistant.domain.model.ToolCall>) {
        _uiState.value = _uiState.value.copy(
            activity = _uiState.value.activity +
                calls.map { TurnActivity.ToolRun(it.name, it.arguments) }
        )
    }

    private suspend fun memoryContextFor(query: String): String? {
        val memories = runCatching {
            memorySearchUseCase.getRelevantMemories(
                query = query,
                limit = MEMORY_INJECTION_LIMIT,
                fallbackToRecent = false
            )
        }.getOrElse { error ->
            Log.w("ChatViewModel", "Memory retrieval failed", error)
            return null
        }
        if (memories.isNotEmpty()) {
            Log.d("ChatViewModel", "Injecting ${memories.size} stored fact(s)")
        }
        return formatMemoryContext(memories)
    }

    private suspend fun processAttachments(
        userMessage: String,
        attachments: List<Attachment>
    ): Pair<String, List<Map<String, Any>>> {
        return withContext(Dispatchers.IO) {
            val apiAttachments = mutableListOf<Map<String, Any>>()
            var content = userMessage
            
            for (attachment in attachments) {
                when (attachment.type) {
                    AttachmentType.IMAGE -> {
                        val file = copyToCache(attachment.uri)
                        val base64String = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                        apiAttachments.add(
                            mapOf(
                                "type" to "image_url",
                                "image_url" to mapOf("url" to "data:image/png;base64,$base64String")
                            )
                        )
                    }
                    AttachmentType.DOCUMENT -> {
                        val file = copyToCache(attachment.uri)
                        val text = try {
                            when {
                                attachment.fileName.endsWith(".txt") || attachment.fileName.endsWith(".md") || 
                                attachment.fileName.endsWith(".json") || attachment.fileName.endsWith(".xml") ||
                                attachment.fileName.endsWith(".csv") || attachment.fileName.endsWith(".html") ||
                                attachment.fileName.endsWith(".js") || attachment.fileName.endsWith(".py") ||
                                attachment.fileName.endsWith(".java") || attachment.fileName.endsWith(".kt") ||
                                attachment.fileName.endsWith(".ts") || attachment.fileName.endsWith(".tsx") ||
                                attachment.fileName.endsWith(".css") -> {
                                    file.readText()
                                }
                                attachment.fileName.endsWith(".pdf") -> {
                                    null
                                }
                                else -> {
                                    file.readText()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ChatViewModel", "Error reading document: ${e.message}", e)
                            null
                        }
                        
                        if (text != null && text.isNotBlank()) {
                            val truncatedText = if (text.length > 10000) {
                                "${text.take(10000)}\n\n[... truncated - document continues for ${text.length - 10000} more characters ...]"
                            } else {
                                text
                            }
                            content = "$userMessage\n\n[Attached document: ${attachment.fileName}]\n\n$truncatedText"
                        } else if (attachment.fileName.endsWith(".pdf")) {
                            content = "$userMessage\n\n[Attached PDF: ${attachment.fileName}] - PDF text extraction is not available on this device. Please describe or paste the content you'd like me to analyze from this PDF."
                        }
                    }
                }
            }
            
            content to apiAttachments
        }
    }

    private suspend fun copyToCache(uri: Uri): File {
        return withContext(Dispatchers.IO) {
            val contentResolver = applicationContext.contentResolver
            val tempFile = File(applicationContext.cacheDir, "attachment_${System.currentTimeMillis()}_${uri.lastPathSegment}")
            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        }
    }

    private suspend fun buildApiMessages(
        conversationId: String,
        query: String,
        /** Excluded from the replay; the caller sends its own version of this message. */
        skipMessageId: String? = null
    ): MutableList<ApiChatMessage> {
        val history = mutableListOf<ApiChatMessage>()

        val zdt = java.time.ZonedDateTime.now()
        val currentDateTime = "${zdt.format(DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy 'at' hh:mm a z"))} (UTC${zdt.offset})"
        val effectivePrompt = (_systemPrompt.value ?: DEFAULT_SYSTEM_PROMPT).replace("[CURRENT_DATE_TIME]", currentDateTime)
        history.add(ApiChatMessage("system", effectivePrompt))

        memoryContextFor(query)?.let { history.add(ApiChatMessage("system", it)) }

        val messages = messageRepository.getMessagesSync(conversationId)
        messages.forEach { msg ->
            if (msg.id != skipMessageId && msg.content.isNotBlank()) {
                history.add(
                    ApiChatMessage(
                        role = msg.role.name.lowercase(),
                        content = msg.content
                    )
                )
            }
        }

        return history
    }

    private fun generateTitleFromMessage(message: String): String {
        val firstLine = message.trimIndent().trim()
            .split('\n')
            .firstOrNull()
            ?.trim()
            ?: message.trim()
        
        return if (firstLine.length > 40) {
            firstLine.take(40).trim() + "..."
        } else {
            firstLine
        }
    }

    fun clearMessages() {
        val currentConversationId = _uiState.value.conversationId ?: return
        viewModelScope.launch {
            try {
                messageRepository.deleteMessages(currentConversationId)
                _uiState.value = _uiState.value.copy(
                    messages = emptyList(),
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Error: ${e.message}"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun saveConversationSettings(systemPrompt: String?, model: String) {
        val currentConversationId = _uiState.value.conversationId ?: return
        viewModelScope.launch {
            try {
                conversationRepository.updateConversationSettings(
                    currentConversationId,
                    systemPrompt,
                    model
                )
                _systemPrompt.value = systemPrompt
                _model.value = model
                _uiState.value = _uiState.value.copy(
                    systemPrompt = systemPrompt,
                    model = model,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Error: ${e.message}"
                )
            }
        }
    }

    fun updateApiKey(key: String?) {
        _apiKey.value = key
    }

    fun updateSystemPrompt(prompt: String?) {
        _systemPrompt.value = prompt
    }

    fun updateModel(model: String) {
        _model.value = model
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { onDeviceLlmRepository.shutdown() }
        Log.d("ChatViewModel", "ViewModel cleared, on-device engine shutdown")
    }
}
