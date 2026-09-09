package com.aiassistant.data.model.api

import com.google.gson.annotations.SerializedName

/**
 * One `data:` frame of a streamed completion.
 *
 * Every field is nullable because providers differ in what they put in the first and last
 * frames -- an empty `choices` list, a usage-only frame, and role-only deltas are all normal.
 */
data class ChatCompletionChunk(
    val id: String? = null,
    val model: String? = null,
    val choices: List<StreamingChoice>? = null,
    val usage: StreamingUsage? = null
)

/** What a streamed round reports to its caller. */
sealed interface StreamEvent {
    /** Text as it arrives. Deltas are fragments, not whole lines. */
    data class Delta(val text: String) : StreamEvent

    /** End of the round, with everything reassembled. */
    data class Complete(
        val content: String,
        val toolCalls: List<ToolCall>,
        val finishReason: String?
    ) : StreamEvent
}

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
