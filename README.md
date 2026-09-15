<img src="Synapse.png" width="96" align="right" alt="Synapse" />

# Synapse

An Android AI assistant built with Kotlin and Jetpack Compose. It talks to any OpenAI-compatible
API, or runs a model entirely on the phone with no network and no API key. Either way it gets the
same set of tools: a shell, the web, your calendar and messages, and the ability to schedule work
for itself and report back later.

## Features

- **Hosted or on-device** — any OpenAI-compatible endpoint (OpenAI, Azure, Ollama, LM Studio,
  llama.cpp), or a local `.litertlm` model through LiteRT-LM with CPU, GPU, NPU or Google Tensor
  backends. On-device inference runs in a separate `:llm` process, so a model that runs out of
  memory takes that process down and not the UI.
- **Streaming replies** — tokens arrive as they are generated. Navigating away mid-reply asks before
  abandoning it.
- **Tools** — eleven of them, listed below. Tool calls appear in the transcript as pills while they
  run and stay there afterwards, so a turn can be read back.
- **Scheduled tasks** — prompts that run on their own later, once, on an interval, or on a cron
  expression, with the result delivered as a notification. The assistant can create and manage them
  itself.
- **Speech** — dictation and spoken replies through the platform engines, so nothing is sent
  anywhere extra. Tap the mic for one utterance, hold it to dictate for as long as you keep talking.
  Speaking over a reply interrupts it.
- **Memory** — durable facts stored and recalled by embedding similarity, using either the API's
  embedding model or a local one.
- **Attachments** — images and documents, from the gallery, the camera, or the file picker.
- **Conversations** — created, searched, deleted and switched between.
- **Adaptive layout** — a single-pane phone UI that becomes a sidebar layout at 840dp and up, which
  is what makes it usable in Samsung DeX and on a tablet.
- **Markdown rendering** — headings, lists, tables, code blocks and inline formatting.

## Tools

| Tool | What it does |
| --- | --- |
| `termux_shell` | Runs Linux commands on-device through Termux. The general-purpose escape hatch — networking, files, `python`, `jq`, anything installed. |
| `web_search` | Exa search with result counts, neural or keyword mode, category and domain filters, date ranges, and page text, highlights or summaries. |
| `web_fetch` | Fetches a page and extracts its readable text. |
| `weather` | Current conditions and forecast from Open-Meteo. No key needed. |
| `calendar` | Reads events and creates new ones. |
| `sms` | Reads recent messages and sends new ones. |
| `save_file` | Writes a file to Downloads and returns it; the transcript shows it as a chip that opens it. |
| `manage_tasks` | Lists, creates, updates, deletes and runs scheduled tasks. |
| `remember_fact` / `recall_facts` | Stores and retrieves durable facts about the user. |
| `device_info` | Battery, storage, network and device details. |

There is no calculator and no sandboxed code interpreter — `termux_shell` covers both, with a real
Python and a real shell behind it rather than an approximation.

## Setup

### Prerequisites

- Android Studio with AGP 9.4 support
- JDK 17
- Android SDK 37 (`compileSdk`); the app targets 35 and runs on 26 and up

### Build

```bash
.\gradlew.bat :app:assembleDebug     # debug build
.\gradlew.bat :app:assembleRelease   # release build
.\gradlew.bat :app:lintDebug         # lint
.\gradlew.bat test                   # unit tests
```

Versions live in `gradle/libs.versions.toml`, not inline in the build files.

### API configuration

Settings → **API Configuration**:

- **API Key** — your provider's key
- **Base URL** — `https://api.openai.com/` by default; `http://localhost:11434/v1/` for Ollama,
  `http://localhost:1234/v1/` for LM Studio
- **Default Model** — e.g. `gpt-4o`, `llama3`
- **System Prompt** — standing instructions
- **Embedding Model** — e.g. `text-embedding-3-small`, used for memory search
- **Exa API Key** — for `web_search`
- **Max Tool Rounds** — how many times the model may call tools before it has to answer

### On-device models

Settings → **On-Device LLM** → *Enable On-Device Mode*. The model is downloaded from HuggingFace on
first use; the default is `gemma-4-E2B-it` from `litert-community`. Budget 2–6GB of storage and a
device with 8GB or more of RAM.

Pick the **compute backend** to match the bundle you are using — a `-gpu` bundle for GPU, a
`_Google_Tensor_*` bundle for Pixel, and so on. Selecting GPU against a CPU-built bundle fails, and
the failure is not obvious. Temperature, top-k, top-p, thinking budget, max output tokens and the KV
context size are all adjustable.

**On-Device Embeddings** is a separate switch: it runs memory search locally with its own ~300MB
model, so memory works with no API key either.

To sideload a bundle you already have rather than downloading one:

```bash
./tools/sideload.sh /path/to/model.litertlm [dest-name.litertlm]
./tools/watch.sh            # tail the engine logs during first init
./tools/watch.sh mem        # RSS of the :llm process
```

### Termux shell

The shell tool needs Termux installed and configured to accept commands from other apps.

1. Install **Termux** from [F-Droid](https://f-droid.org/packages/com.termux/) or
   [GitHub Releases](https://github.com/termux/termux-app/releases) — the Play Store build is
   deprecated and will not work.
2. Run `termux-setup-storage`.
3. Add `allow-external-apps = true` to `~/.termux/termux.properties`.
4. Restart Termux completely — close it from the notification, not just the back button.
5. In Synapse, Settings → **Termux** → enable *Termux Shell*, then grant the permission when asked.
   If Android has stopped asking, use **Open App Settings** → Additional permissions → *Run commands
   in Termux environment*.

Commands time out after 30s (configurable to 120s). Interactive programs like `vim` will hang, and
long-running daemons are not supported.

### Phone access

Settings → **Phone access** grants the calendar and SMS permissions the `calendar` and `sms` tools
need. Android stops prompting after a permission is refused twice; the card links straight to the
system settings page when that happens.

## Architecture

Clean Architecture, three layers, with a separate process for inference.

**Presentation** (`presentation/`) — Compose screens for chat, the conversation list, tasks and
settings; `ChatViewModel`, `ConversationListViewModel`, `TaskViewModel`, `SettingsViewModel`,
`ShellViewModel`; `AppNavigation` switching between a compact and a sidebar layout on width.

**Domain** (`domain/`) — models, repository interfaces, the tool implementations, `ToolManager`,
`VectorMathService`, the LiteRT-LM engine wrappers, `Speaker` and `Dictation`, and the use cases
(`SendChatMessageUseCase`, `MemorySearchUseCase`, `TaskExecutor`, `CronScheduler`).

**Data** (`data/`) — Retrofit client, Room database, DataStore settings, the WorkManager scheduler
and worker, and notifications.

**Inference** (`service/`, `client/`) — `LlmService` runs in `:llm` with `LlmIpc` and `LlmClient`
either side of the process boundary.

```
app/src/main/java/com/aiassistant/
├── AIAssistantApp.kt          # Application class (Hilt), launch-time task reconciliation
├── MainActivity.kt
├── client/LlmClient.kt        # main-process side of the :llm boundary
├── service/                   # LlmService, LlmIpc — the :llm process
├── di/                        # Hilt modules
├── data/
│   ├── api/                   # Retrofit, streaming chat completions
│   ├── database/              # Room: conversations, messages, memory, tasks
│   ├── llm/                   # on-device settings
│   ├── notification/
│   ├── repository/
│   ├── scheduler/             # TaskScheduler (WorkManager)
│   └── worker/                # TaskWorker
├── domain/
│   ├── llm/                   # LiteRT-LM engines, backends, settings
│   ├── model/
│   ├── repository/
│   ├── service/
│   ├── speech/                # Dictation, Speaker
│   ├── tool/
│   └── usecase/
├── presentation/
│   ├── component/
│   ├── navigation/
│   ├── screen/                # chat, conversation, settings, tasks
│   └── vm/
└── ui/theme/
```

## Dependencies

- **UI** — Jetpack Compose (BOM), Material 3, Navigation Compose, Coil, Commonmark
- **DI** — Hilt/Dagger
- **Network** — Retrofit, OkHttp, Gson, Jsoup
- **Persistence** — Room, DataStore
- **Background** — WorkManager
- **On-device inference** — LiteRT-LM
- **Async** — Coroutines, Flow
- **Permissions** — Accompanist

## License

MIT
