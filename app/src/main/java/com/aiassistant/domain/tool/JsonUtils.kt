package com.aiassistant.domain.tool

import android.util.Log

object JsonUtils {
    private const val TAG = "JsonUtils"

    fun parseToJsonMap(jsonString: String): Map<String, Any?> {
        val trimmed = jsonString.trim()
        
        if (!trimmed.startsWith('{')) {
            return emptyMap()
        }
        
        return try {
            parseObject(trimmed)
        } catch (e: Exception) {
            Log.w(TAG, "Parse failed: ${e.message}")
            emptyMap()
        }
    }

    private fun parseObject(input: String): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        val content = extractContent(input, '{', '}') ?: return result
        
        var pos = 0
        while (pos < content.length) {
            // Skip whitespace and commas
            while (pos < content.length && (content[pos].isWhitespace() || content[pos] == ',')) pos++
            if (pos >= content.length) break
            
            // Parse key - could be quoted or unquoted
            val (key, keyEnd) = parseKey(content, pos)
            pos = keyEnd
            
            // Skip whitespace and colon (or equals for non-JSON format)
            while (pos < content.length && content[pos].isWhitespace()) pos++
            if (pos < content.length && (content[pos] == ':' || content[pos] == '=')) pos++
            while (pos < content.length && content[pos].isWhitespace()) pos++
            
            // Parse value
            if (pos < content.length) {
                val (value, nextPos) = parseValue(content, pos)
                result[key] = value
                pos = nextPos
            }
        }
        return result
    }

    private fun parseKey(input: String, startPos: Int): Pair<String, Int> {
        var pos = startPos
        while (pos < input.length && input[pos].isWhitespace()) pos++
        
        return if (pos < input.length && (input[pos] == '"' || input[pos] == '\'')) {
            // Quoted key
            val quote = input[pos]
            val sb = StringBuilder()
            pos++
            while (pos < input.length && input[pos] != quote) {
                if (input[pos] == '\\' && pos + 1 < input.length) {
                    pos++
                    sb.append(input[pos])
                } else {
                    sb.append(input[pos])
                }
                pos++
            }
            Pair(sb.toString(), pos + 1)
        } else {
            // Unquoted key (e.g., {query=value})
            val sb = StringBuilder()
            while (pos < input.length && input[pos] != ':' && input[pos] != '=' && 
                   input[pos] != ',' && input[pos] != '}' && !input[pos].isWhitespace()) {
                sb.append(input[pos])
                pos++
            }
            Pair(sb.toString(), pos)
        }
    }

    private fun parseArray(input: String, startPos: Int): Pair<List<Any?>, Int> {
        val result = mutableListOf<Any?>()
        var pos = startPos
        val content = extractContent(input, '[', ']') ?: return Pair(emptyList(), pos)
        
        while (pos < content.length) {
            while (pos < content.length && (content[pos].isWhitespace() || content[pos] == ',')) pos++
            if (pos >= content.length) break
            
            val (value, nextPos) = parseValue(content, pos)
            result.add(value)
            pos = nextPos
        }
        return Pair(result, pos)
    }

    private fun parseValue(input: String, startPos: Int): Pair<Any?, Int> {
        var pos = startPos
        while (pos < input.length && input[pos].isWhitespace()) pos++
        
        return when {
            pos >= input.length -> Pair(null, pos)
            input[pos] == '"' -> {
                val (str, endPos) = parseString(input, pos)
                Pair(str, endPos + 1)
            }
            input[pos] == '{' -> {
                val obj = parseObject(input.substring(pos))
                val endPos = findClosingBracket(input, pos, '{', '}') + 1
                Pair(obj, endPos)
            }
            input[pos] == '[' -> {
                val (arr, endPos) = parseArray(input, pos)
                Pair(arr, endPos + 1)
            }
            input.startsWith("true", pos) -> Pair(true, pos + 4)
            input.startsWith("false", pos) -> Pair(false, pos + 5)
            input.startsWith("null", pos) -> Pair(null, pos + 4)
            input[pos] == '-' || input[pos].isDigit() -> {
                val numEnd = findNumberEnd(input, pos)
                val numStr = input.substring(pos, numEnd)
                val value = if (numStr.contains('.')) {
                    numStr.toDoubleOrNull()
                } else {
                    numStr.toLongOrNull()
                }
                Pair(value, numEnd)
            }
            else -> {
                // Unquoted value (e.g., query=latest news about Nvidia)
                val sb = StringBuilder()
                while (pos < input.length && input[pos] != ',' && input[pos] != '}') {
                    sb.append(input[pos])
                    pos++
                }
                val value = sb.toString().trim()
                Pair(if (value.isEmpty()) null else value, pos)
            }
        }
    }

    private fun parseString(input: String, startPos: Int): Pair<String, Int> {
        val quote = input[startPos]
        val sb = StringBuilder()
        var pos = startPos + 1
        
        while (pos < input.length) {
            val ch = input[pos]
            if (ch == '\\') {
                pos++
                if (pos < input.length) {
                    val escaped = input[pos]
                    when (escaped) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (pos + 4 < input.length) {
                                val hex = input.substring(pos + 1, pos + 5)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                        }
                        else -> {
                            sb.append('\\')
                            sb.append(escaped)
                        }
                    }
                }
            } else if (ch == quote) {
                break
            } else {
                sb.append(ch)
            }
            pos++
        }
        return Pair(sb.toString(), pos)
    }

    private fun extractContent(input: String, open: Char, close: Char): String? {
        val openIdx = input.indexOf(open)
        val closeIdx = input.lastIndexOf(close)
        if (openIdx < 0 || closeIdx < 0 || closeIdx <= openIdx) return null
        return input.substring(openIdx + 1, closeIdx)
    }

    private fun findClosingBracket(input: String, startPos: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = startPos
        var inString = false
        var stringQuote = ' '
        
        while (i < input.length) {
            val ch = input[i]
            
            if (inString) {
                if (ch == '\\' && i + 1 < input.length) {
                    i++
                } else if (ch == stringQuote) {
                    inString = false
                }
            } else {
                if (ch == '"' || ch == '\'') {
                    inString = true
                    stringQuote = ch
                } else if (ch == open) {
                    depth++
                } else if (ch == close) {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return input.lastIndex
    }

    private fun findNumberEnd(input: String, startPos: Int): Int {
        var pos = startPos
        if (pos < input.length && input[pos] == '-') pos++
        
        while (pos < input.length && input[pos].isDigit()) pos++
        
        if (pos < input.length && input[pos] == '.') {
            pos++
            while (pos < input.length && input[pos].isDigit()) pos++
        }
        
        if (pos < input.length && (input[pos] == 'e' || input[pos] == 'E')) {
            pos++
            if (pos < input.length && (input[pos] == '+' || input[pos] == '-')) pos++
            while (pos < input.length && input[pos].isDigit()) pos++
        }
        
        return pos
    }
}
