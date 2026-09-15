package com.aiassistant.domain.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "OnDeviceEmbedding"

/**
 * Wraps LiteRT-LM's [EmbeddingEngine] for text embeddings.
 *
 * Unlike [OnDeviceLlmEngine] this runs in the app process rather than the `:llm` service: the
 * embedding models are far smaller than the chat models, and search needs a synchronous answer
 * without a round trip through the Messenger protocol. The engine is loaded lazily on first use
 * and guarded by a mutex, since the native handle is not safe for concurrent calls.
 */
class OnDeviceEmbeddingEngine(
    private val context: Context
) {

    private val mutex = Mutex()
    private var engine: EmbeddingEngine? = null
    private var loadedModelPath: String? = null

    val isLoaded: Boolean get() = engine != null

    suspend fun embed(text: String, modelPath: String): Result<List<Float>> =
        embedAll(listOf(text), modelPath).map { it.firstOrNull().orEmpty() }

    suspend fun embedAll(
        texts: List<String>,
        modelPath: String
    ): Result<List<List<Float>>> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext Result.success(emptyList())
        mutex.withLock {
            try {
                val engine = ensureEngine(modelPath)
                val options = EmbeddingOptions(normalize = true)
                val embeddings = engine.computeEmbeddingBatch(
                    texts.map { listOf(InputData.Text(it)) },
                    options
                ).map { it.embedding.toList() }
                Result.success(embeddings)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to compute ${texts.size} embedding(s)", e)
                Result.failure(e)
            }
        }
    }

    suspend fun shutdown() = withContext(Dispatchers.IO) {
        mutex.withLock { closeEngine() }
    }

    private fun ensureEngine(modelPath: String): EmbeddingEngine {
        engine?.let { existing ->
            if (loadedModelPath == modelPath) return existing
            Log.d(TAG, "Embedding model changed, reloading")
            closeEngine()
        }

        val modelFile = File(modelPath)
        check(modelFile.exists() && modelFile.length() > 0L) {
            "Embedding model not found or empty: $modelPath"
        }

        Log.d(TAG, "Loading embedding model: $modelPath")
        val created = EmbeddingEngine(
            EmbeddingEngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.path
            )
        )
        created.initialize()
        engine = created
        loadedModelPath = modelPath
        return created
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
            .onFailure { Log.w(TAG, "Failed to close embedding engine", it) }
        engine = null
        loadedModelPath = null
    }

    companion object {
        const val DEFAULT_EMBEDDING_MODEL_NAME = "LFM2.5-Embedding-350M_wi8fc.litertlm"
        const val DEFAULT_EMBEDDING_REPO = "litert-community/LFM2.5-Embedding-350M"
    }
}
