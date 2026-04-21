package com.aiassistant.domain.service

import com.aiassistant.domain.model.MemoryEntry
import kotlin.math.sqrt

class VectorMathService {
    fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        require(a.size == b.size) { "Vectors must have same dimension" }
        if (a.isEmpty()) return 0f

        val dotProduct = a.zip(b).sumOf { (x, y) -> (x * y).toDouble() }
        val magnitudeA = sqrt(a.sumOf { (it * it).toDouble() })
        val magnitudeB = sqrt(b.sumOf { (it * it).toDouble() })

        return if (magnitudeA == 0.0 || magnitudeB == 0.0) 0f
        else (dotProduct / (magnitudeA * magnitudeB)).toFloat()
    }

    fun findMostSimilar(
        query: List<Float>,
        candidates: List<MemoryEntry>,
        threshold: Float = 0.5f,
        limit: Int = 5
    ): List<Pair<MemoryEntry, Float>> {
        return candidates
            .map { entry ->
                entry to cosineSimilarity(query, entry.embedding)
            }
            .filter { (_, similarity) -> similarity >= threshold }
            .sortedByDescending { (_, similarity) -> similarity }
            .take(limit)
    }

    fun normalize(vector: List<Float>): List<Float> {
        val magnitude = sqrt(vector.sumOf { (it * it).toDouble() })
        return if (magnitude == 0.0) vector
        else vector.map { it / magnitude.toFloat() }
    }
}
