package com.aiassistant.domain.tool

import com.aiassistant.domain.service.ActiveConversation
import com.aiassistant.domain.service.ToolManager
import com.aiassistant.domain.usecase.MemorySearchUseCase
import javax.inject.Inject

class ToolExecutor @Inject constructor(
    private val webSearchTool: WebSearchTool,
    private val weatherTool: WeatherTool,
    private val webPageFetcherTool: WebPageFetcherTool,
    private val deviceInfoTool: DeviceInfoTool,
    private val termuxShellTool: TermuxShellTool,
    private val calendarTool: CalendarTool,
    private val smsTool: SmsTool,
    memory: MemorySearchUseCase,
    activeConversation: ActiveConversation,
    taskRepository: com.aiassistant.domain.repository.TaskRepository,
    taskScheduler: com.aiassistant.data.scheduler.TaskScheduler
) {

    // Shares the on-device tool definitions rather than restating their schemas here.
    private val rememberFactTool = RememberFactTool(memory) { activeConversation.id }
    private val recallFactsTool = RecallFactsTool(memory)

    // Hosted models only. See the class doc for why the on-device engine does not get this one.
    private val scheduledTaskTool = ScheduledTaskTool(taskRepository, taskScheduler)

    init {
        ToolManager.registerOpenApiTool(rememberFactTool)
        ToolManager.registerOpenApiTool(recallFactsTool)
        ToolManager.registerOpenApiTool(scheduledTaskTool)
    }

    fun executeTool(name: String, arguments: String): String {
        return try {
            val args = JsonUtils.parseToJsonMap(arguments)
            
            when (name) {
                // Raw arguments, so the filters live in one place. Restating them here is
                // what broke termux_shell.
                "web_search" -> webSearchTool.execute(arguments)
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
                "device_info" -> {
                    val category = args["category"] as? String ?: "all"
                    deviceInfoTool.getDeviceInfo(category)
                }
                // Handed the raw JSON: this tool's parameters are its own business, and
                // restating them here is what broke it -- the copy read `arguments`, a name the
                // schema does not define, so no command ever reached Termux.
                "termux_shell" -> termuxShellTool.execute(arguments)
                "calendar" -> calendarTool.execute(arguments)
                "sms" -> smsTool.execute(arguments)
                "manage_tasks" -> scheduledTaskTool.execute(arguments)
                "remember_fact" -> rememberFactTool.execute(arguments)
                "recall_facts" -> recallFactsTool.execute(arguments)
                else -> "Error: Unknown tool '$name'"
            }
        } catch (e: Exception) {
            "Error executing tool '$name': ${e.message}"
        }
    }
}
