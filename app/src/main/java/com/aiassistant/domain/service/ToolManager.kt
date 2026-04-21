package com.aiassistant.domain.service

import com.aiassistant.domain.model.ToolCall
import com.aiassistant.domain.model.ToolResult
import com.google.gson.Gson

object ToolManager {
    private val gson = Gson()

    private val registeredTools = mutableMapOf<String, ToolDefinition>()

    data class ToolDefinition(
        val name: String,
        val description: String,
        val parameters: Any,
        val executor: (String) -> Result<String>
    )

    fun registerTool(definition: ToolDefinition) {
        registeredTools[definition.name] = definition
    }

    fun unregisterTool(name: String) {
        registeredTools.remove(name)
    }

    suspend fun executeToolCalls(
        toolCalls: List<ToolCall>,
        onProgress: (String, String) -> Unit = { _, _ -> }
    ): List<ToolResult> {
        return toolCalls.map { toolCall ->
            onProgress(toolCall.name, "Executing ${toolCall.name}...")

            val definition = registeredTools[toolCall.name]
            if (definition != null) {
                val result = definition.executor(toolCall.arguments)
                ToolResult(
                    toolCallId = toolCall.id,
                    name = toolCall.name,
                    result = result.getOrNull() ?: "Error: ${result.exceptionOrNull()?.message}"
                )
            } else {
                ToolResult(
                    toolCallId = toolCall.id,
                    name = toolCall.name,
                    result = "Error: Tool '${toolCall.name}' not found"
                )
            }
        }
    }

    fun buildToolDefinitions(): List<com.aiassistant.data.model.api.Tool> {
        return registeredTools.values.map {
            com.aiassistant.data.model.api.Tool(
                type = "function",
                function = com.aiassistant.data.model.api.FunctionDef(
                    name = it.name,
                    description = it.description,
                    parameters = it.parameters
                )
            )
        }
    }

    fun getToolNames(): Set<String> = registeredTools.keys
}
