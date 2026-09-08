package com.aiassistant

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.aiassistant.data.worker.WorkerFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AIAssistantApp : Application() {

    @Inject
    lateinit var workerFactory: WorkerFactory

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
