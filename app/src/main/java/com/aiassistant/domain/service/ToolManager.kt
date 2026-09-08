package com.aiassistant.domain.service

import com.aiassistant.domain.model.ToolCall
import com.aiassistant.domain.model.ToolResult
import com.google.gson.Gson
import com.google.gson.JsonObject

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

    /**
     * Registers a tool that already describes itself in OpenAPI form, reusing that single schema
     * for the API path instead of restating it here. Used by the memory tools, which are shared
     * with the on-device engine.
     */
    fun registerOpenApiTool(tool: com.google.ai.edge.litertlm.OpenApiTool) {
        val schema = gson.fromJson(tool.getToolDescriptionJsonString(), JsonObject::class.java)
        val name = schema.get("name").asString
        registeredTools[name] = ToolDefinition(
            name = name,
            description = schema.get("description")?.asString.orEmpty(),
            parameters = gson.fromJson(schema.get("parameters"), Map::class.java),
            executor = { arguments -> runCatching { tool.execute(arguments) } }
        )
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
