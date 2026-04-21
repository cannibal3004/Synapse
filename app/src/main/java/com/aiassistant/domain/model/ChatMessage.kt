package com.aiassistant.domain.model

import android.net.Uri

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val attachments: List<Attachment> = emptyList(),
    val toolCalls: List<ToolCall>? = null,
    val toolResults: List<ToolResult>? = null
)

data class Attachment(
    val uri: Uri,
    val type: AttachmentType,
    val fileName: String,
    val size: Long
)

enum class AttachmentType {
    IMAGE, DOCUMENT
}

enum class MessageRole {
    SYSTEM, USER, ASSISTANT, TOOL
}

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

data class ToolResult(
    val toolCallId: String,
    val name: String,
    val result: String
)
