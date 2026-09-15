package com.aiassistant.domain.repository

/**
 * Produces text embeddings for semantic memory search.
 *
 * Kept as a domain interface so the use-case layer does not need to know whether the vectors come
 * from an on-device model or a remote API.
 */
interface EmbeddingProvider {

    /** True when embeddings can be produced without a network call or a model download. */
    suspend fun isReady(): Boolean

    suspend fun embed(text: String): Result<List<Float>>

    suspend fun embedAll(texts: List<String>): Result<List<List<Float>>>

    suspend fun shutdown()
}
