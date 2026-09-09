package com.aiassistant.client

import android.content.ComponentName
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
import com.aiassistant.domain.llm.GenerationStats
import com.aiassistant.domain.llm.LlmBackend
import com.aiassistant.domain.llm.OnDeviceLlmEngine
import com.aiassistant.domain.model.ChatMessage
import com.aiassistant.domain.model.ChatMessageDto
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
import com.aiassistant.service.LlmService
import com.google.gson.Gson
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TAG = "LlmClient"
private const val BIND_TIMEOUT_MS = 5_000L
private const val INIT_TIMEOUT_MS = 120_000L
private const val QUERY_TIMEOUT_MS = 10_000L
private const val SERVICE_DIED_MESSAGE =
    "The on-device model stopped unexpectedly - the system most likely reclaimed its memory. " +
        "Try a smaller model, or close other apps and retry."

@Singleton
class LlmClient @Inject constructor(
    private val context: Context
) : AutoCloseable {

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()
    private var serviceMessenger: Messenger? = null
    private var pendingConnection: CompletableDeferred<Messenger>? = null
    private var isBound = false

    /**
     * Callbacks to run if the service process dies.
     *
     * The engine holds a multi-GB model in a separate process, which makes it a prime target for
     * the low-memory killer mid-inference. Without this the reply flow simply never completes and
     * the UI spins forever.
     */
    private val deathHandlers = mutableSetOf<() -> Unit>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val messenger = Messenger(binder)
            val waiting = synchronized(lock) {
                serviceMessenger = messenger
                isBound = true
                pendingConnection.also { pendingConnection = null }
            }
            waiting?.complete(messenger)
            Log.d(TAG, "Bound to LlmService")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            val handlers = synchronized(lock) {
                serviceMessenger = null
                pendingConnection = null
                deathHandlers.toList().also { deathHandlers.clear() }
            }
            Log.w(TAG, "LlmService process died; failing ${handlers.size} in-flight request(s)")
            handlers.forEach { runCatching { it() } }
        }
    }

    fun bind() {
        synchronized(lock) {
            if (serviceMessenger != null) return
            startBindingLocked()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!isBound) return
            context.unbindService(connection)
            isBound = false
            serviceMessenger = null
            pendingConnection = null
        }
        scope.cancel()
        Log.d(TAG, "Unbound from LlmService")
    }

    suspend fun initializeModel(
        modelPath: String,
        systemPrompt: String? = null,
        temperature: Float? = null,
        topK: Int? = null,
        topP: Float? = null,
        useTools: Boolean = true,
        enableThinking: Boolean = false,
        thinkingTokenBudget: Int? = null,
        maxOutputTokens: Int? = null,
        backend: LlmBackend = LlmBackend.CPU,
        contextTokens: Int? = null
    ): Result<Unit> {
        val data = engineParamsBundle(
            modelPath, systemPrompt, temperature, topK, topP,
            useTools, enableThinking, thinkingTokenBudget, maxOutputTokens, backend, contextTokens
        )
        return sendAndAwait(
            MSG_INITIALIZE,
            data,
            INIT_TIMEOUT_MS,
            // Loading a large bundle is exactly when the process is most likely to be OOM-killed,
            // and reporting that as a timeout sends anyone reading it after the wrong problem.
            onDied = { Result.failure(IllegalStateException(SERVICE_DIED_MESSAGE)) }
        ) { msg ->
            when (msg.what) {
                CB_INIT_DONE -> Result.success(Unit)
                CB_ERROR -> Result.failure(Exception(msg.errorText()))
                else -> null
            }
        } ?: Result.failure(IllegalStateException("Model initialization timed out"))
    }

    suspend fun needsReinitialize(
        modelPath: String,
        systemPrompt: String? = null,
        temperature: Float? = null,
        topK: Int? = null,
        topP: Float? = null,
        useTools: Boolean = true,
        enableThinking: Boolean = false,
        thinkingTokenBudget: Int? = null,
        maxOutputTokens: Int? = null,
        backend: LlmBackend = LlmBackend.CPU,
        contextTokens: Int? = null
    ): Boolean {
        val data = engineParamsBundle(
            modelPath, systemPrompt, temperature, topK, topP,
            useTools, enableThinking, thinkingTokenBudget, maxOutputTokens, backend, contextTokens
        )
        // Defaulting to true means a dropped reply causes a redundant re-init rather than
        // inference against a stale conversation.
        return sendAndAwait(MSG_NEEDS_REINIT, data, QUERY_TIMEOUT_MS) { msg ->
            when (msg.what) {
                CB_NEEDS_REINIT -> msg.arg1 == 1
                CB_ERROR -> true
                else -> null
            }
        } ?: true
    }

    suspend fun getState(): OnDeviceLlmEngine.EngineState {
        return sendAndAwait(MSG_GET_STATE, Bundle(), QUERY_TIMEOUT_MS) { msg ->
            if (msg.what != CB_STATE) {
                null
            } else {
                runCatching {
                    gson.fromJson(
                        msg.text(KEY_STATE),
                        OnDeviceLlmEngine.EngineState::class.java
                    )
                }.getOrNull()
            }
        } ?: OnDeviceLlmEngine.EngineState()
    }

    fun chatStream(messages: List<ChatMessage>): Flow<OnDeviceLlmEngine.ChatEvent> = callbackFlow {
        val messenger = awaitService() ?: run {
            trySend(OnDeviceLlmEngine.ChatEvent.Error("Service bind timeout"))
            close()
            return@callbackFlow
        }
        Log.d(TAG, "chatStream: service bound, sending ${messages.size} messages")

        val onDeath = {
            trySend(OnDeviceLlmEngine.ChatEvent.Error(SERVICE_DIED_MESSAGE))
            close()
            Unit
        }
        addDeathHandler(onDeath)

        val handler = Handler(Looper.getMainLooper()) { msg ->
            when (msg.what) {
                CB_CHUNK -> trySend(OnDeviceLlmEngine.ChatEvent.Chunk(msg.text(KEY_TEXT)))
                CB_THINKING -> trySend(OnDeviceLlmEngine.ChatEvent.Thinking(msg.text(KEY_TEXT)))
                CB_DONE -> {
                    val stats = msg.text(KEY_STATS)
                        .takeIf { it.isNotEmpty() }
                        ?.let {
                            runCatching { gson.fromJson(it, GenerationStats::class.java) }
                                .getOrNull()
                        }
                    trySend(OnDeviceLlmEngine.ChatEvent.Done(msg.text(KEY_RESPONSE), stats))
                    close()
                }
                CB_ERROR -> {
                    trySend(OnDeviceLlmEngine.ChatEvent.Error(msg.errorText()))
                    close()
                }
            }
            true
        }

        val request = Message.obtain(null, MSG_CHAT).apply {
            replyTo = Messenger(handler)
            data = Bundle().apply {
                putString(
                    EXTRA_MESSAGES_JSON,
                    gson.toJson(messages.map { ChatMessageDto.fromChatMessage(it) })
                )
            }
        }

        try {
            messenger.send(request)
            Log.d(TAG, "chatStream: sent")
        } catch (e: Exception) {
            Log.e(TAG, "chatStream: failed to send", e)
            trySend(OnDeviceLlmEngine.ChatEvent.Error("Send failed: ${e.message}"))
            close()
        }

        awaitClose {
            removeDeathHandler(onDeath)
            handler.removeCallbacksAndMessages(null)
        }
    }

    suspend fun shutdown() {
        awaitService()?.trySend(Message.obtain(null, MSG_SHUTDOWN))
    }

    fun resetConversation() = fireAndForget(MSG_RESET_CONVERSATION)

    fun cancel() = fireAndForget(MSG_CANCEL)

    fun ping() = fireAndForget(MSG_PING)

    private fun addDeathHandler(handler: () -> Unit) {
        synchronized(lock) { deathHandlers += handler }
    }

    private fun removeDeathHandler(handler: () -> Unit) {
        synchronized(lock) { deathHandlers -= handler }
    }

    private fun fireAndForget(what: Int) {
        scope.launch {
            val messenger = awaitService()
            if (messenger == null) {
                Log.w(TAG, "fireAndForget: no service for msgWhat=$what")
                return@launch
            }
            messenger.trySend(Message.obtain(null, what))
        }
    }

    private suspend fun <T> sendAndAwait(
        what: Int,
        data: Bundle,
        timeoutMs: Long,
        onDied: () -> T? = { null },
        onReply: (Message) -> T?
    ): T? = withTimeoutOrNull(timeoutMs) {
        val messenger = awaitService() ?: return@withTimeoutOrNull null
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper()) { msg ->
                val value = runCatching { onReply(msg) }.getOrNull()
                if (value != null) continuation.resumeIfActive(value)
                true
            }
            val onDeath = {
                Log.w(TAG, "Service died while awaiting reply to $what")
                continuation.resumeIfActive(onDied())
            }
            addDeathHandler(onDeath)
            continuation.invokeOnCancellation {
                removeDeathHandler(onDeath)
                handler.removeCallbacksAndMessages(null)
            }

            val request = Message.obtain(null, what).apply {
                replyTo = Messenger(handler)
                this.data = data
            }
            try {
                messenger.send(request)
            } catch (e: Exception) {
                Log.e(TAG, "sendAndAwait: failed to send $what", e)
                continuation.resumeIfActive(null)
            }
        }
    }

    /**
     * Binds if needed and suspends until the service connects. Never blocks the calling thread,
     * which matters because [chatStream] is collected from the UI-facing dispatcher.
     */
    private suspend fun awaitService(): Messenger? {
        val deferred = synchronized(lock) {
            serviceMessenger?.let { return it }
            pendingConnection ?: startBindingLocked()
        }
        return withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
    }

    private fun startBindingLocked(): CompletableDeferred<Messenger> {
        val deferred = CompletableDeferred<Messenger>()
        pendingConnection = deferred
        val intent = Intent(context, LlmService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        context.startService(intent)
        Log.d(TAG, "Binding to LlmService")
        return deferred
    }

    private fun engineParamsBundle(
        modelPath: String,
        systemPrompt: String?,
        temperature: Float?,
        topK: Int?,
        topP: Float?,
        useTools: Boolean,
        enableThinking: Boolean,
        thinkingTokenBudget: Int?,
        maxOutputTokens: Int?,
        backend: LlmBackend,
        contextTokens: Int?
    ) = Bundle().apply {
        putString(EXTRA_MODEL_PATH, modelPath)
        systemPrompt?.let { putString(EXTRA_SYSTEM_PROMPT, it) }
        temperature?.let { putFloat(EXTRA_TEMPERATURE, it) }
        topK?.let { putInt(EXTRA_TOP_K, it) }
        topP?.let { putFloat(EXTRA_TOP_P, it) }
        putBoolean(EXTRA_USE_TOOLS, useTools)
        putBoolean(EXTRA_ENABLE_THINKING, enableThinking)
        thinkingTokenBudget?.let { putInt(EXTRA_THINKING_BUDGET, it) }
        maxOutputTokens?.let { putInt(EXTRA_MAX_OUTPUT_TOKENS, it) }
        putString(EXTRA_BACKEND, backend.name)
        contextTokens?.let { putInt(EXTRA_CONTEXT_TOKENS, it) }
    }

    private fun Message.text(key: String): String = (obj as? Bundle)?.getString(key) ?: ""

    private fun Message.errorText(): String =
        (obj as? Bundle)?.getString(KEY_ERROR) ?: "Unknown error"

    private fun Messenger.trySend(message: Message) {
        try {
            send(message)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send message ${message.what}", e)
        }
    }

    private fun <T> CancellableContinuation<T?>.resumeIfActive(value: T?) {
        if (isActive) resume(value)
    }
}
