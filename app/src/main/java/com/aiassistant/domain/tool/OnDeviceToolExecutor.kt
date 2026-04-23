package com.aiassistant.domain.tool

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.util.DisplayMetrics
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.tools.shell.Global

private const val EXA_API_URL = "https://api.exa.ai/search"

class CalculatorToolImpl : OpenApiTool {

    private var pos = 0
    private var tokens = emptyList<String>()

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "calculator",
          "description": "Evaluate a mathematical expression. Input should be a valid math expression with numbers and operators (+, -, *, /, ^, %). Supports parentheses for grouping.",
          "parameters": {
            "type": "object",
            "properties": {
              "expression": {
                "type": "string",
                "description": "The mathematical expression to evaluate (e.g., '2 + 3 * 4', '(10.5 - 5) ^ 2')"
              }
            },
            "required": ["expression"]
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        return try {
            val params = JsonUtils.parseToJsonMap(paramsJsonString)
            val expression = params["expression"] as? String ?: ""
            val result = performCalculation(expression)
            com.google.gson.Gson().toJson(mapOf("result" to result))
        } catch (e: Exception) {
            com.google.gson.Gson().toJson(mapOf("result" to "Error: ${e.message}"))
        }
    }

    private fun performCalculation(expression: String): String {
        return try {
            val sanitized = expression.trim()
                .replace("\u00d7", "*")
                .replace("\u00f7", "/")
                .replace("\u03c0", "3.141592653589793")
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

class WebSearchToolImpl(
    private val context: Context
) : OpenApiTool {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "web_search",
          "description": "Search the web for information using Exa AI semantic search. Takes a query string and returns relevant results with titles, URLs, and content snippets.",
          "parameters": {
            "type": "object",
            "properties": {
              "query": {
                "type": "string",
                "description": "The search query"
              }
            },
            "required": ["query"]
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        return try {
            val params = JsonUtils.parseToJsonMap(paramsJsonString)
            val query = params["query"] as? String ?: ""
            val result = performSearch(query)
            com.google.gson.Gson().toJson(mapOf("result" to result))
        } catch (e: Exception) {
            com.google.gson.Gson().toJson(mapOf("result" to "Error: ${e.message}"))
        }
    }

    private fun getExaApiKey(): String? {
        return try {
            val dataStore = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            dataStore.getString("exa_api_key", null)
        } catch (e: Exception) {
            null
        }
    }

    private fun performSearch(query: String): String {
        return try {
            if (query.isBlank()) {
                return gson.toJson(mapOf("result" to "Error: No search query provided"))
            }
            val apiKey = getExaApiKey()
            if (apiKey.isNullOrEmpty()) {
                return gson.toJson(mapOf("result" to "Error: Exa API key not configured. Please add your Exa API key in Settings."))
            }

            val requestBody = gson.toJson(mapOf(
                "query" to query,
                "numResults" to 5,
                "startCitedByCount" to null,
                "type" to "auto"
            ))

            val request = Request.Builder()
                .url(EXA_API_URL)
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("Accept", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()

            val responseBody = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                return gson.toJson(mapOf("result" to "Error: Exa API returned ${response.code}: $responseBody"))
            }

            val json = com.google.gson.JsonParser.parseString(responseBody)
            val resultsArray = json.asJsonObject?.get("results")?.asJsonArray 
                ?: json.asJsonObject?.get("highlights")?.asJsonObject?.get("text")?.asJsonArray
                ?: com.google.gson.JsonArray()

            if (resultsArray.isEmpty()) {
                return gson.toJson(mapOf("result" to "No search results found for: $query"))
            }

            data class SearchResult(val title: String, val url: String, val snippet: String)
            val results = mutableListOf<SearchResult>()
            for (i in 0 until resultsArray.size()) {
                val result = resultsArray[i].asJsonObject
                val title = result.get("title")?.asString ?: ""
                val url = result.get("url")?.asString ?: ""
                val highlights = result.get("highlights")?.asJsonArray
                val snippet = if (highlights != null && highlights.size() > 0) {
                    highlights[0].asString
                } else {
                    result.get("text")?.asString?.take(300) ?: ""
                }
                if (title.isNotEmpty() || url.isNotEmpty()) {
                    results.add(SearchResult(title, url, snippet))
                }
            }

            if (results.isEmpty()) {
                return gson.toJson(mapOf("result" to "No search results found for: $query"))
            } else {
                val output = buildString {
                    append("Search results for: $query\n\n")
                    results.take(5).forEachIndexed { index, result ->
                        append("${index + 1}. ${result.title}\n")
                        append("   URL: ${result.url}\n")
                        if (!result.snippet.isNullOrEmpty()) {
                            append("   ${result.snippet}\n")
                        }
                        append("\n")
                    }
                }
                gson.toJson(mapOf("result" to output))
            }
        } catch (e: Exception) {
            gson.toJson(mapOf("result" to "Error performing search: ${e.message}"))
        }
    }
}

class WeatherToolImpl : OpenApiTool {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "weather",
          "description": "Get current weather information for a city. Returns temperature, humidity, wind speed, weather condition, and forecast.",
          "parameters": {
            "type": "object",
            "properties": {
              "city": {
                "type": "string",
                "description": "The name of the city (e.g., 'London', 'Tokyo', 'New York')"
              },
              "units": {
                "type": "string",
                "description": "Temperature unit: 'celsius' (default) or 'fahrenheit'",
                "enum": ["celsius", "fahrenheit"]
              }
            },
            "required": ["city"]
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        return try {
            val params = JsonUtils.parseToJsonMap(paramsJsonString)
            val city = params["city"] as? String ?: ""
            val units = params["units"] as? String ?: "celsius"
            val result = fetchWeather(city, units)
            com.google.gson.Gson().toJson(mapOf("result" to result))
        } catch (e: Exception) {
            com.google.gson.Gson().toJson(mapOf("result" to "Error: ${e.message}"))
        }
    }

    private fun fetchWeather(city: String, units: String): String {
        return try {
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${java.net.URLEncoder.encode(city, "UTF-8")}&count=1"
            val geoRequest = Request.Builder().url(geoUrl).build()
            val geoResponse = httpClient.newCall(geoRequest).execute()
            val geoBody = geoResponse.body?.string() ?: "{}"
            val geoJson = com.google.gson.JsonParser.parseString(geoBody)

            val results = geoJson.asJsonObject?.get("results")?.asJsonArray
            if (results == null || results.isEmpty()) {
                return "Error: City '$city' not found"
            }

            val firstResult = results[0].asJsonObject
            val lat = firstResult.get("latitude")?.asDouble
            val lon = firstResult.get("longitude")?.asDouble

            if (lat == null || lon == null) {
                return "Error: Could not get coordinates for '$city'"
            }

            val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m,pressure_msl&daily=temperature_2m_max,temperature_2m_min&temperature_unit=$units&timezone=auto"
            val weatherRequest = Request.Builder().url(weatherUrl).build()
            val weatherResponse = httpClient.newCall(weatherRequest).execute()
            val weatherBody = weatherResponse.body?.string() ?: "{}"
            val weatherJson = com.google.gson.JsonParser.parseString(weatherBody)

            val current = weatherJson.asJsonObject?.get("current")?.asJsonObject
            val daily = weatherJson.asJsonObject?.get("daily")?.asJsonObject

            val temp = current?.get("temperature_2m")?.asDouble ?: 0.0
            val feelsLike = current?.get("apparent_temperature")?.asDouble ?: 0.0
            val humidity = current?.get("relative_humidity_2m")?.asInt ?: 0
            val windSpeed = current?.get("wind_speed_10m")?.asDouble ?: 0.0
            val windDir = current?.get("wind_direction_10m")?.asInt ?: 0
            val pressure = current?.get("pressure_msl")?.asDouble ?: 0.0
            val weatherCode = current?.get("weather_code")?.asInt ?: 0
            val condition = getWeatherCondition(weatherCode)

            val tempMax = daily?.get("temperature_2m_max")?.asJsonArray?.get(0)?.asDouble ?: 0.0
            val tempMin = daily?.get("temperature_2m_min")?.asJsonArray?.get(0)?.asDouble ?: 0.0

            val windDirection = getWindDirection(windDir)

            buildString {
                append("Weather in $city:\n")
                append("Condition: $condition\n")
                append("Temperature: ${String.format("%.1f", temp)}\u00b0$units (feels like ${String.format("%.1f", feelsLike)}\u00b0$units)\n")
                append("High: ${String.format("%.1f", tempMax)}\u00b0 / Low: ${String.format("%.1f", tempMin)}\u00b0\n")
                append("Humidity: $humidity%\n")
                append("Wind: ${String.format("%.1f", windSpeed)} km/h $windDirection\n")
                append("Pressure: ${String.format("%.1f", pressure)} hPa\n")
            }
        } catch (e: Exception) {
            "Error fetching weather: ${e.message}"
        }
    }

    private fun getWeatherCondition(code: Int): String {
        return when (code) {
            0 -> "Clear sky"
            1 -> "Mainly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63, 65 -> "Rain"
            66, 67 -> "Freezing rain"
            71, 73, 75 -> "Snow"
            77 -> "Snow grains"
            80, 81, 82 -> "Heavy rain"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Thunderstorm with hail"
            else -> "Unknown ($code)"
        }
    }

    private fun getWindDirection(degrees: Int): String {
        return when (degrees) {
            in 0..22 -> "N"
            in 23..67 -> "NE"
            in 68..112 -> "E"
            in 113..157 -> "SE"
            in 158..202 -> "S"
            in 203..247 -> "SW"
            in 248..292 -> "W"
            in 293..337 -> "NW"
            else -> "N"
        }
    }
}

class WebPageFetcherToolImpl : OpenApiTool {

    private val gson = Gson()

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "web_fetch",
          "description": "Fetch and extract the main content from a web page. Returns the page title, text content, and any links found on the page.",
          "parameters": {
            "type": "object",
            "properties": {
              "url": {
                "type": "string",
                "description": "The full URL of the web page to fetch (must include http:// or https://)"
              },
              "max_length": {
                "type": "integer",
                "description": "Maximum number of characters to return (default: 2000)"
              }
            },
            "required": ["url"]
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        return try {
            val params = JsonUtils.parseToJsonMap(paramsJsonString)
            val url = params["url"] as? String ?: ""
            val maxLength = (params["max_length"] as? Number)?.toInt() ?: 2000
            val result = fetchPage(url, maxLength)
            com.google.gson.Gson().toJson(mapOf("result" to result))
        } catch (e: Exception) {
            com.google.gson.Gson().toJson(mapOf("result" to "Error: ${e.message}"))
        }
    }

    private fun fetchPage(url: String, maxLength: Int): String {
        return try {
            val normalizedUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
                url
            } else {
                "https://$url"
            }

            val document = Jsoup.connect(normalizedUrl)
                .timeout(15000)
                .userAgent("Mozilla/5.0 (Android; Mobile; rv:68.0) Gecko/68.0 Firefox/68.0")
                .followRedirects(true)
                .get()

            val title = document.title()
            val text = document.body()?.text()?.trim() ?: ""

            val links = document.select("a[href]").mapNotNull { link ->
                val href = link.attr("abs:href")
                val linkText = link.text().trim()
                if (href.isNotEmpty() && linkText.isNotEmpty()) {
                    "  - $linkText: $href"
                } else null
            }.take(10)

            val truncatedText = if (text.length > maxLength) {
                text.substring(0, maxLength) + "..."
            } else {
                text
            }

            buildString {
                append("Page: $title\n")
                append("URL: $normalizedUrl\n\n")
                append("Content:\n$truncatedText\n\n")
                if (links.isNotEmpty()) {
                    append("Links found:\n${links.joinToString("\n")}\n")
                }
            }
        } catch (e: Exception) {
            "Error fetching page: ${e.message}"
        }
    }
}

class CodeInterpreterToolImpl : OpenApiTool {

    private val gson = Gson()

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "code_interpreter",
          "description": "Execute a JavaScript code snippet and return the result. Use this for calculations, data processing, or running small programs. The code runs in a sandboxed environment.",
          "parameters": {
            "type": "object",
            "properties": {
              "code": {
                "type": "string",
                "description": "The JavaScript code to execute"
              },
              "language": {
                "type": "string",
                "description": "The programming language (default: 'javascript')",
                "enum": ["javascript"]
              }
            },
            "required": ["code"]
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        return try {
            val params = JsonUtils.parseToJsonMap(paramsJsonString)
            val code = params["code"] as? String ?: ""
            val language = params["language"] as? String ?: "javascript"
            val result = executeCode(code, language)
            com.google.gson.Gson().toJson(mapOf("result" to result))
        } catch (e: Exception) {
            com.google.gson.Gson().toJson(mapOf("result" to "Error: ${e.message}"))
        }
    }

    private fun executeCode(code: String, language: String): String {
        return try {
            when (language.lowercase()) {
                "javascript" -> executeJavaScript(code)
                else -> "Error: Unsupported language '$language'. Only 'javascript' is supported."
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun executeJavaScript(code: String): String {
        val cx = RhinoContext.enter()
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
            "JavaScript Error: ${e.message}"
        } finally {
            RhinoContext.exit()
        }
    }

    private fun toJsonString(obj: NativeObject): String {
        return try {
            Gson().toJson(toMap(obj))
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

class DeviceInfoToolImpl(
    private val context: Context
) : OpenApiTool {

    private val gson = Gson()

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "device_info",
          "description": "Get detailed information about the current Android device including model, OS version, battery status, storage, display, and memory.",
          "parameters": {
            "type": "object",
            "properties": {
              "category": {
                "type": "string",
                "description": "Information category: 'all' (default), 'battery', 'storage', 'display', 'system', 'memory'",
                "enum": ["all", "battery", "storage", "display", "system", "memory"]
              }
            },
            "required": []
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        return try {
            val params = JsonUtils.parseToJsonMap(paramsJsonString)
            val category = params["category"] as? String ?: "all"
            val result = getDeviceInfo(category)
            com.google.gson.Gson().toJson(mapOf("result" to result))
        } catch (e: Exception) {
            com.google.gson.Gson().toJson(mapOf("result" to "Error: ${e.message}"))
        }
    }

    private fun getDeviceInfo(category: String): String {
        return try {
            buildString {
                when (category.lowercase()) {
                    "battery" -> append(getBatteryInfo())
                    "storage" -> append(getStorageInfo())
                    "display" -> append(getDisplayInfo())
                    "system" -> append(getSystemInfo())
                    "memory" -> append(getMemoryInfo())
                    else -> {
                        append(getSystemInfo())
                        append("\n\n")
                        append(getBatteryInfo())
                        append("\n\n")
                        append(getStorageInfo())
                        append("\n\n")
                        append(getDisplayInfo())
                        append("\n\n")
                        append(getMemoryInfo())
                    }
                }
            }
        } catch (e: Exception) {
            "Error getting device info: ${e.message}"
        }
    }

    private fun getSystemInfo(): String {
        return buildString {
            append("=== System Information ===\n")
            append("Device: ${Build.DEVICE}\n")
            append("Model: ${Build.MODEL}\n")
            append("Brand: ${Build.BRAND}\n")
            append("Manufacturer: ${Build.MANUFACTURER}\n")
            append("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("Security Patch: ${Build.VERSION.SECURITY_PATCH}\n")
            append("Hardware: ${Build.HARDWARE}\n")
            append("Board: ${Build.BOARD}\n")
            append("CPU ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
            append("Product: ${Build.PRODUCT}\n")
            append("Fingerprint: ${Build.FINGERPRINT}\n")
        }
    }

    private fun getBatteryInfo(): String {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent = context.registerReceiver(null, intentFilter)

        var level = -1
        var scale = -1
        var statusInt = -1
        var healthInt = -1
        var voltage = -1
        var temperature = -1

        if (batteryIntent != null) {
            level = batteryIntent.getIntExtra("android.intent.extra.LEVEL", -1)
            scale = batteryIntent.getIntExtra("android.intent.extra.SCALE", -1)
            statusInt = batteryIntent.getIntExtra("android.intent.extra.STATUS", -1)
            healthInt = batteryIntent.getIntExtra("android.intent.extra.HEALTH", -1)
            voltage = batteryIntent.getIntExtra("android.intent.extra.VOLTAGE", -1)
            temperature = batteryIntent.getIntExtra("android.intent.extra.TEMPERATURE", -1)
        }

        if (level < 0 || statusInt < 0) {
            try {
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                if (level < 0) level = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (statusInt < 0) statusInt = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_STATUS)
                if (voltage < 0) voltage = batteryManager.getIntProperty(11)
                if (temperature < 0) temperature = batteryManager.getIntProperty(10)
            } catch (e: SecurityException) {
                // BATTERY_STATS permission not granted
            } catch (e: Exception) {
                // BatteryManager API not available
            }
        }

        val percentage = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        val statusString = when (statusInt) {
            android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            android.os.BatteryManager.BATTERY_STATUS_FULL -> "Full"
            android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            android.os.BatteryManager.BATTERY_STATUS_UNKNOWN -> "Unknown"
            else -> "Unknown"
        }

        val healthString = when (healthInt) {
            android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            android.os.BatteryManager.BATTERY_HEALTH_UNKNOWN -> "Unknown"
            android.os.BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }

        val chargeType = if (batteryIntent != null) {
            when (batteryIntent.getIntExtra("android.intent.extra.PLUGGED", -1)) {
                1 -> "USB"
                2 -> "AC"
                4 -> "Wireless"
                64 -> "Dock"
                else -> "Not plugged"
            }
        } else "Not plugged"

        val batteryTech = if (batteryIntent != null) {
            batteryIntent.getStringExtra("android.intent.extra.TECHNOLOGY") ?: "Unknown"
        } else "Unknown"

        val batteryPresent = if (batteryIntent != null) {
            batteryIntent.getBooleanExtra("android.intent.extra.PRESENT", false)
        } else false

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val isPowerSave = powerManager.isPowerSaveMode

        return buildString {
            append("=== Battery Information ===\n")
            append("Level: ${if (percentage >= 0) "$percentage%" else "Unknown"}\n")
            append("Status: $statusString\n")
            append("Health: $healthString\n")
            append("Voltage: ${if (voltage >= 0) "$voltage mV" else "Unknown"}\n")
            append("Temperature: ${if (temperature >= 0) "${temperature / 10.0}\u00b0C" else "Unknown"}\n")
            append("Charging: $chargeType\n")
            append("Technology: $batteryTech\n")
            append("Present: $batteryPresent\n")
            append("Power Save Mode: $isPowerSave\n")
        }
    }

    private fun getStorageInfo(): String {
        return try {
            val dataDir = context.filesDir
            val totalSpace = dataDir.totalSpace
            val usableSpace = dataDir.usableSpace
            val usedSpace = totalSpace - usableSpace

            fun formatSize(bytes: Long): String {
                return when {
                    bytes < 1024 -> "$bytes B"
                    bytes < 1024 * 1024 -> String.format("%.2f KB", bytes.toDouble() / 1024)
                    bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes.toDouble() / (1024 * 1024))
                    else -> String.format("%.2f GB", bytes.toDouble() / (1024 * 1024 * 1024))
                }
            }

            val externalStorage = Environment.getExternalStorageState()
            val externalDir = Environment.getExternalStorageDirectory()

            buildString {
                append("=== Storage Information ===\n")
                append("Internal Storage:\n")
                append("  Total: ${formatSize(totalSpace)}\n")
                append("  Used: ${formatSize(usedSpace)}\n")
                append("  Available: ${formatSize(usableSpace)}\n")
                append("  Usage: ${String.format("%.1f", (usedSpace.toDouble() / totalSpace * 100))}%\n")

                if (externalStorage == Environment.MEDIA_MOUNTED || externalStorage == Environment.MEDIA_MOUNTED_READ_ONLY) {
                    if (externalDir != null) {
                        val extTotal = externalDir.totalSpace
                        val extUsable = externalDir.usableSpace
                        append("External Storage:\n")
                        append("  Total: ${formatSize(extTotal)}\n")
                        append("  Available: ${formatSize(extUsable)}\n")
                    }
                } else {
                    append("External Storage: Not available\n")
                }

                append("App Data Directory: ${context.filesDir.path}\n")
                append("App Cache Directory: ${context.cacheDir.path}\n")
                append("App External Files: ${context.getExternalFilesDir(null)?.path}\n")
            }
        } catch (e: Exception) {
            "Error getting storage info: ${e.message}"
        }
    }

    private fun getDisplayInfo(): String {
        val display = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val metrics = DisplayMetrics()
        display.defaultDisplay.getMetrics(metrics)

        val densityDpi = metrics.densityDpi
        val density = metrics.density
        val widthPx = metrics.widthPixels
        val heightPx = metrics.heightPixels

        fun pxToDp(px: Float): Float = px / density

        return buildString {
            append("=== Display Information ===\n")
            append("Resolution: ${widthPx} x ${heightPx} px\n")
            append("Density: $densityDpi dpi (${String.format("%.2f", density)}x)\n")
            append("Screen Width: ${String.format("%.1f", pxToDp(widthPx.toFloat()))} dp\n")
            append("Screen Height: ${String.format("%.1f", pxToDp(heightPx.toFloat()))} dp\n")

            val diagonalPx = Math.sqrt(
                (widthPx.toDouble() * widthPx + heightPx.toDouble() * heightPx)
            )
            val diagonalInches = diagonalPx / densityDpi
            append("Diagonal: ${String.format("%.2f", diagonalInches)} inches\n")
        }
    }

    private fun getMemoryInfo(): String {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            val runtime = Runtime.getRuntime()
            val totalMemory = runtime.totalMemory()
            val freeMemory = runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val usedMemory = totalMemory - freeMemory

            fun formatBytes(bytes: Long): String {
                return when {
                    bytes < 1024 -> "$bytes B"
                    bytes < 1024 * 1024 -> String.format("%.2f MB", bytes.toDouble() / (1024 * 1024))
                    else -> String.format("%.2f GB", bytes.toDouble() / (1024 * 1024 * 1024))
                }
            }

            buildString {
                append("=== Memory Information ===\n")
                append("Device Memory:\n")
                append("  Total: ${formatBytes(memoryInfo.totalMem)}\n")
                append("  Available: ${formatBytes(memoryInfo.availMem)}\n")
                append("  Threshold: ${formatBytes(memoryInfo.threshold)}\n")
                append("  Low Memory: ${if (memoryInfo.lowMemory) "Yes" else "No"}\n")
                append("\n")
                append("App JVM Memory:\n")
                append("  Used: ${formatBytes(usedMemory)}\n")
                append("  Total: ${formatBytes(totalMemory)}\n")
                append("  Max: ${formatBytes(maxMemory)}\n")
            }
        } catch (e: Exception) {
            "Error getting memory info: ${e.message}"
        }
    }
}

class TermuxShellToolImpl(
    private val context: Context
) : OpenApiTool {

   private class PendingResult(
        val latch: CountDownLatch,
        val stdout: StringBuilder,
        val stderr: StringBuilder,
        var exitCode: Int = -1,
        var err: Int = 0,
        var errmsg: String? = null
    )
    private val pendingResults = ConcurrentHashMap<Int, PendingResult>()
    private val completedResults = ConcurrentHashMap<Int, String>()
    private val executionId = AtomicInteger(0)
    private val resultReceiver = TermuxResultReceiver()

    init {
        val filter = IntentFilter(RESULT_ACTION)
        ContextCompat.registerReceiver(
            context,
            resultReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    inner class TermuxResultReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val execId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
            if (execId == -1) return

            val entry = pendingResults[execId] ?: return

            val resultBundle = intent.getBundleExtra(EXTRA_PLUGIN_RESULT_BUNDLE)
                ?: return

            val stdout = resultBundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT, "")
            val stderr = resultBundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_STDERR, "")
            val exitCode = resultBundle.getInt(EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE, -1)
            val err = resultBundle.getInt(EXTRA_PLUGIN_RESULT_BUNDLE_ERR, 0)
            val errmsg = resultBundle.getString(EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG, "")

            android.util.Log.d("TermuxShellTool", "Bundle: stdout=${stdout.take(80)} stderr=${stderr.take(80)} exitCode=$exitCode err=$err errmsg=$errmsg")

            if (!stdout.isNullOrBlank()) entry.stdout.append(stdout)
            if (!stderr.isNullOrBlank()) entry.stderr.append(stderr)
            if (exitCode >= 0) entry.exitCode = exitCode
            if (err != 0) entry.err = err
            if (!errmsg.isNullOrBlank()) entry.errmsg = errmsg

            if (exitCode >= 0) {
                val output = buildString {
                    val hasRealError = entry.err != 0 && (!entry.errmsg.isNullOrBlank() || entry.exitCode != 0)
                    if (hasRealError) {
                        append("Error: Termux execution failed (err=${entry.err})")
                        if (!entry.errmsg.isNullOrBlank()) append(": ${entry.errmsg}")
                        if (entry.stderr.isNotEmpty()) append("\nSTDERR: ${entry.stderr}")
                        if (entry.stdout.isNotEmpty()) append("\nSTDOUT: ${entry.stdout}")
                        append("\n\nTroubleshooting:\n")
                        append("1. Ensure 'allow-external-apps = true' in ~/.termux/termux.properties\n")
                        append("2. Grant RUN_COMMAND permission: Settings > Apps > Synapse > Additional permissions\n")
                        append("3. Restart Termux after changes")
                    } else {
                        if (entry.stdout.isNotEmpty()) append(entry.stdout)
                        if (entry.stderr.isNotEmpty()) {
                            if (isNotEmpty()) append("\n")
                            append("STDERR:\n${entry.stderr}")
                        }
                        append("\n\nExit code: ${entry.exitCode}")
                    }
                }
                completedResults[execId] = output
                pendingResults.remove(execId)
                android.util.Log.d("TermuxShellTool", "Final result (execId=$execId, length=${output.length})")
                entry.latch.countDown()
            }
        }
    }

    companion object {
        private const val EXTRA_EXECUTION_ID = "com.aiassistant.termux.execution_id"

        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_SERVICE_NAME = "com.termux.app.RunCommandService"
        private const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

        private const val TERMUX_HOME_DIR = "/data/data/com.termux/files/home"
        private const val TERMUX_PREFIX_DIR = "/data/data/com.termux/files/usr"
        private const val TERMUX_BIN_DIR = "/data/data/com.termux/files/usr/bin"

        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        private const val EXTRA_PLUGIN_RESULT_BUNDLE = "result"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT = "stdout"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_STDERR = "stderr"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE = "exitCode"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_ERR = "err"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG = "errmsg"

        private const val RESULT_ACTION = "com.aiassistant.TERMUX_RESULT"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MAX_TIMEOUT_MS = 120_000L
    }

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "termux_shell",
          "description": "Execute commands in a full Linux shell (Termux). This gives you access to a complete Linux environment on the device. Use this for: network diagnostics (ping, curl, wget, nslookup, dig, traceroute, netstat, ss), file operations (ls, cat, grep, find, cp, mv, rm, mkdir, tar, zip, unzip, diff, wc, head, tail), system info (uname, df, free, top, ps, whoami, id, hostname, uptime), text processing (sed, awk, sort, uniq, tr, cut, xargs), package management (pkg, apt), Python/Node scripts, and any other Linux command-line task. This is a powerful tool for diagnosing issues, fetching data, processing files, and running scripts. Commands run synchronously with a timeout (default 30s, max 120s). Avoid long-running or interactive commands that would block indefinitely.",
          "parameters": {
            "type": "object",
            "properties": {
              "command": {
                "type": "string",
                "description": "The interpreter to use. Default: 'bash'. Use 'python3', 'node' etc. for specific interpreters."
              },
              "shell_command": {
                "type": "string",
                "description": "REQUIRED: The shell command to run. Passed to 'bash -c'. Examples: 'ping -c 4 8.8.8.8', 'curl -s https://api.example.com', 'ls -la /sdcard', 'grep -r \"error\" *.log', 'python3 -c \"import json; print(json.dumps({\\\"test\\\": 1}))\"'."
              },
              "script": {
                "type": "string",
                "description": "Alternative to shell_command: multi-line script content passed via stdin. Use for complex Python/Node scripts."
              },
              "workdir": {
                "type": "string",
                "description": "Working directory. Defaults to ~. Use ~/path or /absolute/path"
              },
              "timeout": {
                "type": "integer",
                "description": "Timeout in seconds. Default: 30, max: 120"
              }
            },
            "required": ["shell_command"]
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        return try {
            val params = JsonUtils.parseToJsonMap(paramsJsonString)
            val command = params["command"] as? String ?: "bash"
            val shellCommand = params["shell_command"] as? String
            val script = params["script"] as? String
            val workdir = params["workdir"] as? String
            val timeoutSeconds = (params["timeout"] as? Number)?.toInt() ?: 30

            if (!shellCommand.isNullOrBlank()) {
                val knownShells = setOf("bash", "sh", "zsh", "dash")
                val shell = knownShells.find { command.lowercase() == it } ?: "bash"
                android.util.Log.d("TermuxShellTool", "Executing: cmd=$shell args=-c $shellCommand workdir=$workdir timeout=${timeoutSeconds}s")
                return executeCommand(shell, "-c $shellCommand", null, workdir, timeoutSeconds)
            }

            if (!script.isNullOrBlank()) {
                android.util.Log.d("TermuxShellTool", "Executing: cmd=$command script=${script.length} chars workdir=$workdir timeout=${timeoutSeconds}s")
                return executeCommand(command, "", script, workdir, timeoutSeconds)
            }

            com.google.gson.Gson().toJson(mapOf("result" to "Error: Missing 'shell_command' or 'script' parameter. Provide a command to run."))
        } catch (e: Exception) {
            com.google.gson.Gson().toJson(mapOf("result" to "Error: ${e.message}"))
        }
    }

    private fun executeCommand(
        command: String,
        argumentsStr: String,
        script: String?,
        workdir: String?,
        timeoutSeconds: Int
    ): String {
        if (!isTermuxInstalled()) {
            return "Error: Termux is not installed. Install Termux from F-Droid or GitHub, then grant the RUN_COMMAND permission to this app."
        }

        if (!hasRunCommandPermission()) {
            return "Error: RUN_COMMAND permission not granted. Go to Android Settings > Apps > Synapse > Additional permissions and enable 'Run commands in Termux environment'."
        }

        val timeout = (timeoutSeconds.coerceIn(1, 120) * 1000L).coerceAtMost(MAX_TIMEOUT_MS)

            val cmdPath = resolveCommandPath(command)
        val cmdArgs: Array<String> = if (argumentsStr.isNotBlank()) {
            val trimmed = argumentsStr.trim()
            if (trimmed.startsWith("-c ")) {
                arrayOf("-c", trimmed.substring(3))
            } else {
                arrayOf(trimmed)
            }
        } else {
            emptyArray()
        }

        android.util.Log.d("TermuxShellTool", "cmdPath=$cmdPath cmdArgs=${cmdArgs.contentToString()}")

        val id = executionId.incrementAndGet()
        val latch = CountDownLatch(1)
        pendingResults[id] = PendingResult(latch, StringBuilder(), StringBuilder())

        val intent = buildIntent(cmdPath, cmdArgs, script, workdir, id)
        android.util.Log.d("TermuxShellTool", "Starting Termux service (id=$id)")

        try {
            context.startService(intent)
            android.util.Log.d("TermuxShellTool", "Service started, awaiting result (timeout=${timeout}ms)")
        } catch (e: Exception) {
            pendingResults.remove(id)
            android.util.Log.e("TermuxShellTool", "Failed to start service: ${e.message}")
            return "Error: Failed to start Termux service: ${e.message}"
        }

        val completed = latch.await(timeout, TimeUnit.MILLISECONDS)
        if (!completed) {
            pendingResults.remove(id)
            android.util.Log.e("TermuxShellTool", "Timeout after ${timeoutSeconds}s (id=$id)")
            return "Error: Command timed out after ${timeoutSeconds}s"
        }

        android.util.Log.d("TermuxShellTool", "Result received (id=$id)")
        return completedResults.remove(id) ?: "Error: No result for execution $id"
    }

    private fun buildIntent(
        commandPath: String,
        arguments: Array<String>,
        stdin: String?,
        workdir: String?,
        id: Int
    ): Intent {
        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, TERMUX_SERVICE_NAME)
            action = ACTION_RUN_COMMAND
            putExtra(EXTRA_COMMAND_PATH, commandPath)
            putExtra(EXTRA_ARGUMENTS, arguments)
            putExtra(EXTRA_BACKGROUND, true)
        }

        if (!stdin.isNullOrBlank()) {
            intent.putExtra(EXTRA_STDIN, stdin)
        }
        if (!workdir.isNullOrBlank()) {
            intent.putExtra(EXTRA_WORKDIR, workdir)
        }

        val resultIntent = Intent(RESULT_ACTION).apply {
            putExtra(EXTRA_EXECUTION_ID, id)
            setPackage(context.packageName)
        }

        val flags = android.app.PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) android.app.PendingIntent.FLAG_MUTABLE else 0)

        val pendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            id,
            resultIntent,
            flags
        )
        intent.putExtra(EXTRA_PENDING_INTENT, pendingIntent)

        return intent
    }

    private fun parseQuotedArguments(str: String): List<String> {
        val args = mutableListOf<String>()
        var i = 0
        while (i < str.length) {
            if (str[i] == ' ' || str[i] == '\t') {
                i++
                continue
            }
            if (str[i] == '"') {
                var arg = ""
                i++
                while (i < str.length && str[i] != '"') {
                    if (str[i] == '\\' && i + 1 < str.length) {
                        arg += str[++i]
                    } else {
                        arg += str[i]
                    }
                    i++
                }
                i++ // skip closing quote
                args.add(arg)
            } else if (str[i] == '\'') {
                var arg = ""
                i++
                while (i < str.length && str[i] != '\'') {
                    arg += str[i]
                    i++
                }
                i++ // skip closing quote
                args.add(arg)
            } else {
                var arg = ""
                while (i < str.length && str[i] != ' ' && str[i] != '\t') {
                    if (str[i] == '\\' && i + 1 < str.length && (str[i + 1] == '"' || str[i + 1] == '\\' || str[i + 1] == ' ')) {
                        arg += str[++i]
                    } else {
                        arg += str[i]
                    }
                    i++
                }
                args.add(arg)
            }
        }
        return args
    }

    private fun resolveCommandPath(command: String): String {
        return when {
            command.startsWith("/") -> command
            command.startsWith("~") -> command.replaceFirst("~", TERMUX_HOME_DIR)
            command.startsWith("\$PREFIX") -> command.replaceFirst("\$PREFIX", TERMUX_PREFIX_DIR)
            else -> "$TERMUX_BIN_DIR/$command"
        }
    }

    private fun isTermuxInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun hasRunCommandPermission(): Boolean {
        return context.checkPermission(
            PERMISSION_RUN_COMMAND,
            android.os.Process.myPid(),
            android.os.Process.myUid()
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

class OnDeviceToolExecutor(
    private val context: Context
) {
    private val calculatorTool = CalculatorToolImpl()
    private val webSearchTool = WebSearchToolImpl(context)
    private val weatherTool = WeatherToolImpl()
    private val webPageFetcherTool = WebPageFetcherToolImpl()
    private val codeInterpreterTool = CodeInterpreterToolImpl()
    private val deviceInfoTool = DeviceInfoToolImpl(context)
    private val termuxShellTool = TermuxShellToolImpl(context)

    fun getAllTools(): List<OpenApiTool> = listOf(
        calculatorTool,
        webSearchTool,
        weatherTool,
        webPageFetcherTool,
        codeInterpreterTool,
        deviceInfoTool,
        termuxShellTool
    )
}
