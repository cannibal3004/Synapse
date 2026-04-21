package com.aiassistant.domain.model

data class MemoryEntry(
    val id: String,
    val conversationId: String,
    val content: String,
    val embedding: List<Float>,
    val timestamp: Long,
    val metadata: Map<String, String>? = null
)
