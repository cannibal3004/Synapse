package com.aiassistant.data.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.aiassistant.data.notification.NotificationHelper
import com.aiassistant.data.repository.SettingsDataRepository
import com.aiassistant.domain.repository.TaskRepository
import com.aiassistant.domain.usecase.TaskExecutor
import javax.inject.Inject

class WorkerFactory @Inject constructor(
    private val taskExecutor: TaskExecutor,
    private val taskRepository: TaskRepository,
    private val settingsDataRepository: SettingsDataRepository,
    private val notificationHelper: NotificationHelper
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            TaskWorker::class.java.name -> TaskWorker(
                appContext,
                workerParameters,
                taskExecutor,
                taskRepository,
                settingsDataRepository,
                notificationHelper
            )
            else -> null
        }
    }
}
