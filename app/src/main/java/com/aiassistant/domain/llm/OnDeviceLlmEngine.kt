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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
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

            applyEngineFlags(caps)

            // Only name a vision/audio backend when the bundle actually carries that encoder.
            // Naming one it lacks turns a skippable warning into a hard createConversation
            // failure ("TF_LITE_AUDIO_ENCODER_HW not found in the model") -- gemma's -gpu bundle
            // is text-only while its CPU bundle is multi-modal. Audio stays on CPU when present,
            // per the docs' multi-modal compliance note.
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = litertBackend(config.backend),
                visionBackend = if (caps.supportsVision) litertBackend(config.backend) else null,
                audioBackend = if (caps.supportsAudio) Backend.CPU() else null,
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

    fun chatStream(messages: List<ChatMessage>): Flow<ChatEvent> = flow {
        val conv = conversation ?: run {
            emit(ChatEvent.Error("Model not initialized"))
            return@flow
        }

        val lastUserMessage = messages.lastOrNull { it.role == MessageRole.USER } ?: run {
            emit(ChatEvent.Error("No user message found"))
            return@flow
        }

        Log.d(TAG, "Starting chat stream for: ${lastUserMessage.content.take(50)}...")
        activeConversationId = lastUserMessage.conversationId

        try {
            val responseText = StringBuilder()
            var nextInput: Any = withMemoryContext(lastUserMessage.content)
            var round = 0
            val deadline = System.currentTimeMillis() + TOOL_LOOP_BUDGET_MS

            while (round < MAX_TOOL_ROUNDS) {
                val roundText = StringBuilder()
                val responses = try {
                    when (val input = nextInput) {
                        is Message -> conv.sendMessageAsync(input).toList()
                        else -> conv.sendMessageAsync(input as String).toList()
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
                    emit(ChatEvent.Done(responseText.toString(), readStats(conv)))
                    return@flow
                }

                for (response in responses) {
                    response.contents.contents
                        .filterIsInstance<Content.Text>()
                        .forEach { part ->
                            if (part.text.isNotEmpty()) {
                                roundText.append(part.text)
                                emit(ChatEvent.Chunk(part.text))
                            }
                        }
                    // Channel content is out-of-band by definition, and the name differs per
                    // model (Spark calls its channel "thought", ours is "thinking"), so route
                    // whatever channels come back rather than only the one we declared.
                    response.channels.forEach { (name, text) ->
                        if (text.isNotEmpty()) {
                            Log.d(TAG, "Channel '$name': ${text.length} chars")
                            emit(ChatEvent.Thinking(text))
                        }
                    }
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
                    emit(ChatEvent.Done(responseText.toString(), readStats(conv)))
                    return@flow
                }

                val toolResponses = toolCalls.map { toolCall ->
                    val resultJson = executeToolCall(toolCall.name, toolCall.arguments)
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

            emit(ChatEvent.Done(responseText.toString(), readStats(conv)))
        } catch (e: Exception) {
            Log.e(TAG, "Chat failed", e)
            emit(ChatEvent.Error(e.message ?: "Unknown error"))
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
            // promptly. The user's setting overrides this.
            maxOutputToken = config.maxOutputTokens ?: DEFAULT_MAX_OUTPUT_TOKENS,
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
    private fun applyEngineFlags(caps: ModelCapabilities) {
        ExperimentalFlags.enableSpeculativeDecoding = caps.supportsSpeculativeDecoding
        // Cheap timing counters; without this BenchmarkInfo throws instead of reporting tok/s.
        ExperimentalFlags.enableBenchmark = true
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

    private fun sidecarFlag(modelPath: String, key: String): Boolean =
        sidecarConfig(modelPath)?.get(key)?.asBoolean ?: false

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

    private fun litertBackend(backend: LlmBackend): Backend = when (backend) {
        LlmBackend.CPU -> Backend.CPU()
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
     * Tool output goes back into the prompt verbatim, so an untruncated web page can consume a
     * small model's entire context in one round.
     */
    private fun truncateToolResult(name: String, result: String): String {
        if (result.length <= MAX_TOOL_RESULT_CHARS) return result
        Log.d(TAG, "Truncating $name result from ${result.length} to $MAX_TOOL_RESULT_CHARS chars")
        return result.take(MAX_TOOL_RESULT_CHARS) + "\u2026[truncated]"
    }

    private fun executeToolCall(name: String, arguments: Map<String, Any?>): String {
        val tool = toolsByName[name] ?: run {
            Log.w(TAG, "Unknown tool: $name")
            return gson.toJson(mapOf("error" to "Tool not found: $name"))
        }
        return try {
            val result = truncateToolResult(name, tool.execute(gson.toJson(arguments)))
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
        private const val MAX_TOOL_ROUNDS = 25
        private const val TOOL_LOOP_BUDGET_MS = 3 * 60 * 1000L
        private const val MAX_TOOL_RESULT_CHARS = 1500

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
