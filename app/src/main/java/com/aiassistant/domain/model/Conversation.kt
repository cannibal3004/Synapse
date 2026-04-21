package com.aiassistant.domain.model

data class Conversation(
    val id: String,
    val title: String,
    val systemPrompt: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val model: String = "gpt-3.5-turbo"
)
