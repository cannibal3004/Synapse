package com.aiassistant.presentation.screen.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aiassistant.domain.llm.OnDeviceLlmSettings
import com.aiassistant.presentation.vm.SettingsViewModel

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
    var showSaveToast by remember { mutableStateOf(false) }

    var onDeviceEnabled by remember { mutableStateOf(settings.onDeviceSettings?.enabled ?: false) }
    var onDeviceModelName by remember { mutableStateOf(settings.onDeviceSettings?.modelName ?: "gemma-4-E2B-it.litertlm") }
    var onDeviceHuggingfaceRepo by remember { mutableStateOf(settings.onDeviceSettings?.huggingfaceRepo ?: "litert-community/gemma-4-E2B-it-litert-lm") }
    var onDeviceSystemPrompt by remember { mutableStateOf(settings.onDeviceSettings?.systemPrompt ?: "") }

    LaunchedEffect(settings) {
        apiKey = settings.apiKey ?: ""
        apiBaseUrl = settings.apiBaseUrl ?: "https://api.openai.com/"
        defaultModel = settings.defaultModel ?: ""
        systemPrompt = settings.systemPrompt ?: ""
        embeddingModel = settings.embeddingModel ?: "text-embedding-3-small"
        exaApiKey = settings.exaApiKey ?: ""
        settings.onDeviceSettings?.let { ods ->
            onDeviceEnabled = ods.enabled
            onDeviceModelName = ods.modelName
            onDeviceHuggingfaceRepo = ods.huggingfaceRepo
            onDeviceSystemPrompt = ods.systemPrompt ?: ""
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
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
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

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    viewModel.saveSettings(
                        apiKey = if (apiKey.isEmpty()) null else apiKey,
                        apiBaseUrl = if (apiBaseUrl.isEmpty()) null else apiBaseUrl,
                        defaultModel = if (defaultModel.isEmpty()) null else defaultModel,
                        systemPrompt = if (systemPrompt.isEmpty()) null else systemPrompt,
                        embeddingModel = if (embeddingModel.isEmpty()) null else embeddingModel,
                        exaApiKey = if (exaApiKey.isEmpty()) null else exaApiKey
                    )

                    viewModel.saveOnDeviceSettings(
                        OnDeviceLlmSettings(
                            enabled = onDeviceEnabled,
                            modelName = onDeviceModelName,
                            huggingfaceRepo = onDeviceHuggingfaceRepo,
                            systemPrompt = if (onDeviceSystemPrompt.isEmpty()) null else onDeviceSystemPrompt
                        )
                    )
                    showSaveToast = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Settings")
            }

            if (showSaveToast) {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(2000)
                    showSaveToast = false
                }
                Snackbar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    action = {
                        TextButton(onClick = { showSaveToast = false }) {
                            Text("Dismiss")
                        }
                    }
                ) {
                    Text("Settings saved successfully!")
                }
            }
        }
    }
}
