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

`CONTEXT_PRESSURE_FRACTION` is a fraction of the *effective* capacity. Do **not** re-anchor it to the `context_length` a bundle's manifest quotes -- those are
conversion choices, not model limits. Spark's manifest says 4096; the base model's
`max_position_embeddings` is 1048576, and with 3-sliding-to-1-full layers (window 512) only 7 of
its 28 layers carry full-attention KV, so long context is cheap for it.

The binding constraint is RAM: KV size is a direct input to the OOM kills, and `MAX_NUM_TOKENS`
(16384) is a single hardcoded figure applied to every model regardless of its weight size. It is
the obvious next thing to make per-model.

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

Generation is also always bounded (`maxOutputToken` defaults to 4096). Left unset it runs to EOS
or the KV limit, which on CPU is tens of minutes.

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
