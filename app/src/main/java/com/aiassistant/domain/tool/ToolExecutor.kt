package com.aiassistant.domain.tool

import javax.inject.Inject

class ToolExecutor @Inject constructor(
    private val webSearchTool: WebSearchTool,
    private val calculatorTool: CalculatorTool,
    private val weatherTool: WeatherTool,
    private val webPageFetcherTool: WebPageFetcherTool,
    private val codeInterpreterTool: CodeInterpreterTool,
    private val deviceInfoTool: DeviceInfoTool,
    private val termuxShellTool: TermuxShellTool
) {

    fun executeTool(name: String, arguments: String): String {
        return try {
            val args = JsonUtils.parseToJsonMap(arguments)
            
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
                "termux_shell" -> {
                    val command = args["command"] as? String ?: ""
                    val arguments = args["arguments"] as? String ?: ""
                    val script = args["script"] as? String
                    val workdir = args["workdir"] as? String
                    val timeout = (args["timeout"] as? Number)?.toInt() ?: 30
                    termuxShellTool.executeCommand(command, arguments, script, workdir, timeout)
                }
                else -> "Error: Unknown tool '$name'"
            }
        } catch (e: Exception) {
            "Error executing tool '$name': ${e.message}"
        }
    }
}
