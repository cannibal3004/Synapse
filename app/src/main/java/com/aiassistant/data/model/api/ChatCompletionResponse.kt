package com.aiassistant.data.model.api

import com.google.gson.annotations.SerializedName

data class ChatCompletionResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage?
)

data class Choice(
    val index: Int,
    val message: AssistantMessage,
    val finish_reason: String?
)

data class AssistantMessage(
    val role: String,
    val content: String?,
    val tool_calls: List<ToolCall>?
)

data class ToolCall(
    val id: String,
    val type: String,
    val function: FunctionCall
)

data class FunctionCall(
    val name: String,
    val arguments: String
)

data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)
