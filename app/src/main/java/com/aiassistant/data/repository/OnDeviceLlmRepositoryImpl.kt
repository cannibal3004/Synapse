package com.aiassistant.data.repository

import android.content.Context
import android.util.Log
import com.aiassistant.data.notification.NotificationHelper
import com.aiassistant.domain.llm.OnDeviceLlmEngine
import com.aiassistant.domain.model.ChatMessage
import com.aiassistant.domain.repository.OnDeviceLlmRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnDeviceLlmRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationHelper: NotificationHelper
) : OnDeviceLlmRepository {

    private val _state = MutableStateFlow(OnDeviceLlmEngine.EngineState())
    override val state: StateFlow<OnDeviceLlmEngine.EngineState> = _state.asStateFlow()

    private val engine = OnDeviceLlmEngine(context)
    private val modelDir: File by lazy {
        File(context.filesDir, OnDeviceLlmEngine.MODEL_DIR).apply { mkdirs() }
    }

    override suspend fun initializeModel(
        modelPath: String,
        systemPrompt: String?,
        temperature: Float?,
        topK: Int?,
        topP: Float?
    ): Result<Unit> = engine.initializeModel(
        modelPath = modelPath,
        systemPrompt = systemPrompt,
        temperature = temperature,
        topK = topK,
        topP = topP
    ).onSuccess {
        _state.value = OnDeviceLlmEngine.EngineState(
            isReady = true,
            isLoading = false,
            modelPath = modelPath,
            error = null
        )
        Log.d("OnDeviceLlmRepository", "Model initialized: $modelPath")
    }.onFailure {
        _state.value = OnDeviceLlmEngine.EngineState(
            isReady = false,
            isLoading = false,
            modelPath = modelPath,
            error = it.message
        )
        Log.e("OnDeviceLlmRepository", "Model initialization failed", it)
    }

    override fun chatStream(messages: List<ChatMessage>) = engine.chatStream(messages)

    override suspend fun chat(messages: List<ChatMessage>): Result<String> = engine.chat(messages, null)

    override suspend fun downloadModel(
        huggingfaceRepo: String,
        modelName: String,
        onProgress: (Float) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val modelFile = getModelFile(modelName)
            if (modelFile.exists() && modelFile.length() > 0) {
                Log.d("OnDeviceLlmRepository", "Model already exists: ${modelFile.absolutePath}")
                _state.value = _state.value.copy(isLoading = false)
                return@withContext Result.success(modelFile.absolutePath)
            }

            _state.value = _state.value.copy(isLoading = true)
            notificationHelper.showDownloadNotification(modelName)

            val downloadUrl = buildDownloadUrl(huggingfaceRepo, modelName)
            Log.d("OnDeviceLlmRepository", "Downloading model from: $downloadUrl")

            val client = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder().url(downloadUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Download failed: ${response.code}"
                )
                notificationHelper.cancelDownloadNotification()
                return@withContext Result.failure(IllegalStateException("Download failed: ${response.code}"))
            }

            val totalSize = response.body?.contentLength() ?: -1
            val inputStream = response.body?.byteStream() ?: run {
                _state.value = _state.value.copy(isLoading = false)
                notificationHelper.cancelDownloadNotification()
                return@withContext Result.failure(IllegalStateException("No input stream"))
            }

            modelFile.outputStream().use { output ->
                var downloaded = 0L
                val buffer = ByteArray(8192)
                var lastProgress = 0f

                while (inputStream.read(buffer).also { bytesRead ->
                        if (bytesRead == -1) return@use
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                    } != -1) {
                    if (totalSize > 0) {
                        val progress = (downloaded.toFloat() / totalSize.toFloat()) * 100f
                        if (progress - lastProgress >= 1.0f) {
                            lastProgress = progress
                            onProgress(progress)
                            notificationHelper.updateDownloadNotification(progress, modelName)
                        }
                    }
                }
            }

            response.close()

            if (!modelFile.exists() || modelFile.length() == 0L) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Downloaded file is empty"
                )
                notificationHelper.cancelDownloadNotification()
                return@withContext Result.failure(IllegalStateException("Downloaded file is empty"))
            }

            notificationHelper.updateDownloadNotification(100f, modelName)
            Log.d("OnDeviceLlmRepository", "Model downloaded successfully: ${modelFile.absolutePath} (${modelFile.length() / 1024 / 1024} MB)")
            _state.value = _state.value.copy(
                isLoading = false,
                modelPath = modelFile.absolutePath
            )
            Result.success(modelFile.absolutePath)
        } catch (e: Exception) {
            Log.e("OnDeviceLlmRepository", "Model download failed", e)
            notificationHelper.cancelDownloadNotification()
            _state.value = _state.value.copy(
                isLoading = false,
                error = e.message ?: "Download failed"
            )
            Result.failure(e)
        }
    }

    override fun getModelPath(modelName: String) = getModelFile(modelName).absolutePath

    override fun isModelAvailable(modelName: String) = getModelFile(modelName).exists()

    override fun deleteModel(modelName: String): Boolean {
        val modelFile = getModelFile(modelName)
        return if (modelFile.exists()) {
            modelFile.delete()
            _state.value = _state.value.copy(modelPath = null)
            true
        } else {
            false
        }
    }

    override fun shutdown() {
        engine.shutdown()
        _state.value = OnDeviceLlmEngine.EngineState()
    }

    private fun getModelFile(modelName: String): File {
        return File(modelDir, modelName)
    }

    private fun buildDownloadUrl(huggingfaceRepo: String, modelName: String): String {
        return "https://huggingface.co/$huggingfaceRepo/resolve/main/$modelName?download=true"
    }

    init {
        Log.d("OnDeviceLlmRepository", "Repository initialized")
    }
}
