package com.aiassistant.domain.usecase

import com.aiassistant.domain.model.MemoryEntry
import com.aiassistant.domain.repository.MemoryRepository
import kotlin.math.sqrt

class MemorySearchUseCase(
    private val memoryRepository: MemoryRepository
) {
    suspend fun getRelevantMemories(query: String, limit: Int = 5): List<MemoryEntry> {
        val allMemories = memoryRepository.getMemoriesSync("")
        if (allMemories.isEmpty()) return emptyList()

        return allMemories
    }

    fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size) return 0f

        val dotProduct = a.zip(b).sumOf { (x, y) -> (x * y).toDouble() }
        val magnitudeA = sqrt(a.sumOf { (it * it).toDouble() })
        val magnitudeB = sqrt(b.sumOf { (it * it).toDouble() })

        return if (magnitudeA == 0.0 || magnitudeB == 0.0) 0f
        else (dotProduct / (magnitudeA * magnitudeB)).toFloat()
    }
}
