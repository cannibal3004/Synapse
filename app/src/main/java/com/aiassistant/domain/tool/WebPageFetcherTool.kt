package com.aiassistant.domain.tool

import com.aiassistant.domain.service.ToolManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import javax.inject.Inject

class WebPageFetcherTool @Inject constructor() {
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
                name = "web_fetch",
                description = "Fetch and extract the main content from a web page. Returns the page title, text content, and any links found on the page.",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "url" to mapOf(
                            "type" to "string",
                            "description" to "The full URL of the web page to fetch (must include http:// or https://)"
                        ),
                        "max_length" to mapOf(
                            "type" to "integer",
                            "description" to "Maximum number of characters to return (default: 2000)"
                        )
                    ),
                    "required" to listOf("url")
                ),
                executor = { arguments ->
                    runCatching {
                        val args = com.google.gson.Gson().fromJson(arguments, Map::class.java)
                        val url = args["url"] as? String ?: ""
                        val maxLength = (args["max_length"] as? Number)?.toInt() ?: 2000
                        fetchPage(url, maxLength)
                    }
                }
            )
        )
    }

    fun fetchPage(url: String, maxLength: Int = 2000): String {
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
            val errorMsg = e.message ?: e.javaClass.simpleName
            "Error fetching page: $errorMsg"
        }
    }

   
}
