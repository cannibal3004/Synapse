package com.aiassistant.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: String, // "user", "assistant", "system"
    val content: String,
    val timestamp: Long,
    val toolCalls: String? = null, // JSON serialized tool calls
    val toolResults: String? = null, // JSON serialized tool results
    // JSON serialized List<TurnActivity>: the reasoning and tool calls behind this reply, kept
    // so the transcript renders the same way after a restart as it did while it was generating.
    val activity: String? = null
)
