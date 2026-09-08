package com.aiassistant.data.repository

import android.util.Log
import com.aiassistant.data.llm.OnDeviceLlmSettingsManager
import com.aiassistant.domain.llm.OnDeviceEmbeddingEngine
import com.aiassistant.domain.repository.EmbeddingProvider
import com.aiassistant.domain.repository.OnDeviceLlmRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OnDeviceEmbeddingRepo"

@Singleton
class OnDeviceEmbeddingRepositoryImpl @Inject constructor(
    private val engine: OnDeviceEmbeddingEngine,
    private val settingsManager: OnDeviceLlmSettingsManager,
    private val onDeviceLlmRepository: OnDeviceLlmRepository
) : EmbeddingProvider {

    override suspend fun isReady(): Boolean {
        val settings = settingsManager.getSettings()
        return settings.onDeviceEmbeddingsEnabled &&
            onDeviceLlmRepository.isModelAvailable(settings.embeddingModelName)
    }

    override suspend fun embed(text: String): Result<List<Float>> =
        embedAll(listOf(text)).map { it.firstOrNull().orEmpty() }

    override suspend fun embedAll(texts: List<String>): Result<List<List<Float>>> {
        val settings = settingsManager.getSettings()
        if (!settings.onDeviceEmbeddingsEnabled) {
            return Result.failure(IllegalStateException("On-device embeddings are disabled"))
        }

        val modelPath = if (onDeviceLlmRepository.isModelAvailable(settings.embeddingModelName)) {
            onDeviceLlmRepository.getModelPath(settings.embeddingModelName)
        } else {
            Log.d(TAG, "Embedding model missing, downloading ${settings.embeddingModelName}")
            val downloaded = onDeviceLlmRepository.downloadModel(
                huggingfaceRepo = settings.embeddingHuggingfaceRepo,
                modelName = settings.embeddingModelName
            )
            downloaded.getOrElse { return Result.failure(it) }
        }

        return engine.embedAll(texts, modelPath)
    }

    override suspend fun shutdown() = engine.shutdown()
}
