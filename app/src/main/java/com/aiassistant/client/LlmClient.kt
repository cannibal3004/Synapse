package com.aiassistant.client

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.aiassistant.domain.llm.OnDeviceLlmEngine
import com.aiassistant.domain.model.ChatMessage
import com.aiassistant.domain.model.ChatMessageDto
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.aiassistant.service.LlmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LlmClient"

private const val MSG_INITIALIZE = 1
private const val MSG_CHAT = 2
private const val MSG_SHUTDOWN = 3
private const val MSG_GET_STATE = 4
private const val MSG_NEEDS_REINIT = 5
private const val MSG_PING = 6
private const val MSG_RESET_CONVERSATION = 7

private const val CB_INIT_DONE = 100
private const val CB_CHUNK = 101
private const val CB_DONE = 102
private const val CB_ERROR = 103
private const val CB_STATE = 104
private const val CB_NEEDS_REINIT = 105

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

@Singleton
class LlmClient @Inject constructor(
    private val context: Context
) : AutoCloseable {

    private var serviceMessenger: Messenger? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, binder: IBinder) {
            serviceMessenger = Messenger(binder)
            isBound = true
            Log.d(TAG, "Bound to LlmService")
        }

        override fun onServiceDisconnected(name: android.content.ComponentName) {
            serviceMessenger = null
            isBound = false
            Log.d(TAG, "Disconnected from LlmService")
        }
    }

    fun bind() {
        if (isBound) return
        val intent = Intent(context, LlmService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        context.startService(intent)
         Log.d(TAG, "Binding to LlmService")
    }

    override fun close() {
        if (isBound) {
            context.unbindService(connection)
            isBound = false
            Log.d(TAG, "Unbound from LlmService")
        }
    }

    suspend fun initializeModel(
        modelPath: String,
        systemPrompt: String? = null,
        temperature: Float? = null,
        topK: Int? = null,
        topP: Float? = null,
        useTools: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        ensureBound()
        val latch = CountDownLatch(1)
        var result: Result<Unit> = Result.failure(Exception("Timeout"))

        val handler = Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                CB_INIT_DONE -> { result = Result.success(Unit); latch.countDown() }
                CB_ERROR -> {
                    val b = msg.obj as? Bundle
                    result = Result.failure(Exception(b?.getString("error") ?: "Unknown error"))
                    latch.countDown()
                }
            }
            true
        }

        val sm = serviceMessenger
        if (sm != null) {
            val msg = Message.obtain(null, MSG_INITIALIZE).apply {
                replyTo = Messenger(handler)
                data = Bundle().apply {
                    putString(EXTRA_MODEL_PATH, modelPath)
                    systemPrompt?.let { putString(EXTRA_SYSTEM_PROMPT, it) }
                    temperature?.let { putFloat(EXTRA_TEMPERATURE, it) }
                    topK?.let { putInt(EXTRA_TOP_K, it) }
                    topP?.let { putFloat(EXTRA_TOP_P, it) }
                    putBoolean(EXTRA_USE_TOOLS, useTools)
                }
            }
            sm.send(msg)
            Log.d(TAG, "initializeModel: sent")
        }

        if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
            Log.w(TAG, "initializeModel: latch timed out")
        }
        handler.removeCallbacksAndMessages(null)
        result
    }

    suspend fun needsReinitialize(
        modelPath: String,
        systemPrompt: String? = null,
        temperature: Float? = null,
        topK: Int? = null,
        topP: Float? = null,
        useTools: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        ensureBound()
        val latch = CountDownLatch(1)
        var result = true

        val handler = Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                CB_NEEDS_REINIT -> { result = msg.arg1 == 1; latch.countDown() }
                CB_ERROR -> { latch.countDown() }
            }
            true
        }

        val sm = serviceMessenger
        if (sm != null) {
            val msg = Message.obtain(null, MSG_NEEDS_REINIT).apply {
                replyTo = Messenger(handler)
                data = Bundle().apply {
                    putString(EXTRA_MODEL_PATH, modelPath)
                    systemPrompt?.let { putString(EXTRA_SYSTEM_PROMPT, it) }
                    temperature?.let { putFloat(EXTRA_TEMPERATURE, it) }
                    topK?.let { putInt(EXTRA_TOP_K, it) }
                    topP?.let { putFloat(EXTRA_TOP_P, it) }
                    putBoolean(EXTRA_USE_TOOLS, useTools)
                }
            }
            sm.send(msg)
            Log.d(TAG, "needsReinitialize: sent")
        }

        if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            Log.w(TAG, "needsReinitialize: latch timed out")
        }
        handler.removeCallbacksAndMessages(null)
        result
    }

    fun chatStream(messages: List<ChatMessage>): Flow<OnDeviceLlmEngine.ChatEvent> = callbackFlow {
        bind()

        var timeout = 5000
        while (!isBound && timeout > 0) {
            Thread.sleep(50)
            timeout -= 50
        }
        if (!isBound) {
            trySend(OnDeviceLlmEngine.ChatEvent.Error("Service bind timeout"))
            close()
            return@callbackFlow
        }
        Log.d(TAG, "chatStream: service bound, sending ${messages.size} messages")

        val handler = Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                CB_CHUNK -> {
                    val b = msg.obj as? Bundle
                    trySend(OnDeviceLlmEngine.ChatEvent.Chunk(b?.getString("text") ?: ""))
                }
                CB_DONE -> {
                    val b = msg.obj as? Bundle
                    trySend(OnDeviceLlmEngine.ChatEvent.Done(b?.getString("response") ?: ""))
                    close()
                }
                CB_ERROR -> {
                    val b = msg.obj as? Bundle
                    trySend(OnDeviceLlmEngine.ChatEvent.Error(b?.getString("error") ?: "Unknown error"))
                    close()
                }
            }
            true
        }

        val sm = serviceMessenger
        if (sm == null) {
            trySend(OnDeviceLlmEngine.ChatEvent.Error("Service not connected"))
            close()
            return@callbackFlow
        }

        val msg = Message.obtain(null, MSG_CHAT).apply {
            replyTo = Messenger(handler)
            data = Bundle().apply {
                putString(EXTRA_MESSAGES_JSON, gson.toJson(messages.map { ChatMessageDto.fromChatMessage(it) }))
            }
        }
        try {
            sm.send(msg)
            Log.d(TAG, "chatStream: sent")
        } catch (e: Exception) {
            Log.e(TAG, "chatStream: failed to send", e)
            trySend(OnDeviceLlmEngine.ChatEvent.Error("Send failed: ${e.message}"))
            close()
        }

        awaitClose {
            handler.removeCallbacksAndMessages(null)
        }
    }

    suspend fun getState(): OnDeviceLlmEngine.EngineState = withContext(Dispatchers.IO) {
        ensureBound()
        val latch = CountDownLatch(1)
        var result = OnDeviceLlmEngine.EngineState()

        val handler = Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == CB_STATE) {
                val json = msg.obj as? String
                if (json != null) {
                    val map = gson.fromJson(json, object : TypeToken<Map<String, Any>>() {}.type) as? Map<String, Any>
                    if (map != null) {
                        result = OnDeviceLlmEngine.EngineState(
                            isReady = map["isReady"] as? Boolean ?: false,
                            isLoading = map["isLoading"] as? Boolean ?: false,
                            modelPath = map["modelPath"] as? String,
                            error = map["error"] as? String
                        )
                    }
                }
                latch.countDown()
            }
            true
        }

        val sm = serviceMessenger
        if (sm != null) {
            val msg = Message.obtain(null, MSG_GET_STATE).apply {
                replyTo = Messenger(handler)
            }
            sm.send(msg)
            Log.d(TAG, "getState: sent")
        }

        if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            Log.w(TAG, "getState: latch timed out")
        }
        handler.removeCallbacksAndMessages(null)
        result
    }

    suspend fun shutdown() = withContext(Dispatchers.IO) {
        sendRequest(MSG_SHUTDOWN, Bundle())
    }

    fun resetConversation() {
        sendRequest(MSG_RESET_CONVERSATION, Bundle())
    }

    fun ping() {
        sendRequest(MSG_PING, Bundle())
    }

    private fun sendRequest(msgWhat: Int, extras: Bundle) {
        val sm = serviceMessenger
        if (sm == null) {
            Log.w(TAG, "sendRequest: serviceMessenger is null for msgWhat=$msgWhat")
            return
        }
        Message.obtain(null, msgWhat).apply {
            data = extras
            try {
                sm.send(this)
                Log.d(TAG, "sendRequest: $msgWhat")
            } catch (e: Exception) {
                Log.e(TAG, "sendRequest: failed $msgWhat", e)
            }
        }
    }

    private fun ensureBound() {
        if (!isBound) {
            bind()
            var timeout = 5000
            while (!isBound && timeout > 0) {
                Thread.sleep(50)
                timeout -= 50
            }
        }
    }
}
