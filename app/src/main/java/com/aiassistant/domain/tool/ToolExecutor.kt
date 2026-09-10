package com.aiassistant.domain.tool

import com.aiassistant.domain.service.ActiveConversation
import com.aiassistant.domain.service.ToolManager
import com.aiassistant.domain.usecase.MemorySearchUseCase
import javax.inject.Inject

class ToolExecutor @Inject constructor(
    private val webSearchTool: WebSearchTool,
    private val calculatorTool: CalculatorTool,
    private val weatherTool: WeatherTool,
    private val webPageFetcherTool: WebPageFetcherTool,
    private val codeInterpreterTool: CodeInterpreterTool,
    private val deviceInfoTool: DeviceInfoTool,
    private val termuxShellTool: TermuxShellTool,
    memory: MemorySearchUseCase,
    activeConversation: ActiveConversation
) {

    // Shares the on-device tool definitions rather than restating their schemas here.
    private val rememberFactTool = RememberFactTool(memory) { activeConversation.id }
    private val recallFactsTool = RecallFactsTool(memory)

    init {
        ToolManager.registerOpenApiTool(rememberFactTool)
        ToolManager.registerOpenApiTool(recallFactsTool)
    }

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
                // Handed the raw JSON: this tool's parameters are its own business, and
                // restating them here is what broke it -- the copy read `arguments`, a name the
                // schema does not define, so no command ever reached Termux.
                "termux_shell" -> termuxShellTool.execute(arguments)
                "remember_fact" -> rememberFactTool.execute(arguments)
                "recall_facts" -> recallFactsTool.execute(arguments)
                else -> "Error: Unknown tool '$name'"
            }
        } catch (e: Exception) {
            "Error executing tool '$name': ${e.message}"
        }
    }
}
