package com.aiassistant

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.aiassistant.data.scheduler.TaskScheduler
import com.aiassistant.data.worker.WorkerFactory
import com.aiassistant.domain.repository.TaskRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AIAssistantApp : Application() {

    @Inject
    lateinit var workerFactory: WorkerFactory

    @Inject
    lateinit var taskRepository: TaskRepository

    @Inject
    lateinit var taskScheduler: TaskScheduler

    override fun onCreate() {
        super.onCreate()

        // onCreate runs once per process, and the on-device engine lives in :llm. WorkManager
        // belongs only to the main process; initialising it in :llm would stand up a second
        // scheduler against the same database for no reason.
        if (!isMainProcess()) {
            Log.d("AIAssistantApp", "Application onCreate in ${currentProcessName()}, skipping WorkManager")
            return
        }

        Log.d("AIAssistantApp", "Application onCreate")

        WorkManager.initialize(
            this,
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(Log.DEBUG)
                .build()
        )

        // A scheduled task with no work behind it looks perfectly healthy in the list and never
        // runs. Checking at launch turns that from permanent into something that fixes itself
        // the next time the app is opened.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { taskScheduler.ensureScheduled(taskRepository.getAllTasks().first()) }
                .onFailure { Log.e("AIAssistantApp", "Could not check scheduled tasks", it) }
        }
    }

    private fun isMainProcess(): Boolean = currentProcessName() == packageName

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName()
        }
        val pid = Process.myPid()
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return manager?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }
}
