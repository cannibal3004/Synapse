package com.aiassistant.domain.tool

import com.aiassistant.domain.model.ScheduleType
import com.aiassistant.domain.model.ScheduledTask
import com.aiassistant.domain.repository.TaskRepository
import com.aiassistant.domain.scheduler.TaskScheduling
import com.google.ai.edge.litertlm.OpenApiTool
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lets the assistant schedule its own work.
 *
 * The app already had a scheduler, a worker and a Tasks screen; the only thing that could reach
 * them was the UI, so "check this every morning and tell me" was something the user had to build
 * by hand. This is the adapter over machinery that already existed.
 *
 * One tool with an `action` rather than five tools. Every schema is paid for in the context of
 * every request, and five near-identical descriptions is five chances for a small model to pick
 * the wrong one.
 *
 * Not available to the on-device engine: that runs in `:llm` with its own object graph, and two
 * processes writing the same Room database is not something to take on for this.
 */
class ScheduledTaskTool(
    private val taskRepository: TaskRepository,
    private val taskScheduler: TaskScheduling
) : OpenApiTool {

    override fun getToolDescriptionJsonString(): String = """
        {
          "name": "manage_tasks",
          "description": "Create and manage scheduled tasks that run on their own, in the background, without the user present. A task is a prompt you write now for yourself to answer later, on a schedule; its result is delivered to the user as a notification. Use this whenever the user asks for something recurring or deferred -- 'every morning', 'each Monday', 'in two hours', 'keep an eye on', 'remind me to check'. Do not use it for work you can simply do now. Actions: 'list' every task, 'create' a new one, 'update' an existing one, 'delete' one, 'run_now' to execute one immediately without waiting for its schedule.",
          "parameters": {
            "type": "object",
            "properties": {
              "action": {
                "type": "string",
                "enum": ["list", "create", "update", "delete", "run_now"],
                "description": "What to do. Start with 'list' if you need an id."
              },
              "id": {
                "type": "string",
                "description": "The task id, from 'list'. Required for update, delete and run_now."
              },
              "title": {
                "type": "string",
                "description": "Short name shown in the task list and the notification. Required for create."
              },
              "prompt": {
                "type": "string",
                "description": "REQUIRED for create: the instruction that will be run on the schedule. Write it as a standalone request -- the run has none of this conversation's context, so name anything it needs explicitly. It has the same tools you do."
              },
              "schedule": {
                "type": "string",
                "enum": ["once", "interval", "cron"],
                "description": "'once' runs a single time after delay_minutes; 'interval' repeats every interval_minutes; 'cron' follows cron_expression."
              },
              "delay_minutes": {
                "type": "integer",
                "description": "For schedule 'once': how long from now to run. Default 1."
              },
              "interval_minutes": {
                "type": "integer",
                "description": "For schedule 'interval': minutes between runs. Minimum 15."
              },
              "cron_expression": {
                "type": "string",
                "description": "For schedule 'cron': standard five-field cron, minute hour day month weekday. '0 8 * * *' is every day at 08:00 local time; '30 7 * * 1' is 07:30 each Monday."
              },
              "enabled": {
                "type": "boolean",
                "description": "Whether the task runs. Use update with false to pause one rather than deleting it."
              },
              "notify": {
                "type": "boolean",
                "description": "Whether to notify the user when it finishes. Default true; false only when the user asked for it to be silent."
              }
            },
            "required": ["action"]
          }
        }
    """.trimIndent()

    override fun execute(paramsJsonString: String): String = runCatching {
        val args = JsonUtils.parseToJsonMap(paramsJsonString)
        when (val action = (args["action"] as? String)?.lowercase()) {
            "list" -> list()
            "create" -> create(args)
            "update" -> update(args)
            "delete" -> delete(args)
            "run_now" -> runNow(args)
            null -> "Error: Missing 'action'. One of: list, create, update, delete, run_now."
            else -> "Error: Unknown action '$action'. One of: list, create, update, delete, run_now."
        }
    }.getOrElse { "Error: ${it.message}" }

    // Tool executors are called from a background thread and are expected to block until they
    // have an answer, which is what the repository's suspend functions need bridging for.
    private fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    private fun list(): String {
        val tasks = blocking { taskRepository.getAllTasks().first() }
        if (tasks.isEmpty()) return "No scheduled tasks."
        return buildString {
            append("${tasks.size} scheduled task${if (tasks.size == 1) "" else "s"}:\n")
            tasks.forEach { append("\n").append(it.describe()) }
        }
    }

    private fun create(args: Map<*, *>): String {
        val title = (args["title"] as? String)?.takeIf { it.isNotBlank() }
        val prompt = (args["prompt"] as? String)?.takeIf { it.isNotBlank() }
            ?: return "Error: 'prompt' is required for create -- it is the instruction the task runs."

        val schedule = TaskScheduleParser.parse(args) ?: return scheduleHelp()
        val now = System.currentTimeMillis()
        val task = ScheduledTask(
            title = title ?: prompt.take(40),
            prompt = prompt,
            scheduleType = schedule.type,
            cronExpression = schedule.cron,
            intervalMinutes = schedule.intervalMinutes,
            isEnabled = args["enabled"] as? Boolean ?: true,
            shouldNotify = args["notify"] as? Boolean ?: true,
            nextRunAt = schedule.nextRunAt(now)
        )

        // Scheduled and reported under the id the repository actually stored, so the work
        // finds its task and a later update or delete finds the same one.
        val stored = task.copy(id = blocking { taskRepository.insertTask(task) })
        taskScheduler.scheduleTask(stored)
        return "Created.\n${stored.describe()}"
    }

    private fun update(args: Map<*, *>): String {
        val id = (args["id"] as? String) ?: return "Error: 'id' is required for update. Use action 'list' to find it."
        val existing = blocking { taskRepository.getTaskById(id) }
            ?: return "Error: No task with id '$id'. Use action 'list' to see what exists."

        val schedule = TaskScheduleParser.parse(args)
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            title = (args["title"] as? String)?.takeIf { it.isNotBlank() } ?: existing.title,
            prompt = (args["prompt"] as? String)?.takeIf { it.isNotBlank() } ?: existing.prompt,
            scheduleType = schedule?.type ?: existing.scheduleType,
            cronExpression = schedule?.cron ?: existing.cronExpression,
            intervalMinutes = schedule?.intervalMinutes ?: existing.intervalMinutes,
            isEnabled = args["enabled"] as? Boolean ?: existing.isEnabled,
            shouldNotify = args["notify"] as? Boolean ?: existing.shouldNotify,
            // Only recomputed when the schedule itself changed; otherwise an edit to the title
            // would silently push the next run back.
            nextRunAt = schedule?.nextRunAt(now) ?: existing.nextRunAt,
            updatedAt = now
        )

        blocking { taskRepository.updateTask(updated) }
        if (updated.isEnabled) taskScheduler.scheduleTask(updated) else taskScheduler.cancelTask(id)
        return "Updated.\n${updated.describe()}"
    }

    private fun delete(args: Map<*, *>): String {
        val id = (args["id"] as? String) ?: return "Error: 'id' is required for delete. Use action 'list' to find it."
        val existing = blocking { taskRepository.getTaskById(id) }
            ?: return "Error: No task with id '$id'."
        taskScheduler.cancelTask(id)
        blocking { taskRepository.deleteTask(id) }
        return "Deleted \"${existing.title}\"."
    }

    private fun runNow(args: Map<*, *>): String {
        val id = (args["id"] as? String) ?: return "Error: 'id' is required for run_now. Use action 'list' to find it."
        val existing = blocking { taskRepository.getTaskById(id) }
            ?: return "Error: No task with id '$id'."
        taskScheduler.scheduleNow(existing)
        return "Queued \"${existing.title}\" to run now. It runs in the background; its result " +
            "arrives as a notification rather than in this conversation."
    }

    private fun scheduleHelp(): String =
        "Error: No schedule given. Pass 'cron_expression' (e.g. \"0 8 * * *\" for 08:00 daily), " +
            "or 'interval_minutes' to repeat, or 'delay_minutes' to run once after a delay."

    private fun ScheduledTask.describe(): String {
        val when_ = when (scheduleType) {
            ScheduleType.ONCE -> "once"
            ScheduleType.INTERVAL -> "every $intervalMinutes min"
            ScheduleType.CRON -> "cron ${cronExpression.orEmpty()}"
        }
        return buildString {
            append("- \"$title\" [$id]\n")
            append("  $when_")
            if (!isEnabled) append(" (paused)")
            if (nextRunAt > 0) append(", next ${stamp(nextRunAt)}")
            append("\n")
            append("  prompt: ${prompt.take(120)}${if (prompt.length > 120) "..." else ""}\n")
            if (runCount > 0) append("  run $runCount time${if (runCount == 1) "" else "s"}")
            lastRunAt?.let { append(", last ${stamp(it)}") }
            if (runCount > 0 || lastRunAt != null) append("\n")
            lastError?.let { append("  last error: $it\n") }
        }
    }

    private fun stamp(ms: Long): String =
        SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()).format(Date(ms))
}
