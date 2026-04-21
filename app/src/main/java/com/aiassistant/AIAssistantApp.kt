package com.aiassistant

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AIAssistantApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("AIAssistantApp", "Application onCreate")
    }
}
