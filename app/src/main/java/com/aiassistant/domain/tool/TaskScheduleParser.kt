package com.aiassistant.domain.tool

import com.aiassistant.domain.model.ScheduleType
import com.aiassistant.domain.usecase.CronScheduler

/**
 * Works out what schedule a model asked for.
 *
 * Kept apart from the tool because it is pure -- a map in, a plan out -- and because getting it
 * wrong is silent: the task is created, sits in the list looking correct, and runs at a time
 * nobody chose.
 */
internal object TaskScheduleParser {

    /** WorkManager will not repeat work more often than this, so anything less is a promise the task cannot keep. */
    const val MIN_INTERVAL_MINUTES = 15L

    const val DEFAULT_INTERVAL_MINUTES = 60L
    const val DEFAULT_DELAY_MINUTES = 1L

    /** One hour, used when a cron expression is unparseable rather than failing the whole call. */
    private const val CRON_FALLBACK_MS = 60 * 60_000L

    class Plan(
        val type: ScheduleType,
        val cron: String?,
        val intervalMinutes: Long,
        val delayMinutes: Long
    ) {
        fun nextRunAt(now: Long): Long = when (type) {
            ScheduleType.ONCE -> now + delayMinutes * 60_000
            ScheduleType.INTERVAL -> now + intervalMinutes * 60_000
            ScheduleType.CRON -> runCatching { CronScheduler.nextRun(cron.orEmpty(), now) }
                .getOrDefault(now + CRON_FALLBACK_MS)
        }
    }

    /** Null when the caller did not name a schedule at all, which for create is an error. */
    fun parse(args: Map<*, *>): Plan? {
        val requested = (args["schedule"] as? String)?.lowercase()
        val interval = (args["interval_minutes"] as? Number)?.toLong()
        val cron = (args["cron_expression"] as? String)?.takeIf { it.isNotBlank() }
        val delay = (args["delay_minutes"] as? Number)?.toLong()

        // Models frequently supply cron_expression or interval_minutes and leave `schedule` out.
        // Inferring beats rejecting a call that already said what it wanted.
        val type = when {
            requested == "cron" || (requested == null && cron != null) -> ScheduleType.CRON
            requested == "interval" || (requested == null && interval != null) -> ScheduleType.INTERVAL
            requested == "once" || (requested == null && delay != null) -> ScheduleType.ONCE
            else -> return null
        }

        return Plan(
            type = type,
            cron = cron,
            intervalMinutes = (interval ?: DEFAULT_INTERVAL_MINUTES).coerceAtLeast(MIN_INTERVAL_MINUTES),
            delayMinutes = (delay ?: DEFAULT_DELAY_MINUTES).coerceAtLeast(DEFAULT_DELAY_MINUTES)
        )
    }
}
