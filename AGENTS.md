# AI Assistant Android - Agent Guidelines

## Project Overview

**Synapse** — Modern Android AI assistant app connecting to any OpenAI-compatible API. Built with Kotlin, Jetpack Compose, and Clean Architecture.

**Package**: `com.aiassistant`
**Min SDK**: 26 (Android 8.0)
**Target SDK**: 35
**Compile SDK**: 35
**Java Target**: 17

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
- Uses `commonmark 0.21.0`
- Custom `MarkdownText.kt` with `AnnotatedString` for bold/italic/code formatting
- Supports: headings, bold, italic, inline code, code blocks, lists, blockquotes, horizontal rules
- No `LazyColumn` in renderer (nested scroll crash)

## Dependencies

| Library | Version | Usage |
|---------|---------|-------|
| Compose BOM | 2024.12.01 | UI framework |
| Hilt | 2.55 | Dependency injection |
| Room | 2.6.1 | Local database |
| Retrofit | 2.11.0 | HTTP client |
| OkHttp | 4.12.0 | HTTP client |
| Gson | 2.12.1 | JSON serialization |
| Coil | 2.7.0 | Image loading |
| Commonmark | 0.21.0 | Markdown parsing |
| Jsoup | 1.18.3 | Web scraping |
| Mozilla Rhino | 1.7.15 | JavaScript engine |
| DataStore | 1.1.2 | Preferences storage |
| Accompanist | 0.36.0 | Permissions |

## Important Notes

- **No PDF text extraction** - no Android-compatible PDF parser on Maven Central
- **No `java.awt`** - PDFBox doesn't work on Android (use server-side processing)
- **Min SDK 26** - no need to support Android 7.x or below
- **R8 enabled in release** - keep rules in `proguard-rules.pro` for Retrofit/Room/model classes
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
