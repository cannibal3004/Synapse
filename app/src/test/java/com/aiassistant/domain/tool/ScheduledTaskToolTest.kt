package com.aiassistant.domain.tool

import com.aiassistant.domain.model.ScheduleType
import com.aiassistant.domain.model.ScheduledTask
import com.aiassistant.domain.model.TaskExecutionHistory
import com.aiassistant.domain.repository.TaskRepository
import com.aiassistant.domain.scheduler.TaskScheduling
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tool the model drives to schedule its own work.
 *
 * The case that matters most is the first one: a task was created, stored, and scheduled under a
 * *different* id than the one stored, so the worker woke on time, found nothing, and failed
 * silently. From the outside it looked exactly like a task that simply never ran, and it took a
 * night of not running to notice.
 */
class ScheduledTaskToolTest {

    private val repository = FakeTaskRepository()
    private val scheduler = RecordingScheduler()
    private val tool = ScheduledTaskTool(repository, scheduler)

    private fun create(vararg args: Pair<String, Any?>): String =
        tool.execute(jsonOf(*args))

    // --- create --------------------------------------------------------------

    @Test
    fun `a created task is scheduled under the id it was stored with`() {
        create(
            "action" to "create",
            "title" to "Morning briefing",
            "prompt" to "Summarise the news",
            "cron_expression" to "0 8 * * *"
        )

        val stored = repository.tasks.value.single()
        assertEquals(
            "scheduled under an id the database does not hold",
            listOf(stored.id),
            scheduler.scheduled.map { it.id }
        )
    }

    @Test
    fun `the id reported back is the stored one`() {
        val reply = create(
            "action" to "create",
            "prompt" to "Check the weather",
            "interval_minutes" to 60
        )

        // A later update or delete uses the id from this reply, so it has to be the real one.
        assertTrue(reply.contains(repository.tasks.value.single().id))
    }

    @Test
    fun `create needs a prompt`() {
        val reply = create("action" to "create", "title" to "No prompt", "delay_minutes" to 5)

        assertTrue(reply.startsWith("Error:"))
        assertTrue(repository.tasks.value.isEmpty())
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `create needs a schedule`() {
        val reply = create("action" to "create", "prompt" to "Do a thing")

        assertTrue(reply.startsWith("Error:"))
        assertTrue(repository.tasks.value.isEmpty())
    }

    @Test
    fun `a missing title falls back to the prompt`() {
        create("action" to "create", "prompt" to "Check the bins go out", "delay_minutes" to 30)

        assertEquals("Check the bins go out", repository.tasks.value.single().title)
    }

    @Test
    fun `a one-off is due after its delay rather than immediately`() {
        val before = System.currentTimeMillis()
        create("action" to "create", "prompt" to "Remind me", "delay_minutes" to 120)

        val task = repository.tasks.value.single()
        assertEquals(ScheduleType.ONCE, task.scheduleType)
        assertTrue("due immediately", task.nextRunAt >= before + 119 * 60_000)
    }

    @Test
    fun `notify and enabled default to on`() {
        create("action" to "create", "prompt" to "Anything", "delay_minutes" to 5)

        val task = repository.tasks.value.single()
        assertTrue(task.isEnabled)
        assertTrue(task.shouldNotify)
    }

    @Test
    fun `a task created disabled is stored but not scheduled`() {
        create("action" to "create", "prompt" to "Paused", "delay_minutes" to 5, "enabled" to false)

        assertEquals(1, repository.tasks.value.size)
        // scheduleTask ignores a disabled task, so nothing should be waiting on it.
        assertTrue(scheduler.scheduled.none { it.isEnabled })
    }

    // --- update --------------------------------------------------------------

    @Test
    fun `editing the title leaves the next run alone`() {
        create("action" to "create", "prompt" to "Original", "cron_expression" to "0 8 * * *")
        val before = repository.tasks.value.single()

        tool.execute(jsonOf("action" to "update", "id" to before.id, "title" to "Renamed"))

        val after = repository.tasks.value.single()
        assertEquals("Renamed", after.title)
        assertEquals("a rename pushed the schedule back", before.nextRunAt, after.nextRunAt)
    }

    @Test
    fun `changing the schedule recomputes the next run`() {
        create("action" to "create", "prompt" to "Original", "delay_minutes" to 1)
        val before = repository.tasks.value.single()

        tool.execute(
            jsonOf("action" to "update", "id" to before.id, "cron_expression" to "0 8 * * *")
        )

        val after = repository.tasks.value.single()
        assertEquals(ScheduleType.CRON, after.scheduleType)
        assertTrue(after.nextRunAt != before.nextRunAt)
    }

    @Test
    fun `pausing a task cancels its work`() {
        create("action" to "create", "prompt" to "Pausable", "delay_minutes" to 5)
        val id = repository.tasks.value.single().id

        tool.execute(jsonOf("action" to "update", "id" to id, "enabled" to false))

        assertEquals(listOf(id), scheduler.cancelled)
    }

    @Test
    fun `updating an unknown id is an error, not a new task`() {
        val reply = tool.execute(jsonOf("action" to "update", "id" to "nope", "title" to "x"))

        assertTrue(reply.startsWith("Error:"))
        assertTrue(repository.tasks.value.isEmpty())
    }

    // --- delete and run_now --------------------------------------------------

    @Test
    fun `deleting cancels the work and removes the row`() {
        create("action" to "create", "prompt" to "Doomed", "delay_minutes" to 5)
        val id = repository.tasks.value.single().id

        tool.execute(jsonOf("action" to "delete", "id" to id))

        assertTrue(repository.tasks.value.isEmpty())
        assertEquals(listOf(id), scheduler.cancelled)
    }

    @Test
    fun `run_now queues the task without disturbing its schedule`() {
        create("action" to "create", "prompt" to "Now please", "cron_expression" to "0 8 * * *")
        val task = repository.tasks.value.single()

        tool.execute(jsonOf("action" to "run_now", "id" to task.id))

        assertEquals(listOf(task.id), scheduler.ranNow.map { it.id })
        assertEquals(task.nextRunAt, repository.tasks.value.single().nextRunAt)
    }

    // --- list and dispatch ---------------------------------------------------

    @Test
    fun `list says so when there is nothing`() {
        assertEquals("No scheduled tasks.", tool.execute(jsonOf("action" to "list")))
    }

    @Test
    fun `list includes the id, so the model can act on it`() {
        create("action" to "create", "prompt" to "Findable", "delay_minutes" to 5)
        val id = repository.tasks.value.single().id

        val listing = tool.execute(jsonOf("action" to "list"))

        assertTrue(listing.contains(id))
        assertTrue(listing.contains("Findable"))
    }

    @Test
    fun `an unknown or missing action is reported rather than guessed at`() {
        assertTrue(tool.execute(jsonOf("action" to "frobnicate")).startsWith("Error:"))
        assertTrue(tool.execute(jsonOf("title" to "no action")).startsWith("Error:"))
    }

    @Test
    fun `the schema names the tool the executor dispatches on`() {
        assertTrue(tool.getToolDescriptionJsonString().contains("\"name\": \"manage_tasks\""))
    }

    // --- fakes ---------------------------------------------------------------

    /** Arguments reach the tool as a JSON string, so the tests go in the same way the model does. */
    private fun jsonOf(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(",", "{", "}") { (k, v) ->
            val value = when (v) {
                null -> "null"
                is String -> "\"$v\""
                else -> v.toString()
            }
            "\"$k\":$value"
        }

    private class RecordingScheduler : TaskScheduling {
        val scheduled = mutableListOf<ScheduledTask>()
        val cancelled = mutableListOf<String>()
        val ranNow = mutableListOf<ScheduledTask>()

        override fun scheduleTask(task: ScheduledTask) {
            // Mirrors the real one, which silently ignores a disabled task.
            if (task.isEnabled) scheduled += task
        }

        override fun cancelTask(taskId: String) {
            cancelled += taskId
        }

        override fun scheduleNow(task: ScheduledTask) {
            ranNow += task
        }
    }

    private class FakeTaskRepository : TaskRepository {
        val tasks = MutableStateFlow<List<ScheduledTask>>(emptyList())

        override fun getAllTasks(): Flow<List<ScheduledTask>> = tasks

        override suspend fun getTaskById(id: String): ScheduledTask? =
            tasks.value.firstOrNull { it.id == id }

        /**
         * Deliberately stores under a *different* id from the one it was handed, and returns it.
         *
         * The real repository keeps the task's own id, so a tool that scheduled the object it
         * built rather than the one that came back would pass a fake that did the same -- and
         * that is exactly the bug. Reassigning here means only a tool that honours the returned
         * id can pass.
         */
        override suspend fun insertTask(task: ScheduledTask): String {
            val stored = task.copy(id = "stored-${task.id}")
            tasks.value = tasks.value + stored
            return stored.id
        }

        override suspend fun updateTask(task: ScheduledTask) {
            tasks.value = tasks.value.map { if (it.id == task.id) task else it }
        }

        override suspend fun deleteTask(id: String) {
            tasks.value = tasks.value.filterNot { it.id == id }
        }

        override suspend fun toggleTask(id: String, isEnabled: Boolean) {
            tasks.value = tasks.value.map { if (it.id == id) it.copy(isEnabled = isEnabled) else it }
        }

        override suspend fun updateTaskRunState(
            id: String,
            lastRunAt: Long,
            nextRunAt: Long,
            lastError: String?
        ) = Unit

        override fun getPendingTasks(now: Long): Flow<List<ScheduledTask>> = tasks
        override suspend fun getEarliestPendingTask(now: Long): ScheduledTask? = null
        override suspend fun insertExecutionHistory(history: TaskExecutionHistory) = Unit
        override fun getExecutionHistory(taskId: String, limit: Int): Flow<List<TaskExecutionHistory>> =
            flowOf(emptyList())
        override fun getExecutionHistoryById(id: String): Flow<TaskExecutionHistory?> = flowOf(null)
        override suspend fun getLastExecution(taskId: String): TaskExecutionHistory? = null
        override suspend fun getExecutionCount(taskId: String): Int = 0
        override suspend fun deleteExecutionHistory(taskId: String) = Unit
    }
}
