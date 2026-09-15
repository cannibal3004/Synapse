package com.aiassistant.domain.tool

import com.aiassistant.domain.model.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a model asked for, as read from its arguments.
 *
 * Most of these are about tolerating the shapes models actually emit rather than the shape the
 * schema describes -- a wrong reading here produces a task that exists, looks right in the list,
 * and runs at a time nobody chose.
 */
class TaskScheduleParserTest {

    private val now = 1_800_000_000_000L // a fixed instant; nothing here depends on the real clock

    @Test
    fun `an explicit cron schedule is taken as given`() {
        val plan = TaskScheduleParser.parse(
            mapOf("schedule" to "cron", "cron_expression" to "0 8 * * *")
        )!!

        assertEquals(ScheduleType.CRON, plan.type)
        assertEquals("0 8 * * *", plan.cron)
    }

    @Test
    fun `a cron expression alone implies a cron schedule`() {
        // Models routinely supply the expression and omit `schedule` entirely.
        val plan = TaskScheduleParser.parse(mapOf("cron_expression" to "30 7 * * 1"))!!

        assertEquals(ScheduleType.CRON, plan.type)
    }

    @Test
    fun `interval_minutes alone implies an interval schedule`() {
        val plan = TaskScheduleParser.parse(mapOf("interval_minutes" to 30))!!

        assertEquals(ScheduleType.INTERVAL, plan.type)
        assertEquals(30L, plan.intervalMinutes)
    }

    @Test
    fun `delay_minutes alone implies a one-off`() {
        val plan = TaskScheduleParser.parse(mapOf("delay_minutes" to 120))!!

        assertEquals(ScheduleType.ONCE, plan.type)
        assertEquals(120L, plan.delayMinutes)
    }

    @Test
    fun `an explicit schedule wins over what the other fields imply`() {
        val plan = TaskScheduleParser.parse(
            mapOf("schedule" to "once", "interval_minutes" to 30, "delay_minutes" to 5)
        )!!

        assertEquals(ScheduleType.ONCE, plan.type)
        assertEquals(5L, plan.delayMinutes)
    }

    @Test
    fun `cron is preferred when a model sends everything at once`() {
        val plan = TaskScheduleParser.parse(
            mapOf("cron_expression" to "0 9 * * *", "interval_minutes" to 30, "delay_minutes" to 5)
        )!!

        assertEquals(ScheduleType.CRON, plan.type)
    }

    @Test
    fun `no schedule at all is not a schedule`() {
        assertNull(TaskScheduleParser.parse(mapOf("title" to "something")))
        assertNull(TaskScheduleParser.parse(emptyMap<String, Any?>()))
    }

    @Test
    fun `a blank cron expression does not count as one`() {
        assertNull(TaskScheduleParser.parse(mapOf("cron_expression" to "   ")))
    }

    @Test
    fun `an interval below WorkManager's floor is raised to it`() {
        // Accepting 5 would be promising a cadence the platform will not keep.
        val plan = TaskScheduleParser.parse(mapOf("interval_minutes" to 5))!!

        assertEquals(TaskScheduleParser.MIN_INTERVAL_MINUTES, plan.intervalMinutes)
    }

    @Test
    fun `a zero or negative delay becomes the minimum`() {
        assertEquals(
            TaskScheduleParser.DEFAULT_DELAY_MINUTES,
            TaskScheduleParser.parse(mapOf("delay_minutes" to 0))!!.delayMinutes
        )
        assertEquals(
            TaskScheduleParser.DEFAULT_DELAY_MINUTES,
            TaskScheduleParser.parse(mapOf("delay_minutes" to -30))!!.delayMinutes
        )
    }

    @Test
    fun `an interval schedule with no number falls back to the default`() {
        val plan = TaskScheduleParser.parse(mapOf("schedule" to "interval"))!!

        assertEquals(TaskScheduleParser.DEFAULT_INTERVAL_MINUTES, plan.intervalMinutes)
    }

    @Test
    fun `the schedule name is case insensitive`() {
        assertEquals(
            ScheduleType.CRON,
            TaskScheduleParser.parse(mapOf("schedule" to "CRON", "cron_expression" to "0 8 * * *"))!!.type
        )
    }

    @Test
    fun `numbers arriving as doubles are still read`() {
        // JSON parsing yields Long or Double depending on how the model wrote the literal.
        val plan = TaskScheduleParser.parse(mapOf("interval_minutes" to 45.0))!!

        assertEquals(45L, plan.intervalMinutes)
    }

    @Test
    fun `a one-off runs after its delay, not immediately`() {
        // The bug that made "remind me in two hours" fire on creation.
        val plan = TaskScheduleParser.parse(mapOf("delay_minutes" to 120))!!

        assertEquals(now + 120 * 60_000, plan.nextRunAt(now))
    }

    @Test
    fun `an interval's first run is one interval away`() {
        val plan = TaskScheduleParser.parse(mapOf("interval_minutes" to 30))!!

        assertEquals(now + 30 * 60_000, plan.nextRunAt(now))
    }

    @Test
    fun `a cron plan's next run is in the future`() {
        val plan = TaskScheduleParser.parse(mapOf("cron_expression" to "0 8 * * *"))!!

        assertTrue(plan.nextRunAt(now) > now)
    }

    @Test
    fun `an unparseable cron expression falls back rather than throwing`() {
        val plan = TaskScheduleParser.parse(mapOf("schedule" to "cron", "cron_expression" to "not cron"))!!

        // An hour out is a poor guess, but a task that exists beats an exception mid-call.
        assertEquals(now + 60 * 60_000, plan.nextRunAt(now))
    }
}
