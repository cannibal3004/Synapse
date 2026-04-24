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
import com.aiassistant.domain.tool.OnDeviceToolExecutor
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
    val onDeviceEngineReady: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatApiRepository: ChatApiRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val toolExecutor: ToolExecutor,
    private val settingsRepository: SettingsDataRepository,
    private val onDeviceLlmRepository: OnDeviceLlmRepository,
    private val onDeviceLlmSettingsManager: OnDeviceLlmSettingsManager,
    @ApplicationContext private val applicationContext: Context
) : ViewModel() {

    private val _apiKey = MutableStateFlow<String?>(null)
    private val _baseUrl = MutableStateFlow<String?>(null)
    private val _systemPrompt = MutableStateFlow<String?>(null)
    private val _model = MutableStateFlow("")
    private val _isOnDeviceMode = MutableStateFlow(false)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val gson = Gson()
    private val onDeviceToolExecutor = OnDeviceToolExecutor(applicationContext)

    init {
        Log.d("ChatViewModel", "ViewModel initialized")
        loadSettings()
        loadOnDeviceSettings()
        observeOnDeviceState()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _apiKey.value = settings.apiKey
                _baseUrl.value = settings.apiBaseUrl
                _systemPrompt.value = settings.systemPrompt
                _model.value = settings.defaultModel ?: ""
                Log.d("ChatViewModel", "Settings loaded: baseUrl=${settings.apiBaseUrl}, model=${settings.defaultModel}")
            }
        }
    }

    private fun loadOnDeviceSettings() {
        viewModelScope.launch {
            onDeviceLlmSettingsManager.settings.collect { settings ->
                _isOnDeviceMode.value = settings.enabled
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
                    model = conversation?.model ?: _model.value
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
                        isNewConversation = false
                    )
                    Log.d("ChatViewModel", "Persisted new conversation: $effectiveConversationId")
                } else {
                    effectiveConversationId = tempConversationId!!
                }

                messageRepository.addMessage(
                    conversationId = effectiveConversationId,
                    role = "user",
                    content = userMessage
                )

                val assistantContent = if (_isOnDeviceMode.value) {
                    getOnDeviceResponse(effectiveConversationId, userMessage, attachments)
                } else {
                    getCloudResponse(effectiveConversationId, userMessage, attachments)
                }

                messageRepository.addMessage(
                    conversationId = effectiveConversationId,
                    role = "assistant",
                    content = assistantContent
                )

                val updatedMessages = messageRepository.getMessagesSync(effectiveConversationId)
                _uiState.value = _uiState.value.copy(
                    messages = updatedMessages,
                    isLoading = false
                )
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error sending message", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Error: ${e.message}"
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
            return "Error: Failed to initialize on-device model - ${initResult.exceptionOrNull()?.message}"
        }

        val domainMessages = messageRepository.getMessagesSync(conversationId)
            .filter { it.role != MessageRole.SYSTEM }
            .toMutableList()

        onDeviceLlmRepository.resetConversation()

        var fullResponse = ""
        var chatError: String? = null

        withContext(Dispatchers.IO) {
            onDeviceLlmRepository.chatStream(domainMessages).collect { event ->
                when (event) {
                    is OnDeviceLlmEngine.ChatEvent.Chunk -> {
                        fullResponse += event.text
                    }
                    is OnDeviceLlmEngine.ChatEvent.Done -> {
                        fullResponse = event.response
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
        attachments: List<Attachment>
    ): String {
        val (content, apiAttachments) = processAttachments(userMessage, attachments)

        val history = buildApiMessages(conversationId)

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
        var assistantContent = ""
        var round = 0
        val maxRounds = 10
        var toolCalls: List<com.aiassistant.data.model.api.ToolCall>? = null

        do {
            val response = chatApiRepository.sendChatRequest(
                apiKey = _apiKey.value ?: "",
                model = _model.value,
                baseUrl = _baseUrl.value,
                messages = history,
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

                messageRepository.addMessageWithToolCalls(
                    conversationId = conversationId,
                    role = "assistant",
                    content = "",
                    toolCalls = gson.toJson(domainToolCalls)
                )

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
            } else {
                assistantContent = assistantMessage?.content ?: ""
            }
        } while (toolCalls != null && toolCalls.isNotEmpty() && round < maxRounds)

        return assistantContent
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

    private suspend fun buildApiMessages(conversationId: String): MutableList<ApiChatMessage> {
        val history = mutableListOf<ApiChatMessage>()

        val zdt = java.time.ZonedDateTime.now()
        val currentDateTime = "${zdt.format(DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy 'at' hh:mm a z"))} (UTC${zdt.offset})"
        val effectivePrompt = (_systemPrompt.value ?: DEFAULT_SYSTEM_PROMPT).replace("[CURRENT_DATE_TIME]", currentDateTime)
        history.add(ApiChatMessage("system", effectivePrompt))

        val messages = messageRepository.getMessagesSync(conversationId)
        messages.forEach { msg ->
            if (msg.content.isNotBlank()) {
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
