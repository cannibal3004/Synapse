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
        const val NOTIFICATION_CHANNEL_DOWNLOAD = "model_download"
        const val NOTIFICATION_CHANNEL_DOWNLOAD_ID = 1003
        const val NOTIFICATION_ID_DOWNLOAD = 2001
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

            val downloadChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_DOWNLOAD,
                "Model Downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows progress of on-device model downloads"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(taskChannel)
            notificationManager.createNotificationChannel(runningChannel)
            notificationManager.createNotificationChannel(downloadChannel)
        }
    }

    fun createTaskNotification(title: String, text: String, id: Int): androidx.work.ForegroundInfo {
        return updateRunningTaskNotification(title, text, id)
    }

    fun updateRunningTaskNotification(title: String, text: String, id: Int): androidx.work.ForegroundInfo {
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

    fun getRunningTaskNotificationId(taskId: String): Int {
        return NOTIFICATION_CHANNEL_RUNNING_ID + (taskId.hashCode().toUInt().toInt() % 10000)
    }

    fun updateRunningTaskNotification(context: Context, taskId: String, title: String, text: String) {
        val notificationId = getRunningTaskNotificationId(taskId)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_RUNNING)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        val notificationManager = context
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    fun cancelRunningTaskNotification(taskId: String) {
        val notificationId = getRunningTaskNotificationId(taskId)
        val notificationManager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId)
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

    fun showDownloadNotification(modelName: String) {
        Log.d(TAG, "Showing download notification for model: $modelName")

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Downloading model: $modelName")
            .setContentText("0% complete")
            .setProgress(100, 0, false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        val notificationManager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_DOWNLOAD, notification)
    }

    fun updateDownloadNotification(progress: Float, modelName: String) {
        Log.d(TAG, "Updating download notification: $progress%")

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val progressText = if (progress < 100f) {
            "${progress.toInt()}% complete"
        } else {
            "Download complete"
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Downloading model: $modelName")
            .setContentText(progressText)
            .setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setOngoing(progress < 100f)
            .setOnlyAlertOnce(true)
            .build()

        val notificationManager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_DOWNLOAD, notification)
    }

    fun cancelDownloadNotification() {
        Log.d(TAG, "Cancelling download notification")

        val notificationManager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID_DOWNLOAD)
    }
}
