package com.aiassistant.domain.llm

/**
 * Throughput for the most recent generation, from LiteRT-LM's `BenchmarkInfo`.
 *
 * The runtime's counters are per-turn ("last"), so on a multi-round tool call these describe the
 * final round rather than the whole exchange.
 */
data class GenerationStats(
    val prefillTokens: Int,
    val decodeTokens: Int,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
    val timeToFirstTokenSeconds: Double
) {
    fun summary(): String = buildString {
        append("%.1f tok/s".format(decodeTokensPerSecond))
        append(" · $decodeTokens tokens")
        if (timeToFirstTokenSeconds > 0) append(" · %.1fs to first".format(timeToFirstTokenSeconds))
        if (prefillTokensPerSecond > 0) {
            append(" · prefill %.0f tok/s".format(prefillTokensPerSecond))
        }
    }
}
