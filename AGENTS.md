# AI Assistant Android - Agent Guidelines

## Project Overview

**Synapse** — Modern Android AI assistant app connecting to any OpenAI-compatible API. Built with Kotlin, Jetpack Compose, and Clean Architecture.

**Package**: `com.aiassistant`
**Min SDK**: 26 (Android 8.0)
**Target SDK**: 35
**Compile SDK**: 37
**Java Target**: 17

Versions live in `gradle/libs.versions.toml` (version catalog) — not inline in the build files.

## Build Commands

```bash
# Build debug
.\gradlew.bat :app:assembleDebug

# Build release
.\gradlew.bat :app:assembleRelease

# Clean build
.\gradlew.bat clean :app:assembleDebug

# Lint
.\gradlew.bat :app:lintDebug

# Run tests
.\gradlew.bat test
```

## Architecture

Three-layer Clean Architecture:

### Presentation (`presentation/`)
- **Screens**: `ChatScreen.kt`, `SettingsScreen.kt`
- **ViewModels**: `ChatViewModel.kt`, `SettingsViewModel.kt`
- **Navigation**: `AppNavigation.kt` (NavHost with "chat" and "settings")
- **UI Components**: `MarkdownText.kt` (commonmark renderer)

### Domain (`domain/`)
- **Models**: `ChatMessage`, `Conversation`, `MemoryEntry`, `Attachment`, `ToolCall`, `ToolResult`
- **Repositories**: Interfaces (`ChatApiRepository`, `ConversationRepository`, etc.)
- **Use Cases**: `SendChatMessageUseCase`, `MemorySearchUseCase`
- **Services**: `ToolManager` (tool registry), `VectorMathService` (cosine similarity)
- **Tools**: `CalculatorTool`, `CodeInterpreterTool`, `DeviceInfoTool`, `WebSearchTool`, `WebPageFetcherTool`, `WeatherTool`

### Data (`data/`)
- **API**: Retrofit client (`OpenAIService`, `RetrofitClient`)
- **Database**: Room (`AppDatabase`, `ConversationDao`, `MessageDao`, `MemoryDao`)
- **Repositories**: Implementations of domain interfaces
- **Models**: Room entities (`ConversationEntity`, `MessageEntity`, `MemoryEntryEntity`), API models (`ChatCompletionRequest`, `ChatCompletionResponse`, etc.)

### Dependency Injection (`di/`)
- `AppModule.kt` - `@Module` with `@Provides` for singletons
- `RepositoryBindingModule.kt` - `@Module` with `@Binds` for repository interfaces

## Code Conventions

### Kotlin
- Use Kotlin official code style (`kotlin.code.style=official`)
- No unnecessary comments - code should be self-documenting
- Use data classes for models
- Use sealed interfaces/classes for type hierarchies
- Use `sealed interface` for UI state/message types

### Compose
- Use Material 3 components exclusively
- Use `MaterialTheme.colorScheme` for all colors
- Use `MaterialTheme.typography` for text styles
- No `LazyColumn` inside `LazyColumn` (nested scroll crash) - use `AnnotatedString` or `Column` instead
- Use `Modifier` chaining for layout

### State Management
- `MutableStateFlow` / `StateFlow` for VM state
- `collect` in `init` or `LaunchedEffect`
- UI state as immutable data classes

### Coroutines
- `viewModelScope.launch` in ViewModels
- `Dispatchers.IO` for blocking operations (file I/O, network)
- `withContext(Dispatchers.IO)` for suspending blocks
- Handle exceptions in `try/catch`

### DI (Hilt)
- `@HiltViewModel` for ViewModels
- `@AndroidEntryPoint` for Activities/Fragments
- `@Module` + `@Provides` for singletons
- `@Module` + `@Binds` for repository implementations
- `@ApplicationContext` qualifier for Context injection

### Naming
- ViewModel: `XxxViewModel`
- Screen: `XxxScreen`
- Repository interface: `XxxRepository`
- Repository impl: `XxxRepositoryImpl`
- Use case: `XxxUseCase`
- Tool: `XxxTool`
- Entity: `XxxEntity`
- DAO: `XxxDao`

## Key Patterns

### Adding a New Tool
1. Create `XxxTool.kt` in `domain/tool/`
2. Register in `ToolManager.buildToolDefinitions()`
3. Add case in `ToolExecutor.executeTool()`

For the on-device path, add an `OpenApiTool` impl in `OnDeviceToolExecutor.kt` and list it in
`getAllTools()`; the engine picks it up by the `name` in its description JSON.

### Adding a New Screen
1. Create `XxxScreen.kt` in `presentation/screen/`
2. Create `XxxViewModel.kt` in `presentation/vm/`
3. Add route to `AppNavigation.kt`
4. Add navigation graph in `AppNavigation.kt`

### Adding a New Repository
1. Create interface in `domain/repository/`
2. Create implementation in `data/repository/`
3. Add `@Binds` in `RepositoryBindingModule.kt`
4. Add `@Provides` in `AppModule.kt` if needed

### Database Changes
1. Update entity in `data/database/`
2. Update DAO in `data/database/`
3. Increment `Room.databaseBuilder().build()` version if schema migration needed

## API Integration

### OpenAI-Compatible API
- Base URL configurable in Settings
- Supports chat completions, streaming, embeddings
- Multimodal via `content: Any?` (String or array of content parts)
- Tool calling via `tools` parameter (max 10 rounds per message)

### Attachment Handling
- Images: base64-encoded, sent via `image_url` content parts
- Documents: text parsed and inserted into message content (max 10k chars)
- PDFs: filename only (no client-side PDF parsing on Android)
- File picker: `GetMultipleContents` for multi-select

### Markdown Rendering
- Uses `commonmark 0.30.0`
- Custom `MarkdownText.kt` with `AnnotatedString` for bold/italic/code formatting
- Supports: headings, bold, italic, inline code, code blocks, lists, blockquotes, horizontal rules
- No `LazyColumn` in renderer (nested scroll crash)

## Dependencies

| Library | Version | Usage |
|---------|---------|-------|
| AGP | 9.4.0 | Build (built-in Kotlin, new DSL) |
| Gradle | 9.7.1 | Build |
| Kotlin | 2.4.20 | Language (compiled by AGP, not KGP) |
| KSP | 2.3.11 | Annotation processing |
| Compose BOM | 2026.08.00 | UI framework |
| Hilt | 2.60.1 | Dependency injection |
| Room | 2.8.4 | Local database |
| Retrofit | 3.0.0 | HTTP client |
| OkHttp | 5.5.0 | HTTP client |
| Gson | 2.14.0 | JSON serialization |
| Coil | 3.6.2 | Image loading (`coil3.*` package) |
| Commonmark | 0.30.0 | Markdown parsing |
| Jsoup | 1.23.2 | Web scraping |
| Mozilla Rhino | 1.9.1 | JavaScript engine |
| DataStore | 1.2.1 | Preferences storage |
| WorkManager | 2.11.2 | Scheduled tasks |
| Accompanist | 0.37.3 | Permissions |
| LiteRT-LM | 0.17.0 | On-device LLM + embeddings |

## On-Device Inference (LiteRT-LM)

Two engines, deliberately in different processes:

- **`OnDeviceLlmEngine`** (chat) runs in the `:llm` process behind `LlmService`. Reached from the
  app process via `LlmClient` over a `Messenger`; the wire protocol constants live in
  `service/LlmIpc.kt` and must stay in sync on both sides.
- **`OnDeviceEmbeddingEngine`** (memory search) runs in the app process, injected directly. Uses a
  separate, much smaller embedding model.

### Capability probing

`Capabilities(modelPath)` is probed before the engine is created, but it is **advisory only** and
must not gate features:

- Models under-report. `Spark-X2.5-1.7B` answers `false` to both `supportsThinking()` and
  `supportsFunctionCalling()` while its own manifest declares a `<think>` channel and it is a
  reasoning model. Gating on the probe silently turned its reasoning into its answer.
- Honour what the caller asked for and log the disagreement. The probe's useful half is filling
  *unset* sampler values from `defaultSamplerParams()` and the positive-only speculative-decoding
  enable.
- `SamplerParameters` getters return **primitives**, so a model with no declared defaults reports
  `0`, not null. `?:` does not catch that; use `firstPositive(...)`. Passing `topK = 0` fails with
  "topK should be positive, but got 0".

### Thinking / channels

Channel content is out-of-band and arrives as many tiny deltas — 1124 chars over 229 events for a
one-word prompt — so any consumer must **accumulate**, never replace.

Channel names differ per model (`Spark`'s manifest calls its channel `thought`; ours is declared
as `thinking`), so `chatStream` routes *every* entry in `Message.channels` rather than looking up
one key.

Declaring the channel is what makes `thinkingTokenBudget` take effect. With no channel declared,
Spark spent 20k+ tokens and over five minutes answering "hi"; with it declared, the same prompt
takes ~24s and the reasoning stays out of the reply.

### On-device tool calling: works, via two workarounds

It needs both halves. Verified end to end on Spark-X2.5-1.7B (Galaxy S24+, LiteRT-LM 0.17.0):
`98765 * 4321` -> `calculator` -> 426,763,565, and a 3-round web search in ~92s.

**1. The bundle's template omits the tools declaration.** litert-community conversions ship a
simplified template, so the model is never told tools exist -- Qwen3.5-4B's manifest says so
outright: *"Ships a simplified ChatML template with tool-calling and vision sections not
included"*. Fix: drop the upstream `chat_template.jinja` beside the model as
`<model-name>.jinja` and `applyExperimentalFlags` feeds it to
`ExperimentalFlags.overwritePromptTemplate`. Upstream templates do include the tools block
(gemma's is in the litert-community repo; Spark's is in `XHToken/Spark-X2.5-1.7B`).

**2. The runtime does not parse the model's tool-call dialect.** Tool-call parsing lives in a
`ModelDataProcessor` specific to each model, and there isn't one for these. With the template
fixed the model emits a textbook call and `Message.toolCalls` still comes back empty, so
`parseToolCallsFromText` reads it out of the response text. Native calls always take precedence;
the text path is a fallback, gated on `toolsRegistered` so a model merely discussing the syntax
cannot trigger an execution. Handles the `<tool_call>` family (Spark/Qwen/Hermes) in both the
`arg_key`/`arg_value` and JSON spellings. Gemma's dialect (`<|tool_call>call:name{...}`) is *not*
handled.

Do not gate tools on `Capabilities.supportsFunctionCalling()`: it returns false for Spark,
gemma-4-E2B and Ministral-3-3B alike, including the case that demonstrably works.

`renderPrefaceIntoString()` is not a usable signal for anything -- it throws
`Failed to apply template: undefined value` whenever `messages` is empty, because the templates
dereference `messages[0]` unguarded. That is a preface-rendering artifact, not a tools problem.

### The tool loop must be bounded

Every round is a full generation whose prompt carries all previous tool results, so rounds cost
context and wall-clock together. Untruncated, two `web_fetch` results (2699 and 3499 chars) pushed
round 2 of a 4096-context model past three minutes with no end in sight.

Three guards, all in `chatStream`/`executeToolCall`:

- `MAX_TOOL_RESULT_CHARS` (1500) truncates tool output before it re-enters the prompt.
- `TOOL_LOOP_BUDGET_MS` (3 min) ends the loop and returns what exists.
- `CONTEXT_PRESSURE_TOKENS` (3000) ends the loop via `Conversation.getTokenCount()`.

### maxNumTokens is a request, not an allocation

The runtime clamps `EngineConfig.maxNumTokens` to whatever KV the bundle was exported with, and
exposes no way to read the result. Asking for 16384 against a bundle built for 4096 silently
leaves the guard computing a 12288-token ceiling that can never be reached, so the tool loop runs
until prefill hard-fails with:

    FAILED_PRECONDITION: Prefill input length exceeds available state entries (remaining capacity: N)

Two mitigations, both needed:

- **Recover, don't fail.** That error is caught in `chatStream` and ends the turn with whatever
  was gathered, rather than discarding the entire exchange.
- **Learn the real figure.** `learnCapacity` reads `N` out of the error and adds it to
  `getTokenCount()` to get the true total, persisted per model in the `llm_kv_capacity` prefs
  (the `:llm` process is killed too often for an in-memory value to survive). `effectiveCapacity`
  then guards against `min(requested, learned)`.

Seed a known value with a sidecar (`{"contextTokens": 4096}`) to get the right ceiling on the
first turn instead of after one failure.

`CONTEXT_PRESSURE_FRACTION` is a fraction of the *effective* capacity. Do **not** re-anchor it
to the `context_length` a bundle's manifest quotes -- those are conversion choices, not model
limits. Spark's manifest says 4096 while the base model's `max_position_embeddings` is 1048576,
and a re-export at a larger `CACHE` is a one-variable change to the conversion recipe
(`hf-to-litertlm`, `spark_work/convert_spark.py`, `CACHE` env var).

It was previously recorded here that long context is therefore *cheap* for Spark, on the grounds
that only 7 of its 28 layers carry full-attention KV (the other 21 use a 512-token sliding
window). That is true of the architecture and false of the export -- see the next section. Do not
size anything on the sliding-window pattern.

### What context actually costs

KV is allocated **uniformly for every layer at the full exported length**, in fp32. The HF
config's `sliding_window: 512` on 21 of 28 layers buys nothing: litert-torch's transposed KV cache
is uniform, and `patch_modeling.py` exists precisely to route attention through it.

For Spark-X2.5-1.7B (28 layers, 2 kv-heads, head_dim 256) that is
`2 (K+V) x 2 x 256 = 1024` values per token per layer, so **28 x 1024 x 4 B = 112 KiB/token**.

Measured on a Galaxy S24+ (11.35 GB), int4, `:llm` RSS:

| exported `CACHE` | RSS | outcome |
|---|---|---|
| 4096 | 1.31 GB | fine |
| 65536 | 7.9 GB | `LOW_MEMORY` / `OOM KILL` 29s into load |

`(7.9 - 1.31) GB / 61440 tokens` = ~107 KiB/token, 94% of the predicted 112 -- the shortfall is
that the 64k process was killed part-way through allocating. Non-KV footprint is therefore
~840 MB, which projects to ~1.8 GB at 8192, ~2.7 GB at 16384, ~4.6 GB at 32768.

Read the reason out of `dumpsys activity exit-info com.aiassistant` rather than guessing: it gives
`reason`, `subreason` and the RSS at death. lmkd's own log said "no processes to kill" for the same
event, so it was the kernel OOM killer, not the low-memory killer.

**A bigger allocation also costs throughput on CPU, whether or not the tokens get used.** Same
bundle, same 6 threads, same 586-token answer:

| `CACHE` | prefill | decode | time to first token |
|---|---|---|---|
| 4096 | 67 tok/s | 11.9 tok/s | 17.7s |
| 16384 | 20 tok/s | 4.1 tok/s | 63.4s |

3.3x on prefill for allocation alone. The likely mechanism is `max_len` sitting in the innermost
stride: the live prefix of each row is one cache line scattered across a 1.88 GB region, so a
larger export means more TLB and cache misses per token. Budget context by what the device can
*use*, not by what fits in RAM.

`use_ringbuffers_local_attention` would fix this properly -- it sizes local-attention layers to
their window instead of the full context, a ~4x reduction for Spark. It exists in litert-lm's
Python API, its CLI (`--ringbuffers-local-attention`) and its C++ `GpuArtisanConfig`
(`use_autosized_ringbuffers`), but **not in the Kotlin binding**: `Engine.initialize()` passes a
fixed JNI argument list with no slot for it, and 0.17.0 is the newest published Android artifact.
Nothing to do here until upstream exposes it.

`ExperimentalFlags.filterChannelContentFromKvCache` does exist in the AAR and is currently unused;
with thinking enabled it would keep reasoning content out of the KV.

### int4 bundles decode garbage on the GPU path

**int4 + `Backend.GPU()` is broken on this runtime. Route int4 to CPU via the sidecar.**

From the very first decode step every sampled token is invalid. The runtime logs

    llm_litert_compiled_model_executor.cc:1190] Invalid decode and sample result.
    The sampled token is casted to 0 to avoid crash.

once per step, and **token 0 is `<|start of sentence|>` in Spark's tokenizer** (1 = end of
sentence, 2 = pad, 3/4 = `<think>`/`</think>`), so the visible output is that marker repeated until
the output cap or the round timeout stops it. The native warning and the on-screen token loop are
the same event, not two problems.

Isolated by elimination:

| bundle | backend | result |
|---|---|---|
| litert-community int4 | GPU | invalid logits |
| own conversion, int4, `CACHE=4096` | GPU | invalid logits |
| both of the above | CPU | correct, tools work |
| Spark int8 | GPU | correct, 11.7 tok/s |

Two independent conversions with different quantizer settings fail identically, and int8 on the
same GPU is fine, so this is the ML Drift int4 decode path rather than anyone's export.

It is **not** context pressure. Reproduced at `used=1523`, ceiling 2048, one 500-token tool result
-- about 2023 tokens in a 4096 context, nothing near full. Tool-payload overflow and then output
length were each offered as the explanation and each refuted by measurement; the budget fixes were
worth making on their own terms, but they are not the cause of this.

**The damage is engine-scoped and survives `resetConversation()`.** The executor belongs to the
`Engine`, so a fresh `Conversation` inherits the poisoned state and every later turn fails too,
until the process restarts. `chatStream` discards the engine when a round times out having decoded
zero tokens -- but that signature does *not* catch this failure, which emits thousands of tokens at
a healthy rate (2048 at 14.9 tok/s, all identical). Detecting extreme repetition is open work.

### Practical conclusion on model choice

Measured end state on a Galaxy S24+, Spark-X2.5-1.7B:

| config | decode | prefill | `:llm` RSS | tools |
|---|---|---|---|---|
| **int4 @ 4096, CPU, 6 threads** | 11.9 tok/s | 67 tok/s | ~1.3 GB | yes |
| int8 @ 4096, GPU | 11.7 tok/s | 111 tok/s | ~3.9 GB | yes, with the right template |
| int4 @ 16384, CPU | 4.1 tok/s | 20 tok/s | ~2.7 GB | yes, but 63s to first token |

int4 on CPU matches int8-on-GPU decode at a third of the memory, which matters because the RSS is
what drives the OOM kills. The context tax and the GPU int4 defect together mean there is no
configuration here that reaches the context an agentic loop wants; 4096-8192 is the usable range.

### Historical measurements (before the workarounds)

Why the two workarounds exist, measured before they were added:

| Model | `supportsFunctionCalling()` | Tool calls emitted when asked directly |
|---|---|---|
| Spark-X2.5-1.7B | false | 0 |
| gemma-4-E2B-it | false | — (preface render fails) |
| Ministral-3-3B | false | — |

### Tool calling (mechanics)

`automaticToolCalling = false`; the round loop lives in `OnDeviceLlmEngine.chatStream`. Tool output
goes back as `Message.tool(Contents.of(Content.ToolResponse(...)))` — **not** as a user message, or
the model reads results as user input. Tools are `OpenApiTool` implementations resolved by the
`name` parsed out of their description JSON, never by substring matching that JSON.

`OnDeviceToolExecutor` registers a broadcast receiver for the Termux tool in its constructor, so
the engine holds exactly one instance for its lifetime. Do not construct it per conversation or per
tool round.

### Foreground service type

`LlmService` uses `specialUse` with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` of
`on_device_llm_inference`. It must **not** go back to `dataSync`: that type is capped at 6
cumulative hours per 24h from Android 15 (which this app targets), is blocked from
`BOOT_COMPLETED`, and is semantically wrong for inference. `specialUse` requires a written
justification at Google Play submission. `Service.onTimeout` is implemented either way — a
foreground service that does not stand down promptly after a timeout gets a fatal
`RemoteServiceException`.

## Memory

Global (not per-conversation) semantic memory. `MemoryEntry.conversationId` is provenance only.

- **Write** is model-driven: the `remember_fact` tool. Nothing is stored automatically.
  `MemorySearchUseCase.remember` skips near-duplicates above 0.95 cosine similarity, so repeated
  mentions of the same fact do not accumulate.
- **Read** is both automatic and explicit: the top matches are injected into each turn, and
  `recall_facts` lets the model search deliberately.
- **Ranking** happens in `MemorySearchUseCase`. `MemoryRepository.getSimilarMemories` is only a
  recency-ordered *candidate* fetch and ignores its query argument.
- `fallbackToRecent` controls behaviour when ranking is impossible. Prompt injection passes
  `false` (unranked entries are noise); `recall_facts` passes `true`.
- Entries written before an embedding model existed carry no vector; `backfillEmbeddings()` fills
  them in, kicked off opportunistically from `ChatViewModel`.

### Where memory runs

Retrieval and the memory tools run in whichever process is doing inference, so the embedding
model is only ever loaded once:

- **Cloud path** — app process. `ToolExecutor` owns the tools; `ChatViewModel.buildApiMessages`
  injects the context as a second system message.
- **On-device path** — `:llm`. `LlmService` is `@AndroidEntryPoint` and passes
  `MemorySearchUseCase` into the engine, which injects context into the *message* rather than the
  system instruction — varying the system instruction would make `needsReinitialize` true every
  turn and reload the model.

Because the database is now open in both processes, `AppDatabase` is built with
`enableMultiInstanceInvalidation()`, and `AIAssistantApp` skips WorkManager init outside the main
process.

Tool schemas are declared once as `OpenApiTool`s in `MemoryTools.kt` and adapted for the API path
by `ToolManager.registerOpenApiTool`, so the schema the model sees cannot drift from the code that
runs. `remember_fact` reads the conversation id from `ActiveConversation` (app process) or the
incoming message's `conversationId` (`:llm`) — tools receive only their own arguments.

### Backends and per-model overrides

Backend is a Settings choice (`OnDeviceLlmSettings.backend`); per-model overrides live in
`<model-name>.json` beside the model:

    { "backend": "gpu", "contextTokens": 4096, "constrainedDecoding": false }

Measured on a Galaxy S24+ (SM8650, Adreno), gemma-4-E2B:

| | load | turn | `:llm` RSS |
|---|---|---|---|
| CPU bundle | ~7s | OOM-killed | 3.19 GB |
| `-gpu` bundle, `Backend.GPU()` | ~9s | **~2s** | **1.57 GB** |

Two things must be right, and each fails differently:

1. **`<uses-native-library android:name="libOpenCL.so">` in the manifest.** LiteRT-LM's GPU path
   is OpenCL, not Vulkan. Since Android 12 a vendor library must be declared or `dlopen` fails
   even though it exists and is in `/vendor/etc/public.libraries.txt`. Without it the error is
   the misleading `FAILED_PRECONDITION: Can not find OpenCL library on this device`.
2. **Only name vision/audio backends the bundle actually has.** Naming one it lacks turns a
   skippable warning into `NOT_FOUND: TF_LITE_AUDIO_ENCODER_HW not found in the model` at
   `createConversation`. The gemma `-gpu` bundle is text-only; its CPU bundle is multi-modal.
   Gate on `caps.supportsVision` / `caps.supportsAudio`.

A backend-specific bundle is **not** required. Spark runs on GPU with its stock `_int8` file;
gemma's dedicated `-gpu` file also works (loading as `GPU_ARTISAN`). Whether a stock bundle has a
GPU-runnable graph is per-model, so treat it as "try it and look at the tokens/sec", not as a rule.

**Take the chat template from the conversion pipeline, not from the model repo.** The vendor
template on `XHToken/Spark-X2.5-1.7B` renders a tool turn as
`'<tool_response>' ~ message.content ~ '</tool_response>'`, which assumes `message.content` is a
string -- true when HF's `apply_chat_template` calls it, false for LiteRT-LM, which passes a list
of content blocks `[{type: tool_response, name, response}]`. The list gets string-concatenated into
the prompt and the model degenerates. `hf-to-litertlm`'s `templates/spark25_tools.jinja` handles
both forms and is the one to use (its `spark25_think.jinja` has no tools block). A bundle converted
with `USE_JINJA=1` already embeds its template, so do **not** drop a `.jinja` sidecar next to such a
bundle -- the sidecar silently overrides the correct embedded template.

**GPU and tools are mutually exclusive on gemma right now.** Tools need the sidecar template, and
the upstream `chat_template.jinja` produces garbage against the Artisan GPU bundle (`<unused3296>`
tokens) -- it matches the CPU bundle's format, not the GPU one. Verified independently of
constrained decoding, which is why `constrainedDecoding` is now an explicit sidecar opt-in rather
than something a template switches on implicitly.

Throughput is measured, not guessed: `ExperimentalFlags.enableBenchmark` is on and
`Conversation.getBenchmarkInfo()` is read after each turn into `GenerationStats`, surfaced under
the reply as tokens/sec. The runtime's counters are per-turn ("last..."), so on a multi-round tool
call they describe the final round only.

The gemma GPU bundle does carry real sampler defaults (`temperature=1.0, topK=64, topP=0.95`) where the
CPU bundles carry none, so `firstPositive` picks them up.

### The engine process gets OOM-killed

The `:llm` process holds a multi-GB model and is a prime low-memory-killer target even as a
foreground service (`Rescheduling restart of crashed service ... for mem-pressure-event` ~26s into
a gemma-4-E2B generation). `START_STICKY` then restarts it with no model.

`LlmClient` registers death handlers and fails in-flight requests when the process dies. Without
them the reply flow never completes and the UI spins forever. Do not remove them.

Generation is always bounded, and the bound is a share of the context rather than a constant:
`outputTokenLimit` caps it at `MAX_OUTPUT_FRACTION` (0.25) of `contextTokens`. A bare 4096 default
against a 4096-token bundle let one answer fill the KV with the prompt still resident.

That allowance is also what `toolResultBudget` reserves, so prompt plus answer fits by
construction -- the two budgets used to contradict each other (0.75 of the context for input plus
0.5 for output). Keep the fraction modest: reserving half of a 4096 context starved a tool loop
from 1500 characters in round 0 to 252 in round 1 to nothing in round 2, for a 71-token answer.

An OOM kill during model load surfaced as "Model initialization timed out" because `sendAndAwait`
resumes with null on process death and `initializeModel` mapped every null to a timeout. It now
passes an `onDied` value, so a kill reads as a kill.

`cpuThreads` in the sidecar sets `Backend.CPU(threadCount = ...)`, which is otherwise null and left
to the runtime. Worth about 25% on a Galaxy S24+ at 6 threads (prefill 54 -> 67 tok/s), by keeping
work off the two efficiency cores.

### Idle timeout

`IDLE_TIMEOUT_MS` means *idle*, not "time since the service started". Model loads and inference
runs bracket themselves with `beginOperation()`/`endOperation()`, and `resetIdleTimer()` refuses to
arm while `activeOperations > 0`. Before this, the timer fired mid-generation and shut the engine
down, and the turn returned empty. `LlmClient.ping()` exists but nothing calls it — do not rely on
it as the keep-alive.

### IPC payloads

Put payloads in a `Bundle` (see `bundled()`), never a bare object in `Message.obj`. A `String` in
`obj` throws "Can't marshal non-Parcelable objects across processes", which silently turned every
`getState()` into a 10-second timeout that dropped the capability report.

### R8

The LiteRT-LM AAR ships no consumer ProGuard rules, and its JNI layer reads Kotlin data-class
fields by name. `proguard-rules.pro` keeps `com.google.ai.edge.litertlm.**` — without it, release
builds fail at inference time rather than at build time.

## Important Notes

- **No PDF text extraction** - no Android-compatible PDF parser on Maven Central
- **No `java.awt`** - PDFBox doesn't work on Android (use server-side processing)
- **Min SDK 26** - no need to support Android 7.x or below
- **R8 enabled in release** - keep rules in `proguard-rules.pro` for LiteRT-LM/Retrofit/Room/Gson model classes
- **Built-in Kotlin** - AGP compiles Kotlin; the `org.jetbrains.kotlin.android` plugin is *not*
  applied and is incompatible with `android.newDsl`. Configure the compiler via the top-level
  `kotlin { compilerOptions { } }` block
- **No AGP 9 opt-out flags** - `gradle.properties` carries no `android.newDsl` /
  `android.builtInKotlin` / R8 opt-outs; those escape hatches are removed in AGP 10
- **Code interpreter sandbox** - `executeJavaScript` uses `Context.initStandardObjects()`, not
  Rhino's shell `Global`, which would expose `readFile`/`runCommand`/`spawn` to model-written JS
- **Gradle wrapper**: Use `.\gradlew.bat` on Windows
- **Build cache**: Gradle daemon is used, clean build needed after dependency changes

## File Locations

```
app/src/main/java/com/aiassistant/
├── AIAssistantApp.kt          # @HiltAndroidApp
├── MainActivity.kt            # Entry point, EdgeToEdge
├── di/
│   ├── AppModule.kt
│   └── RepositoryBindingModule.kt
├── data/
│   ├── api/
│   │   ├── OpenAIService.kt
│   │   └── RetrofitClient.kt
│   ├── database/
│   │   ├── AppDatabase.kt
│   │   ├── ConversationDao.kt
│   │   ├── MessageDao.kt
│   │   └── MemoryDao.kt
│   ├── model/
│   │   ├── api/
│   │   │   ├── ChatCompletionRequest.kt
│   │   │   ├── ChatCompletionResponse.kt
│   │   │   ├── EmbeddingResponse.kt
│   │   │   └── StreamingResponse.kt
│   │   ├── ConversationEntity.kt
│   │   ├── MemoryEntryEntity.kt
│   │   └── MessageEntity.kt
│   └── repository/
│       ├── ChatRepository.kt
│       ├── ConversationRepositoryImpl.kt
│       ├── MessageRepositoryImpl.kt
│       ├── MemoryRepositoryImpl.kt
│       ├── SettingsDataRepository.kt
│       └── SettingsRepository.kt
├── domain/
│   ├── model/
│   │   ├── ChatMessage.kt
│   │   ├── Conversation.kt
│   │   └── MemoryEntry.kt
│   ├── repository/
│   │   ├── ChatApiRepository.kt
│   │   ├── ChatApiRepositoryImpl.kt
│   │   ├── ConversationRepository.kt
│   │   ├── MemoryRepository.kt
│   │   └── MessageRepository.kt
│   ├── service/
│   │   ├── ToolManager.kt
│   │   └── VectorMathService.kt
│   ├── tool/
│   │   ├── CalculatorTool.kt
│   │   ├── CodeInterpreterTool.kt
│   │   ├── DeviceInfoTool.kt
│   │   ├── ToolExecutor.kt
│   │   ├── WebPageFetcherTool.kt
│   │   ├── WebSearchTool.kt
│   │   └── WeatherTool.kt
│   └── usecase/
│       ├── MemorySearchUseCase.kt
│       └── SendChatMessageUseCase.kt
├── presentation/
│   ├── navigation/
│   │   └── AppNavigation.kt
│   ├── screen/
│   │   ├── chat/
│   │   │   ├── ChatScreen.kt
│   │   │   └── MarkdownText.kt
│   │   └── settings/
│   │       └── SettingsScreen.kt
│   └── vm/
│       ├── ChatViewModel.kt
│       └── SettingsViewModel.kt
└── ui/
    └── theme/
        ├── Theme.kt
        └── Typography.kt
```
