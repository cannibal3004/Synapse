package com.aiassistant.presentation.screen.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.aiassistant.domain.llm.LlmBackend
import com.aiassistant.domain.llm.OnDeviceEmbeddingEngine
import com.aiassistant.domain.llm.OnDeviceLlmSettings
import com.aiassistant.presentation.vm.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onToggleDrawer: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    var apiKey by remember { mutableStateOf(settings.apiKey ?: "") }
    var apiBaseUrl by remember { mutableStateOf(settings.apiBaseUrl ?: "https://api.openai.com/") }
    var defaultModel by remember { mutableStateOf(settings.defaultModel ?: "") }
    var systemPrompt by remember { mutableStateOf(settings.systemPrompt ?: "") }
    var embeddingModel by remember { mutableStateOf(settings.embeddingModel ?: "text-embedding-3-small") }
    var exaApiKey by remember { mutableStateOf(settings.exaApiKey ?: "") }
    var maxToolRounds by remember { mutableStateOf(settings.maxToolRounds.toString()) }

    // The confirmation used to be a Snackbar composed inline at the end of the scrolling
    // column, below the save button, so it only existed off-screen: you saved, nothing
    // appeared, and it had already timed out by the time you scrolled to where it was.
    // The Scaffold's host floats it over the content instead.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var onDeviceEnabled by remember { mutableStateOf(settings.onDeviceSettings?.enabled ?: false) }
    var onDeviceModelName by remember { mutableStateOf(settings.onDeviceSettings?.modelName ?: "gemma-4-E2B-it.litertlm") }
    var onDeviceHuggingfaceRepo by remember { mutableStateOf(settings.onDeviceSettings?.huggingfaceRepo ?: "litert-community/gemma-4-E2B-it-litert-lm") }
    var onDeviceSystemPrompt by remember { mutableStateOf(settings.onDeviceSettings?.systemPrompt ?: "") }
    var onDeviceThinking by remember { mutableStateOf(settings.onDeviceSettings?.enableThinking ?: false) }
    var onDeviceBackend by remember {
        mutableStateOf(settings.onDeviceSettings?.backend ?: LlmBackend.CPU)
    }
    var onDeviceContextTokens by remember {
        mutableStateOf(settings.onDeviceSettings?.contextTokens?.toString() ?: "")
    }
    var onDeviceEmbeddings by remember {
        mutableStateOf(settings.onDeviceSettings?.onDeviceEmbeddingsEnabled ?: false)
    }
    var onDeviceEmbeddingModel by remember {
        mutableStateOf(
            settings.onDeviceSettings?.embeddingModelName
                ?: OnDeviceEmbeddingEngine.DEFAULT_EMBEDDING_MODEL_NAME
        )
    }
    var onDeviceEmbeddingRepo by remember {
        mutableStateOf(
            settings.onDeviceSettings?.embeddingHuggingfaceRepo
                ?: OnDeviceEmbeddingEngine.DEFAULT_EMBEDDING_REPO
        )
    }

    LaunchedEffect(settings) {
        apiKey = settings.apiKey ?: ""
        apiBaseUrl = settings.apiBaseUrl ?: "https://api.openai.com/"
        defaultModel = settings.defaultModel ?: ""
        systemPrompt = settings.systemPrompt ?: ""
        embeddingModel = settings.embeddingModel ?: "text-embedding-3-small"
        exaApiKey = settings.exaApiKey ?: ""
        maxToolRounds = settings.maxToolRounds.toString()
        settings.onDeviceSettings?.let { ods ->
            onDeviceEnabled = ods.enabled
            onDeviceModelName = ods.modelName
            onDeviceHuggingfaceRepo = ods.huggingfaceRepo
            onDeviceSystemPrompt = ods.systemPrompt ?: ""
            onDeviceThinking = ods.enableThinking
            onDeviceBackend = ods.backend
            onDeviceContextTokens = ods.contextTokens?.toString() ?: ""
            onDeviceEmbeddings = ods.onDeviceEmbeddingsEnabled
            onDeviceEmbeddingModel = ods.embeddingModelName
            onDeviceEmbeddingRepo = ods.embeddingHuggingfaceRepo
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    Row {
                        IconButton(onClick = onToggleDrawer) {
                            Icon(Icons.Default.Menu, "Menu")
                        }
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                }
            )
        },
        snackbarHost = {
            // imePadding so the confirmation clears an open keyboard. Saving drops focus, but
            // the keyboard animates out over a couple of frames and the snackbar is instant.
            SnackbarHost(snackbarHostState, modifier = Modifier.imePadding())
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Top from the Scaffold; the bottom resolved against the keyboard the same way
                // the chat input does it. safeDrawing takes whichever of the navigation bar and
                // the IME is larger, and applying it *outside* verticalScroll is the part that
                // matters: it shrinks the scroll viewport rather than padding the content, so a
                // field taking focus scrolls itself above the keyboard instead of being buried
                // under it. Pairs with android:windowSoftInputMode="adjustResize".
                .padding(top = paddingValues.calculateTopPadding())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "API Configuration",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                placeholder = { Text("sk-...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = apiBaseUrl,
                onValueChange = { apiBaseUrl = it },
                label = { Text("API Base URL") },
                placeholder = { Text("https://api.openai.com/") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Chat Settings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = defaultModel,
                onValueChange = { defaultModel = it },
                label = { Text("Default Model") },
                placeholder = { Text("e.g., gpt-4, llama3, mistral") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("System Prompt") },
                placeholder = { Text("You are a helpful assistant.") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Memory Settings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = embeddingModel,
                onValueChange = { embeddingModel = it },
                label = { Text("Embedding Model") },
                placeholder = { Text("text-embedding-3-small") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Search Settings",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = exaApiKey,
                onValueChange = { exaApiKey = it },
                label = { Text("Exa API Key") },
                placeholder = { Text("exa-api-...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = maxToolRounds,
                onValueChange = { maxToolRounds = it.filter(Char::isDigit) },
                label = { Text("Max Tool Rounds") },
                placeholder = { Text("10") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Text(
                text = "How many times the model may call tools before it has to answer. " +
                    "Applies to hosted models, on-device and scheduled tasks. On-device runs " +
                    "usually stop earlier anyway, when the context fills.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "On-Device LLM",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Enable On-Device Mode",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = onDeviceEnabled,
                    onCheckedChange = { onDeviceEnabled = it }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Model will be downloaded from HuggingFace (~2-6GB). Requires device with 8GB+ RAM. Runs fully offline with no API key needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = onDeviceModelName,
                onValueChange = { onDeviceModelName = it },
                label = { Text("Model Name") },
                placeholder = { Text("gemma-4-E2B-it.litertlm") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = onDeviceHuggingfaceRepo,
                onValueChange = { onDeviceHuggingfaceRepo = it },
                label = { Text("HuggingFace Repo") },
                placeholder = { Text("litert-community/gemma-4-E2B-it-litert-lm") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = onDeviceSystemPrompt,
                onValueChange = { onDeviceSystemPrompt = it },
                label = { Text("On-Device System Prompt") },
                placeholder = { Text("You are a helpful AI assistant running on device.") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Compute Backend",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "A backend only works if the model bundle was built for it \u2014 e.g. gemma " +
                    "publishes a separate -gpu file. Falls back with an error if unsupported.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                LlmBackend.entries.forEach { backend ->
                    FilterChip(
                        selected = onDeviceBackend == backend,
                        onClick = { onDeviceBackend = backend },
                        label = {
                            Text(
                                when (backend) {
                                    LlmBackend.CPU -> "CPU"
                                    LlmBackend.GPU -> "GPU"
                                    LlmBackend.NPU -> "NPU"
                                    LlmBackend.GOOGLE_TENSOR -> "Tensor"
                                }
                            )
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = onDeviceContextTokens,
                onValueChange = { onDeviceContextTokens = it.filter(Char::isDigit) },
                label = { Text("Context Tokens (KV budget)") },
                placeholder = { Text("16384") },
                supportingText = {
                    Text("Larger means more RAM. Lower it if the model keeps being killed.")
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Enable Thinking",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = onDeviceThinking,
                    onCheckedChange = { onDeviceThinking = it }
                )
            }

            Text(
                text = "Lets reasoning models think before answering. Ignored automatically when the loaded model does not support it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "On-Device Embeddings",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = onDeviceEmbeddings,
                    onCheckedChange = { onDeviceEmbeddings = it }
                )
            }

            Text(
                text = "Runs memory search locally with a separate embedding model (~300MB), downloaded on first use.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )

            if (onDeviceEmbeddings) {
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = onDeviceEmbeddingModel,
                    onValueChange = { onDeviceEmbeddingModel = it },
                    label = { Text("Embedding Model Name") },
                    placeholder = { Text(OnDeviceEmbeddingEngine.DEFAULT_EMBEDDING_MODEL_NAME) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = onDeviceEmbeddingRepo,
                    onValueChange = { onDeviceEmbeddingRepo = it },
                    label = { Text("Embedding HuggingFace Repo") },
                    placeholder = { Text(OnDeviceEmbeddingEngine.DEFAULT_EMBEDDING_REPO) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            val termuxStatus by viewModel.termuxStatus.collectAsState()
            val context = LocalContext.current
            val settingsLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { }

            Text(
                text = "Termux",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Termux Shell",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = if (termuxStatus.ready) "Ready"
                                   else if (termuxStatus.installed) "Permission needed"
                                   else "Not installed",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (termuxStatus.ready) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (!termuxStatus.installed) {
                            "Termux is not installed. Install from F-Droid or GitHub."
                        } else if (!termuxStatus.permissionGranted) {
                            "Grant RUN_COMMAND permission in Additional permissions. Also set allow-external-apps = true in ~/.termux/termux.properties"
                        } else {
                            "Shell commands and scripts can be executed via Termux."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            settingsLauncher.launch(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, null, modifier = Modifier.padding(end = 8.dp))
                        Text("Open App Settings")
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    viewModel.saveSettings(
                        apiKey = if (apiKey.isEmpty()) null else apiKey,
                        apiBaseUrl = if (apiBaseUrl.isEmpty()) null else apiBaseUrl,
                        defaultModel = if (defaultModel.isEmpty()) null else defaultModel,
                        systemPrompt = if (systemPrompt.isEmpty()) null else systemPrompt,
                        embeddingModel = if (embeddingModel.isEmpty()) null else embeddingModel,
                        exaApiKey = if (exaApiKey.isEmpty()) null else exaApiKey,
                        maxToolRounds = maxToolRounds.toIntOrNull()
                    )

                    // copy() rather than a fresh instance: sampler settings are not edited on
                    // this screen and must survive a save.
                    viewModel.saveOnDeviceSettings(
                        (settings.onDeviceSettings ?: OnDeviceLlmSettings()).copy(
                            enabled = onDeviceEnabled,
                            modelName = onDeviceModelName,
                            huggingfaceRepo = onDeviceHuggingfaceRepo,
                            systemPrompt = if (onDeviceSystemPrompt.isEmpty()) null else onDeviceSystemPrompt,
                            enableThinking = onDeviceThinking,
                            backend = onDeviceBackend,
                            contextTokens = onDeviceContextTokens.toIntOrNull(),
                            onDeviceEmbeddingsEnabled = onDeviceEmbeddings,
                            embeddingModelName = onDeviceEmbeddingModel,
                            embeddingHuggingfaceRepo = onDeviceEmbeddingRepo
                        )
                    )

                    // Nothing left to type, and it gets the keyboard out of the way of the
                    // confirmation.
                    focusManager.clearFocus()
                    scope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(
                            message = "Settings saved",
                            withDismissAction = true,
                            duration = SnackbarDuration.Short
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Settings")
            }

            // Room to scroll the save button clear of the snackbar that covers it.
            Spacer(modifier = Modifier.height(72.dp))
        }
    }
}
