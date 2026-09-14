package com.aiassistant.domain.tool

import android.content.Context
import android.util.Log
import com.aiassistant.domain.service.ToolManager
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val TAG = "WebSearchTool"
private const val EXA_API_URL = "https://api.exa.ai/search"

/** Beyond this the answer stops being research and starts being a context problem. */
private const val TOTAL_BUDGET_CHARS = 24_000

/**
 * Web search, through Exa.
 *
 * One implementation for both paths, like the shell tool: hosted models reach it through
 * [ToolManager] and [ToolExecutor], the on-device engine through [OpenApiTool].
 *
 * It used to send a bare query, take five results and show 300 characters of each, which meant
 * the model had to follow almost every search with a `web_fetch` to learn anything -- two round
 * trips and a whole page of context to answer what the search could have answered directly.
 * Exa returns page text, summaries and highlights inline, and filters by date, domain and
 * category, so most of that second trip is avoidable.
 */
class WebSearchTool @Inject constructor(
    private val context: Context
) : OpenApiTool {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    init {
        register()
    }

    fun register() {
        ToolManager.registerOpenApiTool(this)
    }

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "web_search",
          "description": "Search the web with Exa, a semantic search engine, and read the results in one call. By default each result comes back with a chunk of the page's actual text, so you usually do not need web_fetch afterwards -- reach for web_fetch only when you need a specific page in full. Phrase the query as a description of what you want rather than as keywords. Narrow with the filters instead of searching repeatedly: date filters for recent news, include_domains when the user names a source, category to restrict the kind of page.",
          "parameters": {
            "type": "object",
            "properties": {
              "query": {
                "type": "string",
                "description": "REQUIRED. What you are looking for, as a description rather than keywords."
              },
              "num_results": {
                "type": "integer",
                "description": "How many results, 1-25. Default 6. Ask for more only when surveying; each one costs context."
              },
              "text_chars": {
                "type": "integer",
                "description": "Characters of page text per result. Default 1200. Use 0 for links and titles only, or up to 8000 when you need to read the sources properly."
              },
              "summary_query": {
                "type": "string",
                "description": "Ask Exa to summarise every result against this question, e.g. 'what funding did they raise'. Much cheaper than pulling full text when you want one specific fact from many pages."
              },
              "category": {
                "type": "string",
                "enum": ["company", "research paper", "news", "pdf", "github", "personal site", "people", "financial report"],
                "description": "Restrict to a kind of page. Note 'company' and 'people' ignore the date and exclude_domains filters."
              },
              "include_domains": {
                "type": "array",
                "items": { "type": "string" },
                "description": "Only return results from these domains, e.g. [\"arxiv.org\"]. Use when the user names a source."
              },
              "exclude_domains": {
                "type": "array",
                "items": { "type": "string" },
                "description": "Never return results from these domains."
              },
              "start_published_date": {
                "type": "string",
                "description": "Only pages published on or after this date, YYYY-MM-DD. Use for anything time-sensitive."
              },
              "end_published_date": {
                "type": "string",
                "description": "Only pages published on or before this date, YYYY-MM-DD."
              },
              "type": {
                "type": "string",
                "enum": ["auto", "neural", "fast", "keyword"],
                "description": "Search strategy. Default 'auto'. 'fast' trades quality for latency; 'neural' is purely semantic."
              }
            },
            "required": ["query"]
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String {
        val args = runCatching { JsonUtils.parseToJsonMap(paramsJsonString) }.getOrElse {
            return "Error: Could not read the search arguments: ${it.message}"
        }
        val query = (args["query"] as? String)?.takeIf { it.isNotBlank() }
            ?: return "Error: Missing 'query'."
        return performSearchSync(
            query = query,
            numResults = (args["num_results"] as? Number)?.toInt()?.coerceIn(1, 25) ?: 6,
            textChars = (args["text_chars"] as? Number)?.toInt()?.coerceIn(0, 8000) ?: 1200,
            summaryQuery = (args["summary_query"] as? String)?.takeIf { it.isNotBlank() },
            category = (args["category"] as? String)?.takeIf { it.isNotBlank() },
            includeDomains = args["include_domains"].asStringList(),
            excludeDomains = args["exclude_domains"].asStringList(),
            startDate = (args["start_published_date"] as? String)?.takeIf { it.isNotBlank() },
            endDate = (args["end_published_date"] as? String)?.takeIf { it.isNotBlank() },
            type = (args["type"] as? String)?.takeIf { it.isNotBlank() } ?: "auto"
        )
    }

    private fun Any?.asStringList(): List<String>? = (this as? List<*>)
        ?.mapNotNull { (it as? String)?.takeIf(String::isNotBlank) }
        ?.takeIf { it.isNotEmpty() }

    /** Kept for callers that only have a query, and as the single place the request is built. */
    fun performSearchSync(
        query: String,
        numResults: Int = 6,
        textChars: Int = 1200,
        summaryQuery: String? = null,
        category: String? = null,
        includeDomains: List<String>? = null,
        excludeDomains: List<String>? = null,
        startDate: String? = null,
        endDate: String? = null,
        type: String = "auto"
    ): String {
        return try {
            val apiKey = getExaApiKey()
            if (apiKey.isNullOrEmpty()) {
                return "Error: Exa API key not configured. Please add your Exa API key in Settings."
            }

            val contents = mutableMapOf<String, Any>()
            if (textChars > 0) contents["text"] = mapOf("maxCharacters" to textChars)
            if (summaryQuery != null) contents["summary"] = mapOf("query" to summaryQuery)
            // With neither, Exa returns links alone, which is a legitimate ask (a survey of what
            // is out there) but not the default.
            if (contents.isEmpty()) contents["highlights"] = mapOf("numSentences" to 2)

            val body = mutableMapOf<String, Any>(
                "query" to query,
                "numResults" to numResults,
                "type" to type,
                "contents" to contents
            )
            category?.let { body["category"] = it }
            includeDomains?.let { body["includeDomains"] = it }
            excludeDomains?.let { body["excludeDomains"] = it }
            startDate?.let { body["startPublishedDate"] = it }
            endDate?.let { body["endPublishedDate"] = it }

            val requestBody = Gson().toJson(body)
            Log.d(TAG, "Exa request: $requestBody")

            val request = Request.Builder()
                .url(EXA_API_URL)
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("Accept", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body.string()
            Log.d(TAG, "Exa response code: ${response.code}")

            if (!response.isSuccessful) {
                // Exa's 400s say exactly which filter was wrong, which is worth passing through
                // rather than flattening into "search failed".
                return "Error: Exa returned ${response.code}: ${responseBody.take(500)}"
            }

            val results = JsonParser.parseString(responseBody)
                .asJsonObject?.getAsJsonArray("results")
            if (results == null || results.isEmpty()) {
                return "No results for: $query" + describeFilters(
                    category, includeDomains, excludeDomains, startDate, endDate
                )
            }

            render(query, results.map { it.asJsonObject })
        } catch (e: Exception) {
            Log.e(TAG, "Search failed", e)
            "Error: Search failed: ${e.message}"
        }
    }

    private fun render(query: String, results: List<JsonObject>): String = buildString {
        append("Search results for: $query\n")
        var spent = 0
        var shown = 0

        for ((i, r) in results.withIndex()) {
            if (spent > TOTAL_BUDGET_CHARS) break
            val entry = buildString {
                append("\n[${i + 1}] ${r.str("title") ?: "(untitled)"}\n")
                r.str("url")?.let { append("    $it\n") }
                val meta = listOfNotNull(
                    r.str("publishedDate")?.take(10),
                    r.str("author")?.takeIf { it.length < 60 }
                )
                if (meta.isNotEmpty()) append("    ${meta.joinToString(" · ")}\n")

                r.str("summary")?.let { append("    Summary: ${it.trim()}\n") }

                val highlights = r.getAsJsonArray("highlights")
                if (highlights != null && !highlights.isEmpty()) {
                    highlights.take(2).forEach { append("    \"${it.asString.trim()}\"\n") }
                }

                r.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    append("    ").append(it.replace("\n", "\n    ")).append("\n")
                }
            }
            append(entry)
            spent += entry.length
            shown++
        }

        if (shown < results.size) {
            append("\n(${results.size - shown} further results omitted to stay within a sensible size; ")
            append("narrow the query or lower text_chars to see them.)\n")
        }
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

    private fun describeFilters(
        category: String?,
        include: List<String>?,
        exclude: List<String>?,
        start: String?,
        end: String?
    ): String {
        val parts = listOfNotNull(
            category?.let { "category=$it" },
            include?.let { "includeDomains=${it.joinToString(",")}" },
            exclude?.let { "excludeDomains=${it.joinToString(",")}" },
            start?.let { "after $it" },
            end?.let { "before $it" }
        )
        return if (parts.isEmpty()) "" else
            "\nFilters applied: ${parts.joinToString("; ")}. Try relaxing them."
    }

    private fun getExaApiKey(): String? {
        return try {
            context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .getString("exa_api_key", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading Exa API key", e)
            null
        }
    }
}
