package com.aiassistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.aiassistant.MainActivity
import com.aiassistant.R
import com.aiassistant.domain.llm.LlmBackend
import com.aiassistant.domain.llm.OnDeviceLlmEngine
import com.aiassistant.domain.model.ChatMessageDto
import com.aiassistant.domain.usecase.MemorySearchUseCase
import com.aiassistant.service.LlmIpc.CB_CHUNK
import com.aiassistant.service.LlmIpc.CB_DONE
import com.aiassistant.service.LlmIpc.CB_ERROR
import com.aiassistant.service.LlmIpc.CB_INIT_DONE
import com.aiassistant.service.LlmIpc.CB_NEEDS_REINIT
import com.aiassistant.service.LlmIpc.CB_STATE
import com.aiassistant.service.LlmIpc.CB_THINKING
import com.aiassistant.service.LlmIpc.EXTRA_BACKEND
import com.aiassistant.service.LlmIpc.EXTRA_CONTEXT_TOKENS
import com.aiassistant.service.LlmIpc.EXTRA_ENABLE_THINKING
import com.aiassistant.service.LlmIpc.EXTRA_MAX_OUTPUT_TOKENS
import com.aiassistant.service.LlmIpc.EXTRA_MAX_TOOL_ROUNDS
import com.aiassistant.service.LlmIpc.EXTRA_MESSAGES_JSON
import com.aiassistant.service.LlmIpc.EXTRA_MODEL_PATH
import com.aiassistant.service.LlmIpc.EXTRA_SYSTEM_PROMPT
import com.aiassistant.service.LlmIpc.EXTRA_TEMPERATURE
import com.aiassistant.service.LlmIpc.EXTRA_THINKING_BUDGET
import com.aiassistant.service.LlmIpc.EXTRA_TOP_K
import com.aiassistant.service.LlmIpc.EXTRA_TOP_P
import com.aiassistant.service.LlmIpc.EXTRA_USE_TOOLS
import com.aiassistant.service.LlmIpc.KEY_ERROR
import com.aiassistant.service.LlmIpc.KEY_RESPONSE
import com.aiassistant.service.LlmIpc.KEY_STATE
import com.aiassistant.service.LlmIpc.KEY_STATS
import com.aiassistant.service.LlmIpc.KEY_TEXT
import com.aiassistant.service.LlmIpc.MSG_CANCEL
import com.aiassistant.service.LlmIpc.MSG_CHAT
import com.aiassistant.service.LlmIpc.MSG_GET_STATE
import com.aiassistant.service.LlmIpc.MSG_INITIALIZE
import com.aiassistant.service.LlmIpc.MSG_NEEDS_REINIT
import com.aiassistant.service.LlmIpc.MSG_PING
import com.aiassistant.service.LlmIpc.MSG_RESET_CONVERSATION
import com.aiassistant.service.LlmIpc.MSG_SHUTDOWN
import com.google.gson.Gson
import com.aiassistant.data.repository.DEFAULT_MAX_TOOL_ROUNDS
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

private const val TAG = "LlmService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "llm_inference"
private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L

@AndroidEntryPoint
class LlmService : Service() {

    /**
     * Memory lives in this process for the on-device path, alongside the engine that uses it.
     * Loading the embedding model here rather than in the app process keeps a single copy of it
     * in memory, next to the LLM that is already isolated here.
     */
    @Inject
    lateinit var memory: MemorySearchUseCase

    private val gson = Gson()
    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private lateinit var engine: OnDeviceLlmEngine
    private var isForeground = false

    /**
     * Model loads and inference runs that outlive the idle window must hold it off. Without this
     * the timer fired mid-generation and shut the engine down, and the turn returned empty.
     */
    private val activeOperations = AtomicInteger(0)

    private val idleHandler by lazy { Handler(Looper.getMainLooper()) }
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
                MSG_CANCEL -> handleCancel()
                else -> Log.w(TAG, "handleMessage: unknown msg.what=${msg.what}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = OnDeviceLlmEngine(applicationContext, memory)
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
        hideForegroundNotification()
    }

    private fun handleInitialize(msg: Message) {
        val params = msg.data.toEngineParams() ?: run {
            sendError("modelPath is required", msg.replyTo)
            return
        }
        val replyTo = msg.replyTo

        beginOperation()
        serviceScope.launch {
            try {
                engine.initializeModel(
                    modelPath = params.modelPath,
                    systemPrompt = params.systemPrompt,
                    temperature = params.temperature,
                    topK = params.topK,
                    topP = params.topP,
                    useTools = params.useTools,
                    enableThinking = params.enableThinking,
                    thinkingTokenBudget = params.thinkingTokenBudget,
                    maxOutputTokens = params.maxOutputTokens,
                    backend = params.backend,
                    contextTokens = params.contextTokens
                ).fold(
                    onSuccess = {
                        replyTo?.trySend(Message.obtain(null, CB_INIT_DONE))
                        Log.d(TAG, "Model initialized: ${params.modelPath}")
                    },
                    onFailure = { e ->
                        sendError(e.message ?: "Initialization failed", replyTo)
                        Log.e(TAG, "Init failed", e)
                    }
                )
            } finally {
                endOperation()
            }
        }
    }

    private fun handleChat(msg: Message) {
        val json = msg.data.getString(EXTRA_MESSAGES_JSON) ?: run {
            sendError("messages is required", msg.replyTo)
            return
        }
        val dtos: List<ChatMessageDto> =
            gson.fromJson(json, object : TypeToken<List<ChatMessageDto>>() {}.type)
        val messages = dtos.map { it.toChatMessage() }
        val callback = msg.replyTo
        Log.d(TAG, "handleChat: received ${messages.size} messages, replyTo=${callback != null}")

        showForegroundNotification()
        beginOperation()

        serviceScope.launch {
            try {
                val maxToolRounds = msg.data?.getInt(EXTRA_MAX_TOOL_ROUNDS)
                    ?.takeIf { it > 0 }
                    ?: DEFAULT_MAX_TOOL_ROUNDS
                engine.chatStream(messages, maxToolRounds).collect { event ->
                    val replyMsg = when (event) {
                        is OnDeviceLlmEngine.ChatEvent.Chunk ->
                            bundled(CB_CHUNK, KEY_TEXT, event.text)
                        is OnDeviceLlmEngine.ChatEvent.Thinking ->
                            bundled(CB_THINKING, KEY_TEXT, event.text)
                        is OnDeviceLlmEngine.ChatEvent.Done ->
                            Message.obtain(null, CB_DONE).apply {
                                obj = Bundle().apply {
                                    putString(KEY_RESPONSE, event.response)
                                    event.stats?.let { putString(KEY_STATS, gson.toJson(it)) }
                                }
                            }
                        is OnDeviceLlmEngine.ChatEvent.Error ->
                            bundled(CB_ERROR, KEY_ERROR, event.error)
                    }
                    if (callback == null) {
                        Log.e(TAG, "handleChat: replyTo is NULL for ${event::class.simpleName}")
                    } else {
                        callback.trySend(replyMsg)
                    }
                }
                Log.d(TAG, "handleChat: flow completed")
            } catch (e: Exception) {
                Log.e(TAG, "handleChat: flow collection error", e)
                sendError(e.message ?: "Chat failed", callback)
            } finally {
                hideForegroundNotification()
                endOperation()
            }
        }
    }

    /**
     * The system gives a timed-out foreground service only a few seconds to stand down before
     * throwing a fatal RemoteServiceException. specialUse carries no cumulative cap today, but
     * handling this is what keeps a future policy change from becoming a crash.
     *
     * API 34 signature; superseded by the two-argument overload on API 35+.
     */
    @Deprecated("Kept for API 34; API 35+ calls onTimeout(startId, fgsType).")
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "Foreground service timed out (startId=$startId), stopping")
        stopInferenceAndSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timed out (startId=$startId, type=$fgsType), stopping")
        stopInferenceAndSelf()
    }

    private fun stopInferenceAndSelf() {
        engine.cancel()
        engine.shutdown()
        hideForegroundNotification()
        stopSelf()
    }

    private fun handleShutdown() {
        Log.d(TAG, "Shutdown requested")
        engine.shutdown()
        hideForegroundNotification()
        stopSelf()
    }

    private fun handleResetConversation() {
        Log.d(TAG, "Resetting conversation")
        engine.resetConversation()
    }

    private fun handleCancel() {
        Log.d(TAG, "Cancelling in-flight inference")
        engine.cancel()
    }

    private fun handleGetState(msg: Message) {
        val state = engine.getState()
        // A bare String in Message.obj throws "Can't marshal non-Parcelable objects across
        // processes", which made every getState() time out and drop the capability report.
        msg.replyTo?.trySend(bundled(CB_STATE, KEY_STATE, gson.toJson(state)))
    }

    private fun handleNeedsReinit(msg: Message) {
        val params = msg.data.toEngineParams() ?: run {
            sendError("modelPath is required", msg.replyTo)
            return
        }
        val replyTo = msg.replyTo

        try {
            val needsReinit = engine.needsReinitialize(
                modelPath = params.modelPath,
                systemPrompt = params.systemPrompt,
                temperature = params.temperature,
                topK = params.topK,
                topP = params.topP,
                useTools = params.useTools,
                enableThinking = params.enableThinking,
                thinkingTokenBudget = params.thinkingTokenBudget,
                maxOutputTokens = params.maxOutputTokens,
                backend = params.backend,
                contextTokens = params.contextTokens
            )
            Log.d(TAG, "handleNeedsReinit: $needsReinit")
            replyTo?.trySend(
                Message.obtain(null, CB_NEEDS_REINIT).apply {
                    arg1 = if (needsReinit) 1 else 0
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "handleNeedsReinit: failed", e)
            sendError(e.message ?: "needsReinit failed", replyTo)
        }
    }

    private class EngineParams(
        val modelPath: String,
        val systemPrompt: String?,
        val temperature: Float?,
        val topK: Int?,
        val topP: Float?,
        val useTools: Boolean,
        val enableThinking: Boolean,
        val thinkingTokenBudget: Int?,
        val maxOutputTokens: Int?,
        val backend: LlmBackend,
        val contextTokens: Int?
    )

    private fun Bundle.toEngineParams(): EngineParams? {
        val modelPath = getString(EXTRA_MODEL_PATH) ?: return null
        return EngineParams(
            modelPath = modelPath,
            systemPrompt = getString(EXTRA_SYSTEM_PROMPT),
            temperature = if (containsKey(EXTRA_TEMPERATURE)) getFloat(EXTRA_TEMPERATURE) else null,
            topK = if (containsKey(EXTRA_TOP_K)) getInt(EXTRA_TOP_K) else null,
            topP = if (containsKey(EXTRA_TOP_P)) getFloat(EXTRA_TOP_P) else null,
            useTools = getBoolean(EXTRA_USE_TOOLS, true),
            enableThinking = getBoolean(EXTRA_ENABLE_THINKING, false),
            thinkingTokenBudget =
                if (containsKey(EXTRA_THINKING_BUDGET)) getInt(EXTRA_THINKING_BUDGET) else null,
            maxOutputTokens =
                if (containsKey(EXTRA_MAX_OUTPUT_TOKENS)) getInt(EXTRA_MAX_OUTPUT_TOKENS) else null,
            backend = LlmBackend.fromName(getString(EXTRA_BACKEND)),
            contextTokens =
                if (containsKey(EXTRA_CONTEXT_TOKENS)) getInt(EXTRA_CONTEXT_TOKENS) else null
        )
    }

    private fun bundled(what: Int, key: String, value: String): Message =
        Message.obtain(null, what).apply { obj = Bundle().apply { putString(key, value) } }

    private fun sendError(error: String, replyTo: Messenger?) {
        replyTo?.trySend(bundled(CB_ERROR, KEY_ERROR, error))
    }

    /**
     * The client process can die mid-stream; a dead [Messenger] must not take the service with it.
     */
    private fun Messenger.trySend(message: Message) {
        try {
            send(message)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deliver message ${message.what} to client", e)
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

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        isForeground = true
        Log.d(TAG, "Foreground notification shown")
    }

    private fun hideForegroundNotification() {
        if (!isForeground) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        isForeground = false
        Log.d(TAG, "Foreground notification hidden")
    }

    private fun beginOperation() {
        activeOperations.incrementAndGet()
        idleHandler.removeCallbacks(idleRunnable)
    }

    private fun endOperation() {
        if (activeOperations.decrementAndGet() <= 0) {
            activeOperations.set(0)
            resetIdleTimer()
        }
    }

    private fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable)
        if (activeOperations.get() > 0) return
        idleHandler.postDelayed(idleRunnable, IDLE_TIMEOUT_MS)
    }
}
