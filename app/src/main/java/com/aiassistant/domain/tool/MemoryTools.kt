package com.aiassistant.domain.tool

import android.util.Log
import com.aiassistant.domain.usecase.MemorySearchUseCase
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking

private const val TAG = "MemoryTools"
private const val MAX_RECALL_LIMIT = 20

/**
 * Memory tools, defined once as [OpenApiTool]s and used by both inference paths: directly by
 * `OnDeviceLlmEngine`, and via `ToolManager.registerOpenApiTool` + `ToolExecutor` for the
 * OpenAI-compatible API path. Keeping one definition means the schema the model sees and the code
 * that runs can not drift apart.
 *
 * `execute` is synchronous because that is the shape of the tool interface on both sides. The
 * blocking bridge is safe here: on-device tools run on `Dispatchers.Default` inside the engine's
 * flow, and API tools run inside `withContext(Dispatchers.IO)` in the chat loop.
 */
class RememberFactTool(
    private val memory: MemorySearchUseCase,
    private val conversationIdProvider: () -> String
) : OpenApiTool {

    private val gson = Gson()

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "remember_fact",
          "description": "Store a durable fact about the user so it can be recalled in future conversations. Use for stable preferences, personal details, and standing instructions the user would expect you to remember. Do not use for one-off task details, or for anything the user asks you to keep private.",
          "parameters": {
            "type": "object",
            "properties": {
              "fact": {
                "type": "string",
                "description": "The fact to store, written as a short self-contained statement (e.g. 'Prefers metric units', 'Works as a paramedic in Glasgow')"
              }
            },
            "required": ["fact"]
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        return try {
            val fact = JsonUtils.parseToJsonMap(paramsJsonString)["fact"] as? String ?: ""
            val result = runBlocking { memory.remember(fact, conversationIdProvider()) }
            val message = when (result) {
                is MemorySearchUseCase.RememberResult.Stored ->
                    if (result.embedded) {
                        "Stored."
                    } else {
                        "Stored, but without an embedding, so it will only be found by recent-first recall until embeddings are enabled."
                    }
                is MemorySearchUseCase.RememberResult.Duplicate ->
                    "Already remembered something equivalent: \"${result.existingContent}\". Nothing stored."
                is MemorySearchUseCase.RememberResult.Rejected ->
                    "Not stored: ${result.reason}"
            }
            gson.toJson(mapOf("result" to message))
        } catch (e: Exception) {
            Log.e(TAG, "remember_fact failed", e)
            gson.toJson(mapOf("result" to "Error: ${e.message}"))
        }
    }
}

class RecallFactsTool(
    private val memory: MemorySearchUseCase
) : OpenApiTool {

    private val gson = Gson()

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "recall_facts",
          "description": "Search previously stored facts about the user. The most relevant facts are already provided automatically each turn, so use this only to look for something specific that was not included.",
          "parameters": {
            "type": "object",
            "properties": {
              "query": {
                "type": "string",
                "description": "What to look for, e.g. 'dietary restrictions' or 'where the user lives'"
              },
              "limit": {
                "type": "integer",
                "description": "Maximum number of facts to return (default 5, max 20)"
              }
            },
            "required": ["query"]
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        return try {
            val args = JsonUtils.parseToJsonMap(paramsJsonString)
            val query = args["query"] as? String ?: ""
            val limit = ((args["limit"] as? Number)?.toInt() ?: 5).coerceIn(1, MAX_RECALL_LIMIT)

            val matches = runBlocking { memory.getRelevantMemories(query, limit) }
            val message = if (matches.isEmpty()) {
                "No stored facts matched."
            } else {
                matches.joinToString("\n") { "- ${it.content}" }
            }
            gson.toJson(mapOf("result" to message))
        } catch (e: Exception) {
            Log.e(TAG, "recall_facts failed", e)
            gson.toJson(mapOf("result" to "Error: ${e.message}"))
        }
    }
}

/** Formats retrieved memories for injection into a prompt. Returns null when there is nothing. */
fun formatMemoryContext(memories: List<com.aiassistant.domain.model.MemoryEntry>): String? {
    if (memories.isEmpty()) return null
    return buildString {
        append("Facts you have previously stored about the user:\n")
        memories.forEach { append("- ${it.content}\n") }
        append("Use these only where relevant; do not mention this list.")
    }
}
