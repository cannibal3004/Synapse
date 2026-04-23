# Synapse

A modern Android AI assistant app built with Kotlin and Jetpack Compose that connects to any OpenAI-compatible API server.

## Features

- **OpenAI-Compatible API**: Connect to any OpenAI-compatible API server (OpenAI, Azure, local models like Ollama, LM Studio, etc.)
- **Tool Calling**: The AI can call tools like web search, code interpreter, calculator, weather, and more
- **Termux Shell**: Execute Linux commands on-device via Termux (ping, curl, file operations, Python scripts, etc.)
- **Web Search**: Built-in web search tool using Exa.ai
- **Vector Memory**: Embedding-based memory system with similarity search
- **Modern UI**: Clean Material Design 3 interface with Jetpack Compose
- **Markdown Rendering**: Formatted responses with bold, italic, code blocks, lists, and more
- **Conversation Management**: Create, manage, and switch between conversations
- **File & Image Attachments**: Attach images and documents to messages
- **Configurable**: Set your API key, base URL, model, and system prompt

## Architecture

The app follows Clean Architecture principles with three layers:

### Presentation Layer (`presentation/`)
- **Screens**: ChatScreen, SettingsScreen
- **ViewModel**: ChatViewModel for state management
- **Navigation**: AppNavigation with NavHost
- **Theme**: SynapseTheme with Material 3

### Domain Layer (`domain/`)
- **Models**: Conversation, ChatMessage, MemoryEntry, Attachment
- **Repositories**: Interfaces for data access
- **Use Cases**: SendChatMessageUseCase, MemorySearchUseCase
- **Services**: ToolManager, VectorMathService
- **Tools**: Calculator, Code Interpreter, Web Search, Weather, Device Info, Web Page Fetcher, Termux Shell

### Data Layer (`data/`)
- **API**: Retrofit client for OpenAI-compatible endpoints
- **Database**: Room database with conversations, messages, and memory
- **Repositories**: Implementations of domain repository interfaces
- **Models**: Entity classes for Room, API models for Retrofit

## Setup

### Prerequisites
- Android Studio Hedgehog or later
- JDK 17
- Android SDK 35 (API 35)

### Configuration
1. Open the project in Android Studio
2. Sync Gradle files
3. Run on an emulator or physical device

### API Configuration
Go to Settings to configure:
- **API Key**: Your OpenAI API key (sk-...)
- **Base URL**: Default is `https://api.openai.com/`
  - For local models: `http://localhost:11434/v1/` (Ollama)
  - For LM Studio: `http://localhost:1234/v1/`
- **Default Model**: e.g., `gpt-3.5-turbo`, `gpt-4`, `llama3`
- **System Prompt**: Custom instructions for the AI
- **Embedding Model**: e.g., `text-embedding-3-small`
- **Exa API Key**: For web search (optional)

### Termux Shell Setup (Optional)

The app can execute Linux commands on your device via Termux. This enables network diagnostics (ping, curl, wget, nslookup), file operations, system info, text processing, and running Python/Node scripts.

**Setup:**

1. **Install Termux** from [F-Droid](https://f-droid.org/packages/com.termux/) or [GitHub Releases](https://github.com/termux/termux-app/releases) (Play Store version is deprecated)
2. **Open Termux** and run:
   ```bash
   termux-setup-storage
   ```
3. **Enable external apps** — create/edit `~/.termux/termux.properties`:
   ```bash
   nano ~/.termux/termux.properties
   ```
   Add the line:
   ```
   allow-external-apps = true
   ```
4. **Restart Termux** — close and reopen the app
5. **Grant permission** — open Synapse Settings, tap "Open App Settings", go to Additional permissions, and enable "Run commands in Termux environment"

**Usage:**

Ask the AI to run commands naturally:
- "Ping 8.8.8.8 four times"
- "Check my network connectivity with curl"
- "List files in my home directory"
- "Run a Python script to calculate fibonacci"
- "Check disk usage with df -h"

**Limitations:**

- Commands timeout after 30s (configurable up to 120s)
- Interactive commands (e.g., `vim`, `nano`) will hang
- Long-running daemons are not supported
- Requires Termux to be installed and configured

## Project Structure

```
app/src/main/java/com/aiassistant/
├── AIAssistantApp.kt          # Application class (Hilt)
├── MainActivity.kt            # Entry point
├── di/
│   ├── AppModule.kt           # Dependency injection
│   └── RepositoryBindingModule.kt
├── data/
│   ├── api/                   # Retrofit client
│   ├── database/              # Room database
│   ├── model/                 # Data models
│   └── repository/            # Data repositories
├── domain/
│   ├── model/                 # Domain models
│   ├── repository/            # Repository interfaces
│   ├── service/               # Services (tools, vectors)
│   ├── tool/                  # Tool implementations
│   └── usecase/               # Use cases
├── presentation/
│   ├── navigation/            # Navigation setup
│   ├── screen/                # UI screens
│   └── vm/                    # ViewModels
└── ui/
    └── theme/                 # Compose theme
```

## Dependencies

- **UI**: Jetpack Compose, Material Design 3, Navigation Compose
- **DI**: Hilt/Dagger
- **Network**: Retrofit, OkHttp, Gson
- **Database**: Room, DataStore
- **Async**: Kotlin Coroutines, Flow
- **Images**: Coil
- **Markdown**: Commonmark
- **Web Scraping**: Jsoup
- **JS Engine**: Mozilla Rhino

## Future Enhancements

- [ ] Streaming responses with SSE
- [ ] Image generation support
- [ ] Voice input/output
- [ ] PDF text extraction
- [ ] Multiple AI providers
- [ ] Conversation sharing
- [ ] Dark/Light theme toggle
- [ ] On-device embedding models
- [ ] Conversation search

## License

MIT License
