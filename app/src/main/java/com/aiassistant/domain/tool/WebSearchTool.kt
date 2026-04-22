package com.aiassistant.domain.tool

import android.content.Context
import android.util.Log
import com.aiassistant.domain.service.ToolManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

private const val TAG = "WebSearchTool"
private const val EXA_API_URL = "https://api.exa.ai/search"

class WebSearchTool @Inject constructor(
    private val context: Context
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    init {
        register()
    }

    fun register() {
        ToolManager.registerTool(
            ToolManager.ToolDefinition(
                name = "web_search",
                description = "Search the web for information using Exa AI semantic search. Takes a query string and returns relevant results with titles, URLs, and content snippets.",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf(
                            "type" to "string",
                            "description" to "The search query"
                        )
                    ),
                    "required" to listOf("query")
                ),
                executor = { arguments ->
                    runCatching {
                        val args = JsonUtils.parseToJsonMap(arguments)
                        val query = args["query"] as? String ?: ""
                        performSearchSync(query)
                    }
                }
            )
        )
    }

    private fun getExaApiKey(): String? {
        return try {
            val dataStore = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            dataStore.getString("exa_api_key", null).also { key ->
                Log.d(TAG, "Exa API key loaded: ${if (key.isNullOrEmpty()) "null/empty" else "found (length=${key?.length})"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading Exa API key", e)
            null
        }
    }

    fun performSearchSync(query: String): String {
        return try {
            if (query.isBlank()) {
                return "Error: No search query provided"
            }
            val apiKey = getExaApiKey()
            if (apiKey.isNullOrEmpty()) {
                return "Error: Exa API key not configured. Please add your Exa API key in Settings."
            }

            Log.d(TAG, "Searching with Exa AI for: $query")

            val requestBody = com.google.gson.Gson().toJson(mapOf(
                "query" to query,
                "numResults" to 5,
                "startCitedByCount" to null,
                "type" to "auto"
            ))

            Log.d(TAG, "Request body: $requestBody")

            val request = Request.Builder()
                .url(EXA_API_URL)
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("Accept", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            Log.d(TAG, "Exa response code: ${response.code}")

            val responseBody = response.body?.string() ?: "{}"
            Log.d(TAG, "Exa response: $responseBody")

            if (!response.isSuccessful) {
                return "Error: Exa API returned ${response.code}: $responseBody"
            }

            val json = com.google.gson.JsonParser.parseString(responseBody)
            val resultsArray = json.asJsonObject?.get("results")?.asJsonArray 
                ?: json.asJsonObject?.get("highlights")?.asJsonObject?.get("text")?.asJsonArray
                ?: com.google.gson.JsonArray()

            if (resultsArray.isEmpty()) {
                return "No search results found for: $query"
            }

            val results = mutableListOf<WebSearchResult>()
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
                    results.add(WebSearchResult(title, url, snippet))
                }
            }

            Log.d(TAG, "Found ${results.size} results from Exa")

            if (results.isEmpty()) {
                return "No search results found for: $query"
            } else {
                buildString {
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
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            "Error performing search: $errorMsg"
        }
    }

    data class WebSearchResult(
        val title: String?,
        val url: String?,
        val snippet: String?
    )
}
