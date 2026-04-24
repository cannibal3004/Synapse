package com.aiassistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aiassistant.MainActivity
import com.aiassistant.R
import com.aiassistant.domain.llm.OnDeviceLlmEngine
import com.aiassistant.domain.model.ChatMessage
import com.aiassistant.domain.model.ChatMessageDto
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "LlmService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "llm_inference"
private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L

private const val MSG_INITIALIZE = 1
private const val MSG_CHAT = 2
private const val MSG_SHUTDOWN = 3
private const val MSG_GET_STATE = 4
private const val MSG_NEEDS_REINIT = 5
private const val MSG_PING = 6
private const val MSG_RESET_CONVERSATION = 7

private const val EXTRA_SYSTEM_PROMPT = "systemPrompt"
private const val EXTRA_TEMPERATURE = "temperature"
private const val EXTRA_TOP_K = "topK"
private const val EXTRA_TOP_P = "topP"
private const val EXTRA_USE_TOOLS = "useTools"
private const val EXTRA_MODEL_PATH = "modelPath"
private const val EXTRA_MESSAGES = "messages"
private const val EXTRA_MESSAGES_JSON = "messagesJson"

private val gson = Gson()
private const val EXTRA_CALLBACK = "callback"

private const val CB_INIT_DONE = 100
private const val CB_CHUNK = 101
private const val CB_DONE = 102
private const val CB_ERROR = 103
private const val CB_STATE = 104
private const val CB_NEEDS_REINIT = 105

class LlmService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private lateinit var engine: OnDeviceLlmEngine
    private var isForeground = false

    private val idleHandler by lazy {
        Handler(Looper.getMainLooper())
    }
    private val idleRunnable = Runnable {
        Log.d(TAG, "Idle timeout reached, shutting down")
        engine.shutdown()
        stopSelf()
    }

    private val serviceHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_INITIALIZE -> handleInitialize(msg)
                MSG_CHAT -> handleChat(msg)
                MSG_SHUTDOWN -> handleShutdown()
                MSG_GET_STATE -> handleGetState(msg)
                MSG_NEEDS_REINIT -> handleNeedsReinit(msg)
                MSG_PING -> resetIdleTimer()
                MSG_RESET_CONVERSATION -> handleResetConversation()
                else -> Log.w(TAG, "handleMessage: unknown msg.what=${msg.what}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = OnDeviceLlmEngine(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = Messenger(serviceHandler).binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        resetIdleTimer()
        return START_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Last client unbound")
        resetIdleTimer()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        idleHandler.removeCallbacks(idleRunnable)
        serviceHandler.removeCallbacksAndMessages(null)

        engine.shutdown()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
    }

    private fun handleInitialize(msg: Message) {
        val data = msg.data
        val replyTo = msg.replyTo
        val modelPath = data.getString(EXTRA_MODEL_PATH) ?: run {
            sendError(data, "modelPath is required", replyTo)
            return
        }
        val systemPrompt = data.getString(EXTRA_SYSTEM_PROMPT)
        val temperature = if (data.containsKey(EXTRA_TEMPERATURE)) data.getFloat(EXTRA_TEMPERATURE) else null
        val topK = if (data.containsKey(EXTRA_TOP_K)) data.getInt(EXTRA_TOP_K) else null
        val topP = if (data.containsKey(EXTRA_TOP_P)) data.getFloat(EXTRA_TOP_P) else null
        val useTools = data.getBoolean(EXTRA_USE_TOOLS, true)

        serviceScope.launch {
            val result = engine.initializeModel(
                modelPath = modelPath,
                systemPrompt = systemPrompt,
                temperature = temperature,
                topK = topK,
                topP = topP,
                useTools = useTools
            )
            result.fold(
                onSuccess = {
                    replyTo?.send(Message.obtain(null, CB_INIT_DONE))
                    Log.d(TAG, "Model initialized: $modelPath")
                },
                onFailure = { e ->
                    val m = Message.obtain(null, CB_ERROR)
                    val b = Bundle()
                    b.putString("error", e.message ?: "Initialization failed")
                    m.obj = b
                    replyTo?.send(m)
                    Log.e(TAG, "Init failed", e)
                }
            )
        }
    }

    private fun handleChat(msg: Message) {
        val data = msg.data
        val json = data.getString(EXTRA_MESSAGES_JSON) ?: run {
            val m = Message.obtain(null, CB_ERROR)
            val b = Bundle()
            b.putString("error", "messages is required")
            m.obj = b
            msg.replyTo?.send(m)
            return
        }
        val dtos = gson.fromJson(json, object : TypeToken<List<ChatMessageDto>>() {}.type) as List<ChatMessageDto>
        val messages = dtos.map { dto -> dto.toChatMessage() }
        val callback = msg.replyTo
        Log.d(TAG, "handleChat: received ${messages.size} messages, replyTo=${callback != null}")

        showForegroundNotification()

        serviceScope.launch {
            try {
                engine.chatStream(messages).collect { event ->
                    val replyMsg = when (event) {
                        is OnDeviceLlmEngine.ChatEvent.Chunk -> {
                            val m = Message.obtain(null, CB_CHUNK)
                            val b = Bundle()
                            b.putString("text", event.text)
                            m.obj = b
                            m
                        }
                        is OnDeviceLlmEngine.ChatEvent.Done -> {
                            val m = Message.obtain(null, CB_DONE)
                            val b = Bundle()
                            b.putString("response", event.response)
                            m.obj = b
                            m
                        }
                        is OnDeviceLlmEngine.ChatEvent.Error -> {
                            val m = Message.obtain(null, CB_ERROR)
                            val b = Bundle()
                            b.putString("error", event.error)
                            m.obj = b
                            m
                        }
                    }
                    try {
                        if (callback == null) {
                            Log.e(TAG, "handleChat: replyTo is NULL for event ${event::class.simpleName}")
                        } else {
                            callback.send(replyMsg)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "handleChat: failed to send ${event::class.simpleName}", e)
                    }
                }
                Log.d(TAG, "handleChat: flow completed")
            } catch (e: Exception) {
                Log.e(TAG, "handleChat: flow collection error", e)
                val em = Message.obtain(null, CB_ERROR)
                val eb = Bundle()
                eb.putString("error", e.message ?: "Chat failed")
                em.obj = eb
                msg.replyTo?.send(em)
            } finally {
                hideForegroundNotification()
            }
        }
    }

    private fun handleShutdown() {
        Log.d(TAG, "Shutdown requested")
        engine.shutdown()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun handleResetConversation() {
        Log.d(TAG, "Resetting conversation")
        engine.resetConversation()
    }

    private fun handleGetState(msg: Message) {
        val state = engine.getState()
        val replyTo = msg.replyTo
        val json = gson.toJson(mapOf(
            "isReady" to state.isReady,
            "isLoading" to state.isLoading,
            "modelPath" to state.modelPath,
            "error" to state.error
        ))
        replyTo?.send(Message.obtain(null, CB_STATE).apply { obj = json })
    }

    private fun handleNeedsReinit(msg: Message) {
        val data = msg.data
        val replyTo = msg.replyTo
        val modelPath = data.getString(EXTRA_MODEL_PATH) ?: run {
            sendError(data, "modelPath is required", replyTo)
            return
        }
        val systemPrompt = data.getString(EXTRA_SYSTEM_PROMPT)
        val temperature = if (data.containsKey(EXTRA_TEMPERATURE)) data.getFloat(EXTRA_TEMPERATURE) else null
        val topK = if (data.containsKey(EXTRA_TOP_K)) data.getInt(EXTRA_TOP_K) else null
        val topP = if (data.containsKey(EXTRA_TOP_P)) data.getFloat(EXTRA_TOP_P) else null
        val useTools = data.getBoolean(EXTRA_USE_TOOLS, true)

        try {
            val needsReinit = kotlinx.coroutines.runBlocking {
                engine.needsReinitialize(
                    modelPath = modelPath,
                    systemPrompt = systemPrompt,
                    temperature = temperature,
                    topK = topK,
                    topP = topP,
                    useTools = useTools
                )
            }
            Log.d(TAG, "handleNeedsReinit: $needsReinit")
            replyTo?.send(Message.obtain(null, CB_NEEDS_REINIT).apply {
                arg1 = if (needsReinit) 1 else 0
            })
        } catch (e: Exception) {
            Log.e(TAG, "handleNeedsReinit: failed", e)
            val m = Message.obtain(null, CB_ERROR)
            val b = Bundle()
            b.putString("error", e.message ?: "needsReinit failed")
            m.obj = b
            replyTo?.send(m)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LLM Inference",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when the on-device AI model is running"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun showForegroundNotification() {
        if (isForeground) return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Synapse AI")
            .setContentText("On-device model running...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        isForeground = true
        Log.d(TAG, "Foreground notification shown")
    }

    private fun hideForegroundNotification() {
        if (!isForeground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        isForeground = false
        Log.d(TAG, "Foreground notification hidden")
    }

    private fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable)
        idleHandler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
    }

    private fun sendError(data: Bundle, error: String, replyTo: Messenger? = null) {
        val m = Message.obtain(null, CB_ERROR)
        val b = Bundle()
        b.putString("error", error)
        m.obj = b
        replyTo?.send(m)
    }
}
