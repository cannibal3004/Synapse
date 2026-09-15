package com.aiassistant.domain.scheduler

import com.aiassistant.domain.model.ScheduledTask

/**
 * What the domain needs from a scheduler, without knowing it is WorkManager.
 *
 * Narrow on purpose: these are the three things a tool does to a task. The implementation lives
 * in `data/scheduler` and carries the rest -- reconciliation at launch, bulk rescheduling -- which
 * nothing in the domain calls.
 */
interface TaskScheduling {

    /** Schedules a task for its next run. A disabled task is ignored. */
    fun scheduleTask(task: ScheduledTask)

    /** Drops any pending work for a task, by id. */
    fun cancelTask(taskId: String)

    /** Queues a task to run immediately, leaving its ordinary schedule alone. */
    fun scheduleNow(task: ScheduledTask)
}
