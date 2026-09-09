package com.aiassistant.data.model.api

import com.google.gson.annotations.SerializedName

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val temperature: Double? = null,
    val tools: List<Tool>? = null,
    val toolChoice: String? = null
)

data class ChatMessage(
    val role: String,
    val content: Any? = null,
    val tool_call_id: String? = null,
    /**
     * Set only on an assistant turn that requested tools. Null elsewhere, and Gson omits nulls,
     * so ordinary messages serialise exactly as before.
     */
    val tool_calls: List<ToolCall>? = null
)

data class Tool(
    val type: String = "function",
    val function: FunctionDef
)

data class FunctionDef(
    val name: String,
    val description: String,
    val parameters: Any
)
