package com.aiassistant.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aiassistant.MainActivity
import com.aiassistant.domain.model.TaskExecutionResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val applicationContext: Context
) {
    companion object {
        const val NOTIFICATION_CHANNEL_TASKS = "task_notifications"
        const val NOTIFICATION_CHANNEL_TASKS_ID = 1001
        const val NOTIFICATION_CHANNEL_RUNNING = "task_running"
        const val NOTIFICATION_CHANNEL_RUNNING_ID = 1002
        const val TAG = "NotificationHelper"
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = applicationContext
                .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val taskChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_TASKS,
                "Task Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for completed scheduled tasks"
                enableVibration(true)
                enableLights(true)
            }

            val runningChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_RUNNING,
                "Task Running",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when a task is currently running"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(taskChannel)
            notificationManager.createNotificationChannel(runningChannel)
        }
    }

    fun createTaskNotification(title: String, text: String, id: Int): androidx.work.ForegroundInfo {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_RUNNING)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return androidx.work.ForegroundInfo(id, notification)
    }

    fun notifyTaskComplete(result: TaskExecutionResult, executionHistoryId: String) {
        Log.d(TAG, "Notifying task completion: ${result.taskTitle}")

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("DEEP_LINK_TYPE", "EXECUTION_HISTORY")
            putExtra("DEEP_LINK_ID", executionHistoryId)
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            executionHistoryId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val responsePreview = if (result.response.length > 200) {
            result.response.take(200) + "..."
        } else {
            result.response
        }

        val channel = applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getBoolean("task_notify_enabled", true)

        if (!channel) return

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_TASKS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Task Complete: ${result.taskTitle}")
            .setContentText(responsePreview.trim())
            .setStyle(NotificationCompat.BigTextStyle().bigText(responsePreview.trim()))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        val notificationManager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(result.taskId.hashCode(), notification)
    }

    fun notifyTaskFailed(result: TaskExecutionResult, executionHistoryId: String) {
        Log.d(TAG, "Notifying task failure: ${result.taskTitle}")

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("DEEP_LINK_TYPE", "EXECUTION_HISTORY")
            putExtra("DEEP_LINK_ID", executionHistoryId)
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            executionHistoryId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val errorMessage = result.error ?: "Unknown error"

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_TASKS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Task Failed: ${result.taskTitle}")
            .setContentText(errorMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(errorMessage))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        val notificationManager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(result.taskId.hashCode(), notification)
    }
}
