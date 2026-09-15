package com.aiassistant.domain.service

import com.aiassistant.domain.model.MemoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory ranking. A wrong similarity does not crash anything -- it just quietly recalls the wrong
 * fact, or none, which is the hardest kind of failure to notice in use.
 */
class VectorMathServiceTest {

    private val math = VectorMathService()

    private fun entry(id: String, embedding: List<Float>) = MemoryEntry(
        id = id,
        conversationId = "c1",
        content = id,
        embedding = embedding,
        timestamp = 0L
    )

    @Test
    fun `an identical vector scores 1`() {
        val v = listOf(0.3f, 0.5f, 0.8f)

        assertEquals(1f, math.cosineSimilarity(v, v), 1e-5f)
    }

    @Test
    fun `an orthogonal vector scores 0`() {
        assertEquals(0f, math.cosineSimilarity(listOf(1f, 0f), listOf(0f, 1f)), 1e-5f)
    }

    @Test
    fun `an opposed vector scores -1`() {
        assertEquals(-1f, math.cosineSimilarity(listOf(1f, 0f), listOf(-1f, 0f)), 1e-5f)
    }

    @Test
    fun `magnitude does not affect direction`() {
        // The whole point of cosine: a longer vector pointing the same way is just as similar.
        assertEquals(1f, math.cosineSimilarity(listOf(1f, 1f), listOf(10f, 10f)), 1e-5f)
    }

    @Test
    fun `a zero vector scores 0 rather than dividing by zero`() {
        assertEquals(0f, math.cosineSimilarity(listOf(0f, 0f), listOf(1f, 1f)), 1e-5f)
    }

    @Test
    fun `an empty vector scores 0`() {
        assertEquals(0f, math.cosineSimilarity(emptyList(), emptyList()), 1e-5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mismatched dimensions are rejected`() {
        // Two different embedding models in the same database would otherwise rank nonsense.
        math.cosineSimilarity(listOf(1f, 0f), listOf(1f, 0f, 0f))
    }

    @Test
    fun `matches come back best first`() {
        val query = listOf(1f, 0f)
        val candidates = listOf(
            entry("orthogonal", listOf(0f, 1f)),
            entry("exact", listOf(1f, 0f)),
            entry("close", listOf(0.9f, 0.1f))
        )

        val ranked = math.findMostSimilar(query, candidates, threshold = 0.5f)

        assertEquals(listOf("exact", "close"), ranked.map { it.first.id })
    }

    @Test
    fun `the threshold excludes weak matches`() {
        val ranked = math.findMostSimilar(
            query = listOf(1f, 0f),
            candidates = listOf(entry("orthogonal", listOf(0f, 1f))),
            threshold = 0.5f
        )

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `the limit caps how many come back`() {
        val candidates = (1..10).map { entry("e$it", listOf(1f, 0f)) }

        assertEquals(3, math.findMostSimilar(listOf(1f, 0f), candidates, limit = 3).size)
    }

    @Test
    fun `normalising gives a unit vector and keeps direction`() {
        val normalised = math.normalize(listOf(3f, 4f))

        assertEquals(0.6f, normalised[0], 1e-5f)
        assertEquals(0.8f, normalised[1], 1e-5f)
        assertEquals(1f, math.cosineSimilarity(normalised, listOf(3f, 4f)), 1e-5f)
    }

    @Test
    fun `normalising a zero vector returns it unchanged`() {
        assertEquals(listOf(0f, 0f), math.normalize(listOf(0f, 0f)))
    }
}
