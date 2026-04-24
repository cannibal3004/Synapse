package com.aiassistant.domain.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    val attachments: List<Attachment> = emptyList(),
    val toolCalls: List<ToolCall>? = null,
    val toolResults: List<ToolResult>? = null
) : Parcelable

@Parcelize
data class Attachment(
    val uri: Uri,
    val type: AttachmentType,
    val fileName: String,
    val size: Long
) : Parcelable

@Parcelize
enum class AttachmentType : Parcelable {
    IMAGE, DOCUMENT
}

@Parcelize
enum class MessageRole : Parcelable {
    SYSTEM, USER, ASSISTANT, TOOL
}

@Parcelize
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
) : Parcelable

@Parcelize
data class ToolResult(
    val toolCallId: String,
    val name: String,
    val result: String
) : Parcelable
