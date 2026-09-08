package com.aiassistant.domain.usecase

import android.util.Log
import com.aiassistant.domain.model.MemoryEntry
import com.aiassistant.domain.repository.EmbeddingProvider
import com.aiassistant.domain.repository.MemoryRepository
import com.aiassistant.domain.service.VectorMathService

private const val TAG = "MemorySearchUseCase"
private const val CANDIDATE_MULTIPLIER = 10
private const val DEFAULT_THRESHOLD = 0.5f

/** Above this similarity a new fact is treated as a restatement of one already stored. */
private const val DUPLICATE_THRESHOLD = 0.95f

/** How many existing memories to compare a new fact against before storing it. */
private const val DUPLICATE_CANDIDATE_POOL = 100

class MemorySearchUseCase(
    private val memoryRepository: MemoryRepository,
    private val embeddingProvider: EmbeddingProvider,
    private val vectorMathService: VectorMathService
) {

    /**
     * Ranks stored memories against [query] by cosine similarity of their embeddings.
     *
     * Memory is deliberately global rather than per-conversation: a fact worth storing is worth
     * recalling in a later conversation. `conversationId` on an entry is provenance only.
     *
     * @param fallbackToRecent when ranking is impossible (embeddings disabled, or no stored entry
     *   carries a vector), return the most recent entries instead of nothing. Appropriate for an
     *   explicit recall request, but not for silent prompt injection, where unranked entries are
     *   just noise.
     */
    suspend fun getRelevantMemories(
        query: String,
        limit: Int = 5,
        threshold: Float = DEFAULT_THRESHOLD,
        fallbackToRecent: Boolean = true
    ): List<MemoryEntry> {
        val candidates = memoryRepository.getSimilarMemories(query, limit * CANDIDATE_MULTIPLIER)
        if (candidates.isEmpty()) return emptyList()

        fun fallback() = if (fallbackToRecent) candidates.take(limit) else emptyList()

        if (query.isBlank() || !embeddingProvider.isReady()) return fallback()

        val queryEmbedding = embeddingProvider.embed(query).getOrElse { error ->
            Log.w(TAG, "Query embedding failed", error)
            return fallback()
        }
        if (queryEmbedding.isEmpty()) return fallback()

        val embedded = candidates.filter { it.embedding.size == queryEmbedding.size }
        if (embedded.isEmpty()) {
            Log.d(TAG, "No stored memory carries a matching embedding")
            return fallback()
        }

        return vectorMathService
            .findMostSimilar(queryEmbedding, embedded, threshold, limit)
            .map { (entry, _) -> entry }
    }

    /**
     * Stores [content] as a memory with its embedding attached.
     *
     * Returns [RememberResult.Duplicate] rather than storing again when an existing memory says
     * essentially the same thing, so repeated mentions of the same fact do not accumulate.
     */
    suspend fun remember(content: String, conversationId: String): RememberResult {
        val fact = content.trim()
        if (fact.isEmpty()) return RememberResult.Rejected("Nothing to remember")

        val embedding = if (embeddingProvider.isReady()) {
            embeddingProvider.embed(fact).getOrElse { error ->
                Log.w(TAG, "Memory embedding failed, storing without vector", error)
                emptyList()
            }
        } else {
            emptyList()
        }

        if (embedding.isNotEmpty()) {
            val existing = memoryRepository
                .getSimilarMemories(fact, DUPLICATE_CANDIDATE_POOL)
                .filter { it.embedding.size == embedding.size }
            val duplicate = existing.firstOrNull {
                vectorMathService.cosineSimilarity(embedding, it.embedding) >= DUPLICATE_THRESHOLD
            }
            if (duplicate != null) {
                Log.d(TAG, "Skipping near-duplicate memory of ${duplicate.id}")
                return RememberResult.Duplicate(duplicate.content)
            }
        }

        memoryRepository.addMemoryFromText(fact, conversationId, embedding)
        Log.d(TAG, "Stored memory (embedded=${embedding.isNotEmpty()})")
        return RememberResult.Stored(embedded = embedding.isNotEmpty())
    }

    /** Backfills embeddings for memories stored before an embedding model was available. */
    suspend fun backfillEmbeddings(batchSize: Int = 32): Int {
        if (!embeddingProvider.isReady()) return 0

        val missing = memoryRepository.getMemoriesWithoutEmbedding(batchSize)
        if (missing.isEmpty()) return 0

        val embeddings = embeddingProvider.embedAll(missing.map { it.content }).getOrElse { error ->
            Log.w(TAG, "Backfill failed", error)
            return 0
        }

        var count = 0
        missing.zip(embeddings).forEach { (entry, embedding) ->
            if (embedding.isNotEmpty()) {
                memoryRepository.updateEmbedding(entry.id, embedding)
                count++
            }
        }
        Log.d(TAG, "Backfilled $count memory embedding(s)")
        return count
    }

    fun cosineSimilarity(a: List<Float>, b: List<Float>): Float =
        if (a.size != b.size) 0f else vectorMathService.cosineSimilarity(a, b)

    sealed interface RememberResult {
        data class Stored(val embedded: Boolean) : RememberResult
        data class Duplicate(val existingContent: String) : RememberResult
        data class Rejected(val reason: String) : RememberResult
    }
}
