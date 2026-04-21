package com.aiassistant.domain.tool

import com.google.gson.Gson
import javax.inject.Inject

class ToolExecutor @Inject constructor(
    private val webSearchTool: WebSearchTool,
    private val calculatorTool: CalculatorTool,
    private val weatherTool: WeatherTool,
    private val webPageFetcherTool: WebPageFetcherTool,
    private val codeInterpreterTool: CodeInterpreterTool,
    private val deviceInfoTool: DeviceInfoTool
) {
    private val gson = Gson()

    fun executeTool(name: String, arguments: String): String {
        return try {
            val args = gson.fromJson(arguments, Map::class.java)
            
            when (name) {
                "web_search" -> {
                    val query = args["query"] as? String ?: ""
                    webSearchTool.performSearchSync(query)
                }
                "calculator" -> {
                    val expression = args["expression"] as? String ?: ""
                    calculatorTool.performCalculation(expression)
                }
                "weather" -> {
                    val city = args["city"] as? String ?: ""
                    val units = args["units"] as? String ?: "celsius"
                    weatherTool.fetchWeather(city, units)
                }
                "web_fetch" -> {
                    val url = args["url"] as? String ?: ""
                    val maxLength = (args["max_length"] as? Number)?.toInt() ?: 2000
                    webPageFetcherTool.fetchPage(url, maxLength)
                }
                "code_interpreter" -> {
                    val code = args["code"] as? String ?: ""
                    val language = args["language"] as? String ?: "javascript"
                    codeInterpreterTool.executeCode(code, language)
                }
                "device_info" -> {
                    val category = args["category"] as? String ?: "all"
                    deviceInfoTool.getDeviceInfo(category)
                }
                else -> "Error: Unknown tool '$name'"
            }
        } catch (e: Exception) {
            "Error executing tool '$name': ${e.message}"
        }
    }
}
