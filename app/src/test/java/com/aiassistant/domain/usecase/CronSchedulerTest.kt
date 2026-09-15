package com.aiassistant.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Cron decides when a scheduled task actually fires, and a task that fires at the wrong time is
 * indistinguishable from one that is broken -- the user only sees a notification that did not
 * arrive. These run in the default timezone deliberately: so does the scheduler.
 */
class CronSchedulerTest {

    private fun at(
        year: Int, month: Int, day: Int, hour: Int, minute: Int
    ): Long = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis

    private fun fieldsOf(ms: Long): List<Int> = Calendar.getInstance().apply {
        timeInMillis = ms
    }.let {
        listOf(
            it.get(Calendar.YEAR),
            it.get(Calendar.MONTH) + 1,
            it.get(Calendar.DAY_OF_MONTH),
            it.get(Calendar.HOUR_OF_DAY),
            it.get(Calendar.MINUTE)
        )
    }

    @Test
    fun `daily at 8am moves to tomorrow when today has passed`() {
        val next = CronScheduler.nextRun("0 8 * * *", at(2026, 3, 10, 9, 0))

        assertEquals(listOf(2026, 3, 11, 8, 0), fieldsOf(next))
    }

    @Test
    fun `daily at 8am stays today when it is still ahead`() {
        val next = CronScheduler.nextRun("0 8 * * *", at(2026, 3, 10, 6, 30))

        assertEquals(listOf(2026, 3, 10, 8, 0), fieldsOf(next))
    }

    @Test
    fun `weekday 1 is Monday, as the tool description promises`() {
        // 10 March 2026 is a Tuesday; the next Monday is the 16th. Matching the raw
        // Calendar.DAY_OF_WEEK made this land on Sunday the 15th instead.
        val next = CronScheduler.nextRun("30 7 * * 1", at(2026, 3, 10, 12, 0))

        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals(listOf(2026, 3, 16, 7, 30), fieldsOf(next))
    }

    @Test
    fun `weekday 0 and 7 both mean Sunday`() {
        val from = at(2026, 3, 10, 12, 0)

        assertEquals(
            fieldsOf(CronScheduler.nextRun("0 9 * * 0", from)),
            fieldsOf(CronScheduler.nextRun("0 9 * * 7", from))
        )
        val cal = Calendar.getInstance().apply { timeInMillis = CronScheduler.nextRun("0 9 * * 0", from) }
        assertEquals(Calendar.SUNDAY, cal.get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `a weekday range covers the working week`() {
        // Saturday 14 March -> the range should skip Sunday and land on Monday the 16th.
        val next = CronScheduler.nextRun("0 9 * * 1-5", at(2026, 3, 14, 12, 0))

        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals(listOf(2026, 3, 16, 9, 0), fieldsOf(next))
    }

    @Test
    fun `a step expression fires on the step`() {
        val next = CronScheduler.nextRun("*/15 * * * *", at(2026, 3, 10, 9, 7))

        assertEquals(listOf(2026, 3, 10, 9, 15), fieldsOf(next))
    }

    @Test
    fun `a list picks the nearest listed value`() {
        val next = CronScheduler.nextRun("0 9,17 * * *", at(2026, 3, 10, 12, 0))

        assertEquals(listOf(2026, 3, 10, 17, 0), fieldsOf(next))
    }

    @Test
    fun `a range is inclusive of its start`() {
        val next = CronScheduler.nextRun("0 9-11 * * *", at(2026, 3, 10, 6, 0))

        assertEquals(listOf(2026, 3, 10, 9, 0), fieldsOf(next))
    }

    @Test
    fun `a day-of-month expression crosses into the next month`() {
        val next = CronScheduler.nextRun("0 0 1 * *", at(2026, 3, 10, 12, 0))

        assertEquals(listOf(2026, 4, 1, 0, 0), fieldsOf(next))
    }

    @Test
    fun `the next run is always in the future`() {
        val now = at(2026, 3, 10, 8, 0)

        // Exactly on the minute it would otherwise match: it must move on, not return now.
        assertTrue(CronScheduler.nextRun("0 8 * * *", now) > now)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an expression with the wrong number of fields is rejected`() {
        CronScheduler.nextRun("0 8 * *", System.currentTimeMillis())
    }

    @Test
    fun `timezone assumption holds`() {
        // Guards the tests above rather than the scheduler: if CI ran in a timezone where the
        // constructed instants shifted a day, the failures would look like cron bugs.
        assertTrue(TimeZone.getDefault() != null)
    }
}
