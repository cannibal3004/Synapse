package com.aiassistant.data.scheduler

import android.content.Context
import android.util.Log
import androidx.work.*
import com.aiassistant.data.worker.TaskWorker
import com.aiassistant.domain.model.ScheduleType
import com.aiassistant.domain.model.ScheduledTask
import com.aiassistant.domain.usecase.CronScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskScheduler @Inject constructor(
    @ApplicationContext private val applicationContext: Context
) {
    companion object {
        private const val TAG = "TaskScheduler"
    }

    fun scheduleTask(task: ScheduledTask) {
        if (!task.isEnabled) return

        val workRequest = buildWorkRequest(task)
        val uniqueWorkName = "task_${task.id}"

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            uniqueWorkName,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        Log.d(TAG, "Task '${task.title}' ($uniqueWorkName) scheduled for ${formatSchedule(task)}")
    }

    fun scheduleNow(task: ScheduledTask) {
        val workRequest = OneTimeWorkRequestBuilder<TaskWorker>()
            .setInitialDelay(0, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(
                Data.Builder()
                    .putString(TaskWorker.KEY_TASK_ID, task.id)
                    .putString(TaskWorker.KEY_TASK_TITLE, task.title)
                    .build()
            )
            .addTag("task_${task.id}")
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "task_now_${task.id}",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        Log.d(TAG, "Task '${task.title}' scheduled for immediate execution")
    }

    fun cancelTask(taskId: String) {
        WorkManager.getInstance(applicationContext).cancelUniqueWork("task_$taskId")
        Log.d(TAG, "Task $taskId cancellation requested")
    }

    fun cancelAllTasks() {
        WorkManager.getInstance(applicationContext).cancelAllWorkByTag("task_")
        Log.d(TAG, "All tasks cancellation requested")
    }

    fun rescheduleAllEnabledTasks(tasks: List<ScheduledTask>) {
        val enabledTasks = tasks.filter { it.isEnabled }
        for (task in enabledTasks) {
            scheduleTask(task)
        }
        Log.d(TAG, "Rescheduled ${enabledTasks.size} enabled tasks")
    }

    /**
     * Gives every enabled task work, without disturbing work it already has.
     *
     * A task whose work goes missing is invisible: the row sits in the list looking scheduled,
     * and simply never runs again. That happened for real -- work enqueued under an id the
     * database never stored -- and nothing would ever have noticed. KEEP rather than REPLACE, so
     * this is a repair and not a reset: a task that is correctly scheduled keeps the run it is
     * already waiting for rather than having its timer restarted on every launch.
     */
    fun ensureScheduled(tasks: List<ScheduledTask>) {
        val enabled = tasks.filter { it.isEnabled }
        for (task in enabled) {
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "task_${task.id}",
                ExistingWorkPolicy.KEEP,
                buildWorkRequest(task)
            )
        }
        Log.d(TAG, "Checked ${enabled.size} enabled task(s) have work scheduled")
    }

    private fun buildWorkRequest(task: ScheduledTask): OneTimeWorkRequest {
        val builder = OneTimeWorkRequestBuilder<TaskWorker>()
            .setInputData(
                Data.Builder()
                    .putString(TaskWorker.KEY_TASK_ID, task.id)
                    .putString(TaskWorker.KEY_TASK_TITLE, task.title)
                    .build()
            )
            .addTag("task_${task.id}")
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30, TimeUnit.SECONDS
            )

        val constraintsBuilder = Constraints.Builder()
            .setRequiresBatteryNotLow(true)

        when (task.scheduleType) {
            // Both of these carry the time they are due in nextRunAt, and it is the only place
            // a delay the user asked for survives. ONCE used to hard-code a zero delay, so
            // "remind me in two hours" ran the moment it was created.
            ScheduleType.ONCE,
            ScheduleType.INTERVAL -> {
                val delayMs = (task.nextRunAt - System.currentTimeMillis()).coerceAtLeast(0)
                builder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            }
            ScheduleType.CRON -> {
                val nextRunAt = try {
                    CronScheduler.nextRun(task.cronExpression ?: "", System.currentTimeMillis())
                } catch (e: Exception) {
                    System.currentTimeMillis() + (60 * 60 * 1000)
                }
                val delayMs = nextRunAt - System.currentTimeMillis()
                if (delayMs > 0) {
                    builder.setInitialDelay(
                        delayMs,
                        TimeUnit.MILLISECONDS
                    )
                } else {
                    builder.setInitialDelay(0, TimeUnit.MILLISECONDS)
                }
            }
        }

        // A hosted model is reached over the network whatever the prompt says, and an
        // on-device one never is. The previous rule sniffed the prompt for words like "weather"
        // and demanded an UNMETERED connection otherwise -- which got it wrong both ways: a task
        // that needed the API but said none of those words would not run on mobile data, and a
        // task that needed nothing at all still waited for wifi.
        constraintsBuilder.setRequiredNetworkType(
            if (task.onDevice) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED
        )

        builder.setConstraints(constraintsBuilder.build())

        return builder.build()
    }

    private fun formatSchedule(task: ScheduledTask): String {
        return when (task.scheduleType) {
            ScheduleType.ONCE -> "one-time"
            ScheduleType.INTERVAL -> "every ${task.intervalMinutes}m"
            ScheduleType.CRON -> task.cronExpression ?: "cron"
        }
    }
}
