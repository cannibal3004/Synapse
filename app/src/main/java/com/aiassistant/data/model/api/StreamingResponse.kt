package com.aiassistant.data.model.api

import com.google.gson.annotations.SerializedName

data class StreamingChoice(
    val index: Int,
    val delta: Delta,
    val finish_reason: String?
)

data class Delta(
    val role: String?,
    val content: String?,
    val tool_calls: List<ToolCallDelta>?
)

data class ToolCallDelta(
    val index: Int,
    val id: String?,
    val type: String?,
    val function: FunctionDelta?
)

data class FunctionDelta(
    val name: String?,
    val arguments: String?
)

data class StreamingUsage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)
