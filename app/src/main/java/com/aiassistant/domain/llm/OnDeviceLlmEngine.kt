package com.aiassistant.domain.llm

import android.content.Context
import android.util.Log
import com.aiassistant.domain.model.ChatMessage
import com.aiassistant.domain.model.MessageRole
import com.aiassistant.domain.tool.OnDeviceToolExecutor
import com.aiassistant.domain.tool.formatMemoryContext
import com.aiassistant.domain.usecase.MemorySearchUseCase
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Channel
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.tool
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val TAG = "OnDeviceLlmEngine"

class OnDeviceLlmEngine(
    private val context: Context,
    private val memory: MemorySearchUseCase? = null
) {

    /**
     * What the loaded model actually supports, as reported by the runtime rather than assumed.
     * Populated by probing the model file before the engine is created.
     */
    data class ModelCapabilities(
        val supportsFunctionCalling: Boolean = true,
        val supportsThinking: Boolean = false,
        val supportsSpeculativeDecoding: Boolean = false,
        val supportsVision: Boolean = false,
        val supportsAudio: Boolean = false,
        val defaultTemperature: Float? = null,
        val defaultTopK: Int? = null,
        val defaultTopP: Float? = null
    )

    data class EngineState(
        val isReady: Boolean = false,
        val isLoading: Boolean = false,
        val modelPath: String? = null,
        val error: String? = null,
        val capabilities: ModelCapabilities? = null,
        val toolsRegistered: Boolean = false,
        val tokenCount: Int? = null
    )

    sealed interface ChatEvent {
        data class Chunk(val text: String) : ChatEvent
        data class Thinking(val text: String) : ChatEvent
        data class Error(val error: String) : ChatEvent
        data class Done(
            val response: String,
            val stats: GenerationStats? = null
        ) : ChatEvent
    }

    /** Thrown inside the collection block to stop a degenerate round; never escapes it. */
    private class DegenerateOutput : Exception()

    /**
     * Detects an answer that has collapsed into repeating a single string.
     *
     * Aimed at the int4/GPU decode failure: every sampled token is invalid, the runtime casts it
     * to token 0 -- `<|start of sentence|>` in Spark's tokenizer -- and the deltas then arrive
     * identical, at a perfectly normal rate, for the entire output allowance. Rate and token
     * count both look healthy, so repetition is the only honest signal.
     *
     * Two conditions must hold together, which is what keeps ordinary output safe: a long run of
     * identical deltas *and* enough characters in that run. A table rule or a stretch of newlines
     * clears the first easily and never comes close to the second.
     */
    private class RepetitionGuard {
        private var previous: String? = null
        private var run = 0

        fun isDegenerate(message: Message): Boolean {
            val text = message.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
            if (text.isEmpty()) return false
            if (text == previous) {
                run++
            } else {
                previous = text
                run = 1
            }
            return run >= MIN_RUN && run.toLong() * text.length >= MIN_CHARS
        }

        private companion object {
            /** Identical deltas in a row before an answer is called degenerate. */
            const val MIN_RUN = 32

            /** ...and this many characters of them, so short repeats are never flagged. */
            const val MIN_CHARS = 512
        }
    }

    private data class PendingToolCall(
        val name: String,
        val arguments: Map<String, Any?>,
        val fromText: Boolean
    )

    private data class ActiveConfig(
        val modelPath: String,
        val systemPrompt: String?,
        val temperature: Float?,
        val topK: Int?,
        val topP: Float?,
        val useTools: Boolean,
        val enableThinking: Boolean,
        val thinkingTokenBudget: Int?,
        val maxOutputTokens: Int?,
        val templateFingerprint: String?,
        val backend: LlmBackend,
        val contextTokens: Int
    )

    private val gson = Gson()

    /**
     * Held for the lifetime of the engine. Constructing this registers a broadcast receiver for
     * the Termux tool, so it must not be rebuilt per conversation or per tool-calling round.
     */
    private val toolExecutor: OnDeviceToolExecutor by lazy {
        OnDeviceToolExecutor(context, memory) { activeConversationId }
    }

    /** Provenance for facts stored mid-turn; taken from the message the engine was sent. */
    @Volatile
    private var activeConversationId: String = ""

    private val toolsByName: Map<String, OpenApiTool> by lazy {
        toolExecutor.getAllTools().mapNotNull { tool ->
            toolName(tool)?.let { name -> name to tool }
        }.toMap()
    }

    /**
     * Whether the runtime accepted our tool descriptions. This is NOT the same as the model being
     * able to call them: litert-community bundles strip the tool-calling section from their chat
     * template, so the descriptions are registered and then never rendered into the prompt.
     */
    @Volatile
    private var toolsRegistered: Boolean = false

    /**
     * Per-model KV capacity discovered at runtime; see [effectiveCapacity].
     *
     * Persisted because the `:llm` process is OOM-killed often enough that an in-memory value
     * would usually be re-learned the hard way, at the cost of another failed turn.
     */
    private val capacityPrefs by lazy {
        context.getSharedPreferences("llm_kv_capacity", Context.MODE_PRIVATE)
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var activeConfig: ActiveConfig? = null
    private var capabilities: ModelCapabilities? = null

    fun getState(): EngineState {
        val convAlive = conversation?.isAlive == true
        val config = activeConfig
        return EngineState(
            isReady = convAlive,
            isLoading = false,
            modelPath = config?.modelPath,
            error = if (conversation != null && !convAlive && config != null) "Conversation died" else null,
            capabilities = capabilities,
            toolsRegistered = toolsRegistered,
            tokenCount = if (convAlive) {
                runCatching { conversation?.getTokenCount() }.getOrNull()
            } else {
                null
            }
        )
    }

    fun getCapabilities(): ModelCapabilities? = capabilities

    fun needsReinitialize(
        modelPath: String,
        systemPrompt: String?,
        temperature: Float?,
        topK: Int?,
        topP: Float?,
        useTools: Boolean,
        enableThinking: Boolean = false,
        thinkingTokenBudget: Int? = null,
        maxOutputTokens: Int? = null,
        backend: LlmBackend = LlmBackend.CPU,
        contextTokens: Int? = null
    ): Boolean {
        val requested = ActiveConfig(
            modelPath = modelPath,
            systemPrompt = systemPrompt,
            temperature = temperature,
            topK = topK,
            topP = topP,
            useTools = useTools,
            enableThinking = enableThinking,
            thinkingTokenBudget = thinkingTokenBudget,
            maxOutputTokens = maxOutputTokens,
            templateFingerprint = templateFingerprint(modelPath),
            backend = resolveBackend(modelPath, backend),
            contextTokens = resolveContextTokens(modelPath, contextTokens)
        )
        return engine == null || conversation?.isAlive != true || activeConfig != requested
    }

    @OptIn(ExperimentalApi::class)
    suspend fun initializeModel(
        modelPath: String,
        systemPrompt: String?,
        temperature: Float?,
        topK: Int?,
        topP: Float?,
        useTools: Boolean = true,
        enableThinking: Boolean = false,
        thinkingTokenBudget: Int? = null,
        maxOutputTokens: Int? = null,
        backend: LlmBackend = LlmBackend.CPU,
        contextTokens: Int? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            closeEngine()

            val modelFile = File(modelPath)
            if (!modelFile.exists() || modelFile.length() == 0L) {
                return@withContext Result.failure(
                    IllegalStateException("Model file not found or empty: $modelPath")
                )
            }

            val caps = probeCapabilities(modelPath)
            capabilities = caps

            val config = ActiveConfig(
                modelPath = modelPath,
                systemPrompt = systemPrompt,
                temperature = temperature,
                topK = topK,
                topP = topP,
                useTools = useTools,
                enableThinking = enableThinking,
                thinkingTokenBudget = thinkingTokenBudget,
                maxOutputTokens = maxOutputTokens,
                templateFingerprint = templateFingerprint(modelPath),
                backend = resolveBackend(modelPath, backend),
                contextTokens = resolveContextTokens(modelPath, contextTokens)
            )
            Log.d(
                TAG,
                "Loading $modelPath backend=${config.backend} " +
                    "contextTokens=${config.contextTokens} capabilities=$caps"
            )

            applyEngineFlags(caps, modelPath)

            // This path is text-only: chatStream reads lastUserMessage.content and attachments
            // never reach the engine. Naming a vision or audio backend makes the runtime build
            // and repack those encoders regardless -- measured at ~320 MB of XNNPack cache for
            // gemma-4-E2B, in a process that is already the low-memory killer's first pick. So
            // they stay off unless a model's sidecar opts in, and even then only if the bundle
            // actually carries the encoder: naming one it lacks turns a skippable warning into a
            // hard failure ("TF_LITE_AUDIO_ENCODER_HW not found in the model").
            val useVision = caps.supportsVision && sidecarFlag(modelPath, "enableVision")
            val useAudio = caps.supportsAudio && sidecarFlag(modelPath, "enableAudio")
            Log.d(TAG, "Modalities: vision=$useVision audio=$useAudio")

            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = litertBackend(config.backend, modelPath),
                visionBackend = if (useVision) litertBackend(config.backend, modelPath) else null,
                // Audio stays on CPU even when the main backend is accelerated, per the docs'
                // multi-modal compliance note.
                audioBackend = if (useAudio) Backend.CPU() else null,
                maxNumTokens = config.contextTokens,
                cacheDir = context.cacheDir.path
            )

            val newEngine = Engine(engineConfig)
            newEngine.initialize()
            engine = newEngine

            conversation = createConversation(newEngine, config, caps)
            activeConfig = config

            Log.d(TAG, "Model initialized successfully: $modelPath")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize model", e)
            closeEngine()
            Result.failure(e)
        }
    }

    fun resetConversation() {
        val eng = engine ?: return
        val config = activeConfig ?: return
        Log.d(TAG, "Resetting conversation state")
        closeConversation()
        conversation = createConversation(eng, config, capabilities ?: ModelCapabilities())
    }

    fun chatStream(
        messages: List<ChatMessage>,
        maxToolRounds: Int = MAX_TOOL_ROUNDS
    ): Flow<ChatEvent> = channelFlow {
        val conv = conversation ?: run {
            send(ChatEvent.Error("Model not initialized"))
            return@channelFlow
        }

        val lastUserMessage = messages.lastOrNull { it.role == MessageRole.USER } ?: run {
            send(ChatEvent.Error("No user message found"))
            return@channelFlow
        }

        Log.d(TAG, "Starting chat stream for: ${lastUserMessage.content.take(50)}...")
        activeConversationId = lastUserMessage.conversationId

        try {
            val responseText = StringBuilder()
            var nextInput: Any = withMemoryContext(lastUserMessage.content)
            var round = 0
            val deadline = System.currentTimeMillis() + TOOL_LOOP_BUDGET_MS

            while (round < maxToolRounds) {
                val roundText = StringBuilder()
                // Characters of this round already streamed to the caller.
                var sent = 0
                var degenerate = false
                val responses = try {
                    // A single round is capped on the wall clock as well as on tokens. The
                    // between-round budget cannot catch a round that never ends, and a bundle
                    // whose sampler has gone degenerate will happily emit its whole output
                    // allowance as invalid tokens. Nothing is emitted inside this block, so
                    // wrapping it does not break the flow's emission context.
                    withTimeoutOrNull(ROUND_TIMEOUT_MS) {
                        val collected = mutableListOf<Message>()
                        val repetition = RepetitionGuard()
                        try {
                            val stream = when (val input = nextInput) {
                                is Message -> conv.sendMessageAsync(input)
                                else -> conv.sendMessageAsync(input as String)
                            }
                            stream.collect { message ->
                                collected += message
                                if (repetition.isDegenerate(message)) throw DegenerateOutput()

                                message.contents.contents
                                    .filterIsInstance<Content.Text>()
                                    .forEach { part -> roundText.append(part.text) }
                                streamable(roundText, sent)?.let { delta ->
                                    sent += delta.length
                                    Log.d(TAG, "Chunk: ${delta.length} chars (sent=$sent)")
                                    send(ChatEvent.Chunk(delta))
                                }
                                // Channel content is out-of-band by definition, and the
                                // name differs per model (Spark calls its channel
                                // "thought", ours is "thinking"), so route whatever
                                // channels come back rather than the one we declared.
                                message.channels.forEach { (name, text) ->
                                    if (text.isNotEmpty()) {
                                        Log.d(TAG, "Channel '$name': ${text.length} chars")
                                        send(ChatEvent.Thinking(text))
                                    }
                                }
                            }
                        } catch (_: DegenerateOutput) {
                            // Stop the native generation; without this it keeps running through
                            // its whole output allowance after we have walked away.
                            degenerate = true
                            runCatching { conv.cancelProcess() }
                        }
                        collected
                    } ?: run {
                        runCatching { conv.cancelProcess() }
                        Log.w(TAG, "Round $round exceeded ${ROUND_TIMEOUT_MS}ms; cancelled")
                        val stats = readStats(conv)
                        // Timing out having decoded nothing is not a slow model, it is a wedged
                        // executor: every sample came back invalid and was cast to token 0, which
                        // in Spark's tokenizer is <|start of sentence|> -- the token loop seen on
                        // screen and the native "Invalid decode and sample result" warning are the
                        // same event. That state sits in the engine, so recreating the
                        // conversation does not clear it and every later turn fails too. Drop the
                        // engine instead; the next turn re-initializes from scratch.
                        val wedged = stats == null || stats.decodeTokens == 0
                        if (wedged) {
                            Log.w(TAG, "No valid tokens decoded; discarding the engine")
                            closeConversation()
                            closeEngine()
                        }
                        responseText.append(
                            if (wedged) {
                                "\n\n_The model stopped responding. Reloading it for the next " +
                                    "message._"
                            } else {
                                "\n\n_Stopped: the model stopped making progress._"
                            }
                        )
                        send(ChatEvent.Done(responseText.toString(), stats))
                        return@channelFlow
                    }
                } catch (e: Exception) {
                    val remaining = capacityShortfall(e) ?: throw e
                    // The KV is full. Losing the whole turn here would throw away everything
                    // already gathered, so keep it and stop instead.
                    learnCapacity(conv, remaining)
                    Log.w(TAG, "Prefill exceeded remaining capacity ($remaining); ending turn")
                    responseText.append(
                        "\n\n_Ran out of context after $round tool round(s), so I stopped here._"
                    )
                    send(ChatEvent.Done(responseText.toString(), readStats(conv)))
                    return@channelFlow
                }

                if (degenerate) {
                    Log.w(TAG, "Output collapsed into one repeating token; discarding the engine")
                    val stats = readStats(conv)
                    closeConversation()
                    closeEngine()
                    responseText.append(
                        "\n\n_The model stopped responding. Reloading it for the next message._"
                    )
                    send(ChatEvent.Done(responseText.toString(), stats))
                    return@channelFlow
                }

                // Text arrives as deltas across many emissions, so a tool call need not land on
                // the last one. Aggregate across the round and de-duplicate by name+arguments
                // rather than reading only responses.last().
                val nativeCalls = responses
                    .flatMap { it.toolCalls }
                    .distinctBy { it.name to gson.toJson(it.arguments) }
                    .map { PendingToolCall(it.name, it.arguments, fromText = false) }

                // The runtime only parses tool calls for models it has a ModelDataProcessor for.
                // Others emit a perfectly well-formed call as plain text and leave toolCalls
                // empty, so fall back to reading it out of the response.
                val toolCalls = nativeCalls.ifEmpty {
                    if (toolsRegistered) parseToolCallsFromText(roundText.toString()) else emptyList()
                }
                Log.d(
                    TAG,
                    "Round $round: ${toolCalls.size} tool call(s) " +
                        "(native=${nativeCalls.size}, parsed=${toolCalls.count { it.fromText }})"
                )

                // Tool-call markup is protocol, not prose; keep it out of the visible answer.
                val visible = if (toolCalls.any { it.fromText }) {
                    stripToolCallMarkup(roundText.toString())
                } else {
                    roundText.toString()
                }
                responseText.append(visible)

                if (toolCalls.isEmpty()) break

                stopToolLoopReason(conv, deadline, round)?.let { reason ->
                    Log.w(TAG, "Ending tool loop after round $round: $reason")
                    responseText.append("\n\n_Stopped calling tools: $reason._")
                    send(ChatEvent.Done(responseText.toString(), readStats(conv)))
                    return@channelFlow
                }

                // Budget the payload against what the KV can still take, shared across the
                // calls in this round. Checking only current usage is not enough: two 1500-char
                // web pages overflow a 4096-token context, and on some bundles that surfaces as
                // garbage logits ("Invalid decode and sample result") rather than a clean error.
                val perToolChars = toolResultBudget(conv, toolCalls.size)
                if (perToolChars < MIN_USEFUL_TOOL_CHARS) {
                    Log.w(TAG, "Not enough context left for ${toolCalls.size} tool result(s)")
                    responseText.append(
                        "\n\n_Stopped calling tools: not enough context left for the results._"
                    )
                    send(ChatEvent.Done(responseText.toString(), readStats(conv)))
                    return@channelFlow
                }

                val toolResponses = toolCalls.map { toolCall ->
                    val resultJson =
                        executeToolCall(toolCall.name, toolCall.arguments, perToolChars)
                    Content.ToolResponse(toolCall.name, resultJson)
                }

                // Tool output must go back as a tool-role message, not as a user turn, or the
                // model treats the result as something the user typed.
                nextInput = Message.tool(Contents.of(toolResponses))
                round++
            }

            if (round >= MAX_TOOL_ROUNDS) {
                Log.w(TAG, "Hit tool-round ceiling ($MAX_TOOL_ROUNDS)")
            }

            send(ChatEvent.Done(responseText.toString(), readStats(conv)))
        } catch (e: Exception) {
            Log.e(TAG, "Chat failed", e)
            send(ChatEvent.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.Default)

    fun cancel() {
        runCatching { conversation?.cancelProcess() }
            .onFailure { Log.w(TAG, "cancelProcess failed", it) }
    }

    fun shutdown() {
        Log.d(TAG, "Shutting down")
        closeConversation()
        closeEngine()
    }

    private fun buildConversationConfig(
        config: ActiveConfig,
        caps: ModelCapabilities,
        useTools: Boolean
    ): ConversationConfig {
        // Thinking stays advisory: models under-report it. Spark-X2.5-1.7B ships a <think>
        // channel in its own manifest yet answers false to supportsThinking(), and gating on
        // that turned a reasoning model's thoughts into its answer.
        val thinkingEnabled = config.enableThinking
        if (thinkingEnabled && !caps.supportsThinking) {
            Log.w(TAG, "Model reports no thinking support; declaring thought channel anyway")
        }

        // Prefer the user's setting, then the model's own recommendation, then our default --
        // but only accept positive values at each step. The native sampler rejects topK/topP of 0
        // outright ("topK should be positive, but got 0").
        val topK = firstPositive(config.topK, caps.defaultTopK, fallback = DEFAULT_TOP_K)
        val topP = firstPositive(config.topP, caps.defaultTopP, fallback = DEFAULT_TOP_P)
        val temperature = firstPositive(
            config.temperature,
            caps.defaultTemperature,
            fallback = DEFAULT_TEMPERATURE
        )
        Log.d(TAG, "Sampler: topK=$topK, topP=$topP, temperature=$temperature")

        val samplerConfig = SamplerConfig(
            topK = topK,
            topP = topP.toDouble(),
            temperature = temperature.toDouble()
        )

        return ConversationConfig(
            systemInstruction = config.systemPrompt?.let { Contents.of(it) },
            samplerConfig = samplerConfig,
            tools = if (useTools) toolExecutor.getAllTools().map { tool(it) } else emptyList(),
            automaticToolCalling = false,
            channels = if (thinkingEnabled) thinkingChannels(config.modelPath) else emptyList(),
            // Always bounded. Left unset, generation runs until EOS or the KV limit
            // (MAX_NUM_TOKENS), which on CPU is tens of minutes for a model that does not stop
            // promptly. The user's setting overrides this, up to what the KV can hold.
            maxOutputToken = outputTokenLimit(config),
            thinkingConfig = if (thinkingEnabled) {
                ThinkingConfig(
                    enableThinking = true,
                    thinkingTokenBudget = config.thinkingTokenBudget ?: DEFAULT_THINKING_BUDGET
                )
            } else {
                null
            }
        )
    }

    /**
     * Prepends any relevant stored facts to the outgoing message.
     *
     * This goes into the message rather than the system instruction on purpose: the system
     * instruction is part of the conversation's identity, so varying it per turn would make
     * [needsReinitialize] true every time and reload the model on every message.
     */
    private suspend fun withMemoryContext(userText: String): String {
        val store = memory ?: return userText
        val memories = runCatching {
            store.getRelevantMemories(
                query = userText,
                limit = MEMORY_INJECTION_LIMIT,
                fallbackToRecent = false
            )
        }.getOrElse { error ->
            Log.w(TAG, "Memory retrieval failed", error)
            return userText
        }

        val context = formatMemoryContext(memories) ?: return userText
        Log.d(TAG, "Injecting ${memories.size} stored fact(s)")
        return "$context\n\n$userText"
    }

    /**
     * Creates the conversation and reports what the library actually received.
     *
     * Neither available signal is trustworthy enough to gate tools on:
     * - `Capabilities.supportsFunctionCalling()` returns false for both Spark-X2.5-1.7B *and*
     *   gemma-4-E2B, and gemma documents function calling. It under-reports.
     * - `renderPrefaceIntoString()` throws "Failed to apply template: undefined value" on gemma
     *   with tools *and without them*, so it says nothing about tool support either.
     *
     * So tools are passed whenever the caller asks for them, and we log the tool count the
     * library ended up with, which is the one fact that is actually observable.
     */
    @OptIn(ExperimentalApi::class)
    private fun createConversation(
        eng: Engine,
        config: ActiveConfig,
        caps: ModelCapabilities
    ): Conversation {
        if (config.useTools && !caps.supportsFunctionCalling) {
            Log.w(TAG, "Model reports no function-calling support; offering tools anyway")
        }

        applyConversationFlags(config)

        val conv = eng.createConversation(
            buildConversationConfig(config, caps, useTools = config.useTools)
        )

        toolsRegistered = config.useTools && runCatching {
            val described = conv.toolManager.getToolsDescription().size()
            Log.d(TAG, "Tools handed to runtime: $described (${toolsByName.keys.joinToString(",")})")
            described > 0
        }.getOrElse {
            Log.w(TAG, "Could not read tool descriptions", it)
            false
        }

        return conv
    }

    /**
     * Flags read when the *Engine* is built. Setting these later is silently ignored, which shows
     * up as "Benchmark is not enabled. Please make sure the BenchmarkParams is set in the
     * EngineSettings" and, less visibly, as speculative decoding never turning on. Must be called
     * before `Engine(...)`.
     */
    @OptIn(ExperimentalApi::class)
    private fun applyEngineFlags(caps: ModelCapabilities, modelPath: String) {
        ExperimentalFlags.enableSpeculativeDecoding = caps.supportsSpeculativeDecoding
        // Cheap timing counters; without this BenchmarkInfo throws instead of reporting tok/s.
        ExperimentalFlags.enableBenchmark = true
        applyKvCacheFlags(modelPath)
    }

    /**
     * Keeps reasoning content out of the KV.
     *
     * Channel content is not part of the visible transcript and re-reading it on the next turn
     * buys nothing, so on a 4096-token bundle with a model as verbose as Spark it is context
     * spent for no return. The flag is nullable and unset by default, meaning "runtime decides".
     *
     * Set from both the engine and the conversation path because which scope reads it is not
     * documented, and `enableBenchmark` has already demonstrated that a flag set after the
     * Engine exists is silently ignored.
     */
    @OptIn(ExperimentalApi::class)
    private fun applyKvCacheFlags(modelPath: String) {
        ExperimentalFlags.filterChannelContentFromKvCache =
            sidecarFlag(modelPath, "filterThinkingFromKvCache", default = true)
    }

    /**
     * Flags read when a *Conversation* is created.
     *
     * `ExperimentalFlags` is a singleton, so every field must be set on every path -- leaving one
     * alone lets a previous model's value leak into the next.
     *
     * A template dropped beside the model as `<model-name>.jinja` overrides the one embedded in
     * the bundle. That is the supported route to tool calling on a bundle whose own template
     * omits the tools block, as the litert-community conversions do; the upstream templates on
     * Hugging Face include it.
     */
    @OptIn(ExperimentalApi::class)
    private fun applyConversationFlags(config: ActiveConfig) {
        applyKvCacheFlags(config.modelPath)
        val template = promptTemplate(config.modelPath)
        ExperimentalFlags.overwritePromptTemplate = template
        if (template != null) {
            Log.d(TAG, "Overriding prompt template (${template.length} chars) from sidecar .jinja")
        }

        // Opt-in per model, not implied by the template. Constraining the sampler to
        // function-call syntax is a real risk to ordinary prose, so it should never be switched
        // on as a side effect of supplying a template.
        val constrained = config.useTools && sidecarFlag(config.modelPath, "constrainedDecoding")
        ExperimentalFlags.enableConversationConstrainedDecoding = constrained
        Log.d(TAG, "Constrained decoding: $constrained")
    }

    /**
     * Per-model overrides, read from `<model-name>.json` beside the model file:
     *
     *   { "backend": "gpu", "contextTokens": 4096 }
     *
     * A single global KV budget cannot suit every model -- 16384 is cheap for a 1.7B with mostly
     * sliding-window attention and ruinous for a 2.5B whose weights already fill the process --
     * and backends are per-bundle, so both belong next to the model rather than in one setting.
     */
    private fun sidecarConfig(modelPath: String): JsonObject? {
        val file = File(modelPath).let { File(it.parentFile, "${it.nameWithoutExtension}.json") }
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { JsonParser.parseString(file.readText()).asJsonObject }
            .onFailure { Log.w(TAG, "Ignoring unparseable ${file.name}", it) }
            .getOrNull()
    }

    /**
     * The KV the runtime actually gave us, which can be far below what we asked for.
     *
     * `EngineConfig.maxNumTokens` is a request: bundles are exported with their own KV size and
     * the runtime clamps to it, with no API to read the result back. Asking for 16384 against a
     * bundle built for 4096 makes any guard derived from the request useless -- it will never
     * fire, and the loop runs until prefill hard-fails. So the real figure is learned the only
     * way it is exposed: from the failure itself.
     */
    private fun effectiveCapacity(): Int {
        val requested = activeConfig?.contextTokens ?: DEFAULT_CONTEXT_TOKENS
        val path = activeConfig?.modelPath ?: return requested
        val learned = capacityPrefs.getInt(File(path).name, 0)
        return if (learned > 0) minOf(requested, learned) else requested
    }

    private fun learnCapacity(conv: Conversation, remaining: Int) {
        val path = activeConfig?.modelPath ?: return
        val used = runCatching { conv.getTokenCount() }.getOrNull() ?: return
        val key = File(path).name
        val total = used + remaining
        val previous = capacityPrefs.getInt(key, 0)
        if (previous == 0 || total < previous) {
            capacityPrefs.edit().putInt(key, total).apply()
            Log.d(TAG, "Learned KV capacity for $key: $total tokens (was $previous)")
        }
    }

    /** Remaining capacity from a prefill-overflow error, or null if this is a different error. */
    private fun capacityShortfall(e: Throwable): Int? {
        val message = generateSequence(e) { it.cause }
            .mapNotNull { it.message }
            .firstOrNull { it.contains("remaining capacity", ignoreCase = true) }
            ?: return null
        return CAPACITY_REMAINING.find(message)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    @OptIn(ExperimentalApi::class)
    private fun readStats(conv: Conversation): GenerationStats? = runCatching {
        conv.getBenchmarkInfo().let {
            GenerationStats(
                prefillTokens = it.lastPrefillTokenCount,
                decodeTokens = it.lastDecodeTokenCount,
                prefillTokensPerSecond = it.lastPrefillTokensPerSecond,
                decodeTokensPerSecond = it.lastDecodeTokensPerSecond,
                timeToFirstTokenSeconds = it.timeToFirstTokenInSecond
            )
        }
    }.onSuccess { Log.d(TAG, "Generation: ${it.summary()}") }
        .onFailure { Log.w(TAG, "BenchmarkInfo unavailable", it) }
        .getOrNull()

    /**
     * Reasoning-channel delimiters, which differ per model family: Spark and Qwen use
     * `<think>...</think>`, gemma uses `<|channel>...<channel|>`. Declaring the wrong pair means
     * the runtime never routes the reasoning and the raw delimiters land in the answer.
     *
     * All known dialects are declared at once -- a pair that never appears simply never matches,
     * and `chatStream` routes whatever channels come back rather than one name. A model with its
     * own spelling can add it in the sidecar:
     *
     *   { "thinkingChannels": [ { "name": "x", "start": "<a>", "end": "</a>" } ] }
     */
    private fun thinkingChannels(modelPath: String): List<Channel> {
        val configured = sidecarConfig(modelPath)?.getAsJsonArray("thinkingChannels")
            ?: return DEFAULT_THINKING_CHANNELS
        return runCatching {
            configured.map { entry ->
                val o = entry.asJsonObject
                Channel(o.get("name").asString, o.get("start").asString, o.get("end").asString)
            }
        }.onFailure { Log.w(TAG, "Ignoring malformed thinkingChannels", it) }
            .getOrDefault(DEFAULT_THINKING_CHANNELS)
    }

    /**
     * Output allowance, capped against the KV the answer has to share with the prompt.
     *
     * `maxOutputToken` is fixed when the conversation is created, so it cannot adapt per turn.
     * What it must not do is exceed the whole context: a bare 4096 default against a 4096-token
     * bundle lets one answer fill the KV with the prompt still in it. Overrunning is not a clean
     * failure on every bundle -- Spark's int4 export was observed generating 2638 tokens onto a
     * ~1500-token prompt, after which the next turn on the same engine decoded nothing but
     * invalid logits.
     */
    private fun outputTokenLimit(config: ActiveConfig): Int {
        val requested = config.maxOutputTokens ?: DEFAULT_MAX_OUTPUT_TOKENS
        val ceiling = (config.contextTokens * MAX_OUTPUT_FRACTION).toInt()
            .coerceAtLeast(MIN_OUTPUT_TOKENS)
        val limit = minOf(requested, ceiling)
        if (limit < requested) {
            Log.d(TAG, "Output limit $requested -> $limit (context ${config.contextTokens})")
        }
        return limit
    }

    private fun sidecarFlag(modelPath: String, key: String, default: Boolean = false): Boolean =
        sidecarConfig(modelPath)?.get(key)?.asBoolean ?: default

    private fun resolveBackend(modelPath: String, requested: LlmBackend): LlmBackend {
        val override = sidecarConfig(modelPath)?.get("backend")?.asString ?: return requested
        val resolved = LlmBackend.fromName(override)
        Log.d(TAG, "Sidecar overrides backend: $requested -> $resolved")
        return resolved
    }

    private fun resolveContextTokens(modelPath: String, requested: Int?): Int {
        val override = sidecarConfig(modelPath)?.get("contextTokens")?.asInt
        return (override ?: requested ?: DEFAULT_CONTEXT_TOKENS).coerceAtLeast(1024)
    }

    /**
     * CPU worker threads, from the sidecar's `cpuThreads`.
     *
     * `Backend.CPU()` leaves this null and lets the runtime decide, which is not obviously the
     * right call on a big.LITTLE phone -- scheduling inference onto efficiency cores costs more
     * than the extra parallelism buys. Exposed so it can be measured per device rather than
     * guessed, since CPU is the only working backend for int4 bundles on this runtime.
     */
    private fun cpuThreads(modelPath: String): Int? =
        sidecarConfig(modelPath)?.get("cpuThreads")?.asInt?.takeIf { it > 0 }
            ?.also { Log.d(TAG, "CPU thread count from sidecar: $it") }

    private fun litertBackend(backend: LlmBackend, modelPath: String): Backend = when (backend) {
        LlmBackend.CPU -> Backend.CPU(threadCount = cpuThreads(modelPath))
        LlmBackend.GPU -> Backend.GPU()
        LlmBackend.NPU -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
        LlmBackend.GOOGLE_TENSOR -> Backend.GOOGLE_TENSOR()
    }

    /** An overriding template lives beside the model: `gemma-4-E2B-it.litertlm` -> `.jinja`. */
    private fun templateFile(modelPath: String): File = File(modelPath).let {
        File(it.parentFile, "${it.nameWithoutExtension}.jinja")
    }

    private fun promptTemplate(modelPath: String): String? {
        val file = templateFile(modelPath)
        return if (file.exists() && file.length() > 0) {
            runCatching { file.readText() }
                .onFailure { Log.w(TAG, "Could not read ${file.name}", it) }
                .getOrNull()
        } else {
            null
        }
    }

    /** Cheap identity for the sidecar template, so replacing it forces a re-initialize. */
    private fun templateFingerprint(modelPath: String): String? {
        val file = templateFile(modelPath)
        return if (file.exists()) "${file.length()}@${file.lastModified()}" else null
    }

    private fun firstPositive(vararg candidates: Int?, fallback: Int): Int =
        candidates.firstOrNull { it != null && it > 0 } ?: fallback

    private fun firstPositive(vararg candidates: Float?, fallback: Float): Float =
        candidates.firstOrNull { it != null && it > 0f } ?: fallback

    private fun probeCapabilities(modelPath: String): ModelCapabilities {
        return try {
            Capabilities(modelPath).use { caps ->
                val sampler = runCatching { caps.defaultSamplerParams() }.getOrNull()
                val modalities = runCatching { caps.inputModalities() }.getOrNull()
                ModelCapabilities(
                    supportsFunctionCalling = caps.supportsFunctionCalling(),
                    supportsThinking = caps.supportsThinking(),
                    supportsSpeculativeDecoding = caps.hasSpeculativeDecodingSupport(),
                    supportsVision = modalities?.vision ?: false,
                    supportsAudio = modalities?.audio ?: false,
                    // These come back as primitives, so a model that declares no sampler
                    // defaults reports 0 rather than null. 0 is not a usable topK/topP.
                    defaultTemperature = sampler?.temperature?.takeIf { it > 0f },
                    defaultTopK = sampler?.topK?.takeIf { it > 0 },
                    defaultTopP = sampler?.topP?.takeIf { it > 0f }
                )
            }
        } catch (e: Exception) {
            // An older or unrecognised model file may not carry capability metadata. Assume the
            // permissive defaults the app used before capability probing existed.
            Log.w(TAG, "Capability probe failed for $modelPath, assuming defaults", e)
            ModelCapabilities()
        }
    }

    /**
     * Reads tool calls out of a plain-text response.
     *
     * Handles the `<tool_call>` family, which covers Spark, Qwen and Hermes-style models, in both
     * the arg_key/arg_value and JSON spellings:
     *
     *   <tool_call>calculator<arg_key>expression</arg_key><arg_value>2+2</arg_value></tool_call>
     *   <tool_call>{"name": "calculator", "arguments": {"expression": "2+2"}}</tool_call>
     *
     * Only called when tools are registered, so a model merely discussing the syntax in prose
     * cannot trigger an execution.
     */
    private fun parseToolCallsFromText(text: String): List<PendingToolCall> =
        TOOL_CALL_BLOCK.findAll(text)
            .mapNotNull { match -> parseToolCallBody(match.groupValues[1].trim()) }
            .distinctBy { it.name to gson.toJson(it.arguments) }
            .toList()

    private fun parseToolCallBody(body: String): PendingToolCall? {
        if (body.isEmpty()) return null

        if (body.startsWith("{")) {
            return runCatching {
                val obj = JsonParser.parseString(body).asJsonObject
                val name = obj.get("name").asString
                val args = obj.getAsJsonObject("arguments")
                    ?.entrySet()
                    ?.associate { (key, value) ->
                        key to (if (value.isJsonPrimitive) value.asString else value.toString())
                    }
                    .orEmpty()
                PendingToolCall(name, args, fromText = true)
            }.onFailure { Log.w(TAG, "Unparseable JSON tool call: ${body.take(120)}", it) }
                .getOrNull()
        }

        val name = body.substringBefore("<arg_key>").trim()
        if (name.isEmpty()) return null
        val args = TOOL_CALL_ARG.findAll(body).associate { match ->
            match.groupValues[1].trim() to match.groupValues[2].trim() as Any?
        }
        return PendingToolCall(name, args, fromText = true)
    }

    /**
     * The next slice of this round that is safe to show, or null if there is nothing yet.
     *
     * Streaming raw deltas would put `<tool_call>calculator<arg_key>...` on screen, because tool
     * calls arrive as ordinary text from models the runtime has no parser for and are only
     * stripped once the round ends. So the visible text is recomputed from the whole round each
     * time and the caller is sent only the part it has not seen.
     *
     * Two tails are held back rather than shown and retracted, since a delta cannot be unsent:
     * an unfinished `<tool_call>` block, and a trailing `<` that has not closed yet and may be
     * the start of one.
     */
    private fun streamable(round: StringBuilder, sent: Int): String? {
        val visible = TOOL_CALL_BLOCK.replace(round, "")
        val opener = visible.indexOf(TOOL_CALL_OPEN).takeIf { it >= 0 }
        val dangling = visible.lastIndexOf('<').takeIf { it >= 0 && !visible.endsWith('>') }
        val safeEnd = listOfNotNull(opener, dangling).minOrNull() ?: visible.length
        if (safeEnd <= sent) return null
        return visible.substring(sent, safeEnd)
    }

    private fun stripToolCallMarkup(text: String): String =
        TOOL_CALL_BLOCK.replace(text, "").trim()

    /**
     * Why the tool loop should stop, or null to keep going.
     *
     * Each round is a full generation whose prompt carries every previous tool result, so an
     * unbounded loop grows the KV footprint and the wall clock together. Observed on a 1.7B model:
     * two untruncated web page fetches were enough to push round 2 past three minutes with no end
     * in sight. The cost is decode time and KV RAM, not a model context limit.
     */
    private fun stopToolLoopReason(conv: Conversation, deadline: Long, round: Int): String? {
        if (System.currentTimeMillis() > deadline) {
            return "time budget reached after ${round + 1} round(s)"
        }
        val tokens = runCatching { conv.getTokenCount() }.getOrNull() ?: return null
        val budget = effectiveCapacity()
        val ceiling = (budget * CONTEXT_PRESSURE_FRACTION).toInt()
        if (tokens > ceiling) {
            return "context nearly full ($tokens of $budget tokens)"
        }
        return null
    }

    /**
     * Characters of tool output this round can afford, per call.
     *
     * Tool output goes back into the prompt verbatim, so it has to fit in the KV that is left,
     * not in a fixed constant. Returns below [MIN_USEFUL_TOOL_CHARS] when there is no room worth
     * using, which the caller treats as "stop looping".
     */
    private fun toolResultBudget(conv: Conversation, toolCount: Int): Int {
        if (toolCount <= 0) return 0
        val used = runCatching { conv.getTokenCount() }.getOrNull()
            ?: return MAX_TOOL_RESULT_CHARS
        // Reserve what the runtime has already been told it may generate. A fixed fraction is
        // not enough on its own: 0.75 of the context for the prompt plus a 0.5 output allowance
        // promises 1.25 of a context, and the overrun lands mid-answer rather than as an error.
        val reserved = activeConfig?.let { outputTokenLimit(it) } ?: 0
        val ceiling = minOf(
            (effectiveCapacity() * CONTEXT_PRESSURE_FRACTION).toInt(),
            effectiveCapacity() - reserved
        )
        val headroomChars = (ceiling - used).coerceAtLeast(0) * CHARS_PER_TOKEN
        val perTool = (headroomChars / toolCount).coerceAtMost(MAX_TOOL_RESULT_CHARS)
        Log.d(
            TAG,
            "Tool budget: used=$used ceiling=$ceiling -> $perTool chars x $toolCount call(s)"
        )
        return perTool
    }

    private fun truncateToolResult(name: String, result: String, limit: Int): String {
        if (result.length <= limit) return result
        Log.d(TAG, "Truncating $name result from ${result.length} to $limit chars")
        return result.take(limit) + "\u2026[truncated]"
    }

    private fun executeToolCall(
        name: String,
        arguments: Map<String, Any?>,
        resultLimit: Int = MAX_TOOL_RESULT_CHARS
    ): String {
        val tool = toolsByName[name] ?: run {
            Log.w(TAG, "Unknown tool: $name")
            return gson.toJson(mapOf("error" to "Tool not found: $name"))
        }
        return try {
            val result = truncateToolResult(name, tool.execute(gson.toJson(arguments)), resultLimit)
            Log.d(TAG, "Tool $name result: ${result.take(80)}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution failed: $name", e)
            gson.toJson(mapOf("error" to (e.message ?: "Tool execution failed")))
        }
    }

    private fun toolName(tool: OpenApiTool): String? = try {
        JsonParser.parseString(tool.getToolDescriptionJsonString())
            .takeIf { it.isJsonObject }
            ?.let { it.asJsonObject as JsonObject }
            ?.get("name")
            ?.asString
    } catch (e: Exception) {
        Log.e(TAG, "Could not read tool name from description", e)
        null
    }

    private fun closeConversation() {
        runCatching { conversation?.close() }
            .onFailure { Log.w(TAG, "Failed to close conversation", it) }
        conversation = null
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
            .onFailure { Log.w(TAG, "Failed to close engine", it) }
        engine = null
        activeConfig = null
        capabilities = null
    }

    companion object {
        const val MODEL_DIR = "litertlm_models"
        const val DEFAULT_MODEL_NAME = "gemma-4-E2B-it.litertlm"
        const val DEFAULT_HUGGINGFACE_REPO = "litert-community/gemma-4-E2B-it-litert-lm"

        /** Default KV budget; override per model with a sidecar `.json`. */
        private const val DEFAULT_CONTEXT_TOKENS = 16384
        /**
         * Fallback only; the caller passes the user's setting. Rounds are not the binding limit
         * on-device -- context pressure and the per-round timeout stop the loop first.
         */
        private const val MAX_TOOL_ROUNDS = 10
        private const val TOOL_LOOP_BUDGET_MS = 3 * 60 * 1000L

        /** Hard ceiling on one generation, so a degenerate sampler cannot run for its whole
         * output allowance. Generous: a slow CPU round on a 2.5B model measured ~90s. */
        private const val ROUND_TIMEOUT_MS = 4 * 60 * 1000L
        private const val MAX_TOOL_RESULT_CHARS = 1500

        /** Below this a truncated tool result carries too little to be worth the context. */
        private const val MIN_USEFUL_TOOL_CHARS = 250

        /** Rough chars-per-token for budgeting. Deliberately low, so the estimate over-reserves. */
        private const val CHARS_PER_TOKEN = 3

        /**
         * Fraction of the allocated KV budget at which tool looping stops.
         *
         * Anchored to [MAX_NUM_TOKENS] because that is what we actually allocate, not to any
         * figure a bundle's manifest quotes. Those are conversion choices, not model limits:
         * Spark-X2.5-1.7B's manifest says 4096 while the base model's
         * `max_position_embeddings` is 1048576, and its 3-sliding-to-1-full layer pattern
         * (window 512) means only 7 of 28 layers carry full-attention KV, so long context is
         * cheap for it. The binding constraint is the RAM the KV occupies -- which is what gets
         * the `:llm` process OOM-killed -- so the guard follows the allocation.
         */
        private const val CONTEXT_PRESSURE_FRACTION = 0.75
        private val CAPACITY_REMAINING = Regex("""remaining capacity:\s*(\d+)""")
        private const val DEFAULT_TEMPERATURE = 0.8f
        private const val DEFAULT_TOP_P = 0.95f
        private const val DEFAULT_TOP_K = 10
        private const val DEFAULT_THINKING_BUDGET = 2048
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 4096

        /**
         * Share of the context one answer may claim, leaving the rest for the prompt.
         *
         * Deliberately a quarter rather than a half: this allowance is also what gets reserved
         * when sizing tool results, and in a tool loop the input side is worth far more than a
         * long answer. Reserving half of a 4096 context starved the second round down to 252
         * characters and the third to nothing, on an answer that came to 71 tokens.
         */
        private const val MAX_OUTPUT_FRACTION = 0.25

        /** Floor, so a very small context still allows a usable answer. */
        private const val MIN_OUTPUT_TOKENS = 256

        private const val TOOL_CALL_OPEN = "<tool_call>"
        private val TOOL_CALL_BLOCK = Regex(
            """<tool_call>(.*?)</tool_call>""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val TOOL_CALL_ARG = Regex(
            """<arg_key>(.*?)</arg_key>\s*<arg_value>(.*?)</arg_value>""",
            RegexOption.DOT_MATCHES_ALL
        )
        private const val MEMORY_INJECTION_LIMIT = 5

        /** Non-overlapping, so declaring all of them is safe for any single model. */
        private val DEFAULT_THINKING_CHANNELS = listOf(
            Channel("thinking", "<think>", "</think>"),
            Channel("thought", "<|channel>", "<channel|>")
        )
    }
}
