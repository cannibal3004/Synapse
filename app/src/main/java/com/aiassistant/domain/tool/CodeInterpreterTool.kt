package com.aiassistant.domain.tool

import com.aiassistant.domain.service.ToolManager
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.tools.shell.Global
import javax.inject.Inject

class CodeInterpreterTool @Inject constructor() {
    init {
        register()
    }

    fun register() {
        ToolManager.registerTool(
            ToolManager.ToolDefinition(
                name = "code_interpreter",
                description = "Execute a JavaScript code snippet and return the result. Use this for calculations, data processing, or running small programs. The code runs in a sandboxed environment.",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "code" to mapOf(
                            "type" to "string",
                            "description" to "The JavaScript code to execute"
                        ),
                        "language" to mapOf(
                            "type" to "string",
                            "description" to "The programming language (default: 'javascript')",
                            "enum" to listOf("javascript")
                        )
                    ),
                    "required" to listOf("code")
                ),
                executor = { arguments ->
                    runCatching {
                        val args = com.google.gson.Gson().fromJson(arguments, Map::class.java)
                        val code = args["code"] as? String ?: ""
                        val language = args["language"] as? String ?: "javascript"
                        executeCode(code, language)
                    }
                }
            )
        )
    }

    fun executeCode(code: String, language: String): String {
        return try {
            when (language.lowercase()) {
                "javascript" -> executeJavaScript(code)
                else -> "Error: Unsupported language '$language'. Only 'javascript' is supported."
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            "Error: $errorMsg"
        }
    }

    private fun executeJavaScript(code: String): String {
        val cx = Context.enter()
        return try {
            cx.setOptimizationLevel(-1)
            val global = Global(cx)
            val scope: Scriptable = cx.newObject(global)

            val result = cx.evaluateString(
                scope,
                code,
                "<code>",
                1,
                null
            )

            when (result) {
                is Undefined -> "Result: undefined"
                is Boolean, is Number, is String -> "Result: $result"
                is NativeObject -> {
                    val json = toJsonString(result)
                    if (json.length > 1000) json.substring(0, 1000) + "..." else json
                }
                is NativeArray -> {
                    val items = (result as NativeArray).toList().joinToString(", ")
                    "Result: [$items]"
                }
                else -> "Result: $result"
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            "JavaScript Error: $errorMsg"
        } finally {
            Context.exit()
        }
    }

    private fun toJsonString(obj: NativeObject): String {
        return try {
            com.google.gson.Gson().toJson(toMap(obj))
        } catch (e: Exception) {
            obj.toString()
        }
    }

    private fun toMap(obj: NativeObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val ids = obj.getIds()
        for (id in ids) {
            val key = id.toString()
            val value = obj.get(key, obj)
            map[key] = extractValue(value)
        }
        return map
    }

    private fun extractValue(value: Any?): Any? {
        return when (value) {
            is NativeObject -> toMap(value)
            is NativeArray -> value.toList().map { extractValue(it) }
            is Boolean, is Number, is String -> value
            is Undefined -> null
            else -> value?.toString()
        }
    }

   
}
