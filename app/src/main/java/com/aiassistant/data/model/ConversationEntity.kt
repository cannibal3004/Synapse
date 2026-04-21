package com.aiassistant.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val systemPrompt: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val model: String = "gpt-3.5-turbo"
)
