package com.aiassistant.domain.tool

import com.aiassistant.domain.service.ToolManager
import javax.inject.Inject

class CalculatorTool @Inject constructor() {
    private var pos = 0
    private var tokens = emptyList<String>()

    init {
        register()
    }

    fun register() {
        ToolManager.registerTool(
            ToolManager.ToolDefinition(
                name = "calculator",
                description = "Evaluate a mathematical expression. Input should be a valid math expression with numbers and operators (+, -, *, /, ^, %). Supports parentheses for grouping.",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "expression" to mapOf(
                            "type" to "string",
                            "description" to "The mathematical expression to evaluate (e.g., '2 + 3 * 4', '(10.5 - 5) ^ 2')"
                        )
                    ),
                    "required" to listOf("expression")
                ),
                executor = { arguments ->
                    runCatching {
                        val args = com.google.gson.Gson().fromJson(arguments, Map::class.java)
                        val expression = args["expression"] as? String ?: ""
                        performCalculation(expression)
                    }
                }
            )
        )
    }

    fun performCalculation(expression: String): String {
        return try {
            val sanitized = expression.trim()
                .replace("×", "*")
                .replace("÷", "/")
                .replace("π", "3.141592653589793")
                .replace("e", "2.718281828459045")

            tokens = tokenize(sanitized)
            pos = 0

            val result = parseExpression()

            if (result.isNaN()) {
                "Error: Division by zero or invalid result"
            } else if (result.isInfinite()) {
                "Error: Result is infinite (likely division by zero)"
            } else {
                val formatted = if (result == result.toLong().toDouble() && !sanitized.contains('.')) {
                    result.toLong().toString()
                } else {
                    String.format("%.10f", result).replace(Regex("0+$"), "").replace(Regex("\\.$"), "")
                }
                "Result: $formatted"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun parseExpression(): Double {
        var result = parseTerm()
        while (pos < tokens.size && (tokens[pos] == "+" || tokens[pos] == "-")) {
            val op = tokens[pos++]
            val right = parseTerm()
            result = if (op == "+") result + right else result - right
        }
        return result
    }

    private fun parseTerm(): Double {
        var result = parsePower()
        while (pos < tokens.size && (tokens[pos] == "*" || tokens[pos] == "/" || tokens[pos] == "%")) {
            val op = tokens[pos++]
            val right = parsePower()
            result = when (op) {
                "*" -> result * right
                "/" -> {
                    if (right == 0.0) throw ArithmeticException("Division by zero")
                    result / right
                }
                "%" -> {
                    if (right == 0.0) throw ArithmeticException("Division by zero")
                    result % right
                }
                else -> throw IllegalArgumentException("Unexpected operator: $op")
            }
        }
        return result
    }

    private fun parsePower(): Double {
        var result = parseUnary()
        if (pos < tokens.size && tokens[pos] == "^") {
            pos++
            val exponent = parsePower()
            result = Math.pow(result, exponent)
        }
        return result
    }

    private fun parseUnary(): Double {
        if (pos < tokens.size && tokens[pos] == "-") {
            pos++
            return -parseUnary()
        }
        if (pos < tokens.size && tokens[pos] == "+") {
            pos++
            return parseUnary()
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Double {
        if (pos >= tokens.size) {
            throw IllegalArgumentException("Unexpected end of expression")
        }

        val token = tokens[pos]

        if (token == "(") {
            pos++
            val result = parseExpression()
            if (pos >= tokens.size || tokens[pos] != ")") {
                throw IllegalArgumentException("Missing closing parenthesis")
            }
            pos++
            return result
        }

        if (token == ")") {
            throw IllegalArgumentException("Unexpected closing parenthesis")
        }

        pos++
        return try {
            token.toDouble()
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Unexpected token: $token")
        }
    }

    private fun tokenize(expression: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0

        while (i < expression.length) {
            val ch = expression[i]

            if (Character.isWhitespace(ch)) {
                if (sb.isNotEmpty()) {
                    result.add(sb.toString())
                    sb.clear()
                }
                i++
                continue
            }

            if (Character.isDigit(ch) || ch == '.') {
                sb.append(ch)
                i++
                continue
            }

            if (ch == '-' && (result.isEmpty() || result.last() in listOf("(", "+", "-", "*", "/", "%", "^"))) {
                sb.append(ch)
                i++
                continue
            }

            if (sb.isNotEmpty()) {
                result.add(sb.toString())
                sb.clear()
            }

            if (ch in "+-*/%^()") {
                result.add(ch.toString())
                i++
            } else {
                throw IllegalArgumentException("Unexpected character: $ch")
            }
        }

        if (sb.isNotEmpty()) {
            result.add(sb.toString())
        }

        return result
    }

 
}
