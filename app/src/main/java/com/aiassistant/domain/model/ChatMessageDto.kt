package com.aiassistant.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ChatMessageDto(
    val role: String,
    val content: String,
    val attachments: List<AttachmentDto> = emptyList(),
    val toolCalls: List<ToolCallDto>? = null,
    val toolResults: List<ToolResultDto>? = null
) : Parcelable {
    fun toChatMessage(id: String = "", conversationId: String = "", timestamp: Long = System.currentTimeMillis()): ChatMessage {
        return ChatMessage(
            id = id,
            conversationId = conversationId,
            role = MessageRole.valueOf(role),
            content = content,
            timestamp = timestamp,
            attachments = attachments.map { it.toAttachment() },
            toolCalls = toolCalls?.map { it.toToolCall() },
            toolResults = toolResults?.map { it.toToolResult() }
        )
    }

    companion object {
        fun fromChatMessage(msg: ChatMessage): ChatMessageDto {
            return ChatMessageDto(
                role = msg.role.name,
                content = msg.content,
                attachments = msg.attachments.map { AttachmentDto.fromAttachment(it) },
                toolCalls = msg.toolCalls?.map { ToolCallDto.fromToolCall(it) },
                toolResults = msg.toolResults?.map { ToolResultDto.fromToolResult(it) }
            )
        }
    }
}

@Parcelize
data class AttachmentDto(
    val uri: String,
    val type: String,
    val fileName: String,
    val size: Long
) : Parcelable {
    fun toAttachment(): Attachment {
        return Attachment(
            uri = android.net.Uri.parse(uri),
            type = AttachmentType.valueOf(type),
            fileName = fileName,
            size = size
        )
    }

    companion object {
        fun fromAttachment(a: Attachment): AttachmentDto {
            return AttachmentDto(
                uri = a.uri.toString(),
                type = a.type.name,
                fileName = a.fileName,
                size = a.size
            )
        }
    }
}

@Parcelize
data class ToolCallDto(
    val id: String,
    val name: String,
    val arguments: String
) : Parcelable {
    fun toToolCall(): ToolCall = ToolCall(id, name, arguments)
    companion object {
        fun fromToolCall(tc: ToolCall): ToolCallDto = ToolCallDto(tc.id, tc.name, tc.arguments)
    }
}

@Parcelize
data class ToolResultDto(
    val toolCallId: String,
    val name: String,
    val result: String
) : Parcelable {
    fun toToolResult(): ToolResult = ToolResult(toolCallId, name, result)
    companion object {
        fun fromToolResult(tr: ToolResult): ToolResultDto = ToolResultDto(tr.toolCallId, tr.name, tr.result)
    }
}
