package com.pasarguard.vpn.domain.model

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure-Kotlin latency quality & stability scorer.
 * Computes average/min/max latency, jitter (mean absolute difference between consecutive
 * successful samples), packet loss %, and a 0..100 stability score + tier.
 */
object PingScoring {

    val DEFAULT_TEST_URLS: List<String> = listOf(
        "https://www.gstatic.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
        "https://connectivitycheck.gstatic.com/generate_204",
        "http://detectportal.firefox.com/success.txt",
        "https://www.google.com/generate_204",
        "https://captive.apple.com"
    )

    fun evaluate(samplesMs: List<Int>): PingQualityMetrics {
        if (samplesMs.isEmpty()) {
            return PingQualityMetrics(
                avgMs = -1,
                minMs = -1,
                maxMs = -1,
                jitterMs = 0,
                packetLossPct = 100,
                stabilityScore = 0,
                tier = StabilityTier.DEAD,
                samples = emptyList()
            )
        }

        val successes = samplesMs.filter { it > 0 }
        val lossPct = (((samplesMs.size - successes.size).toDouble() / samplesMs.size) * 100.0).roundToInt()

        if (successes.isEmpty()) {
            return PingQualityMetrics(
                avgMs = -1,
                minMs = -1,
                maxMs = -1,
                jitterMs = 0,
                packetLossPct = 100,
                stabilityScore = 0,
                tier = StabilityTier.DEAD,
                samples = samplesMs
            )
        }

        val avg = successes.average().roundToInt()
        val min = successes.min()
        val max = successes.max()

        val jitter = if (successes.size >= 2) {
            var diffSum = 0
            for (i in 1 until successes.size) {
                diffSum += abs(successes[i] - successes[i - 1])
            }
            (diffSum.toDouble() / (successes.size - 1)).roundToInt()
        } else {
            0
        }

        // Weighted 0..100 score: latency (up to -55), packet loss (up to -45), jitter (up to -20)
        val latencyPenalty = when {
            avg <= 100 -> 0.0
            avg <= 220 -> (avg - 100) * 0.14
            avg <= 450 -> 16.8 + (avg - 220) * 0.12
            else -> 44.4 + ((avg - 450).coerceAtMost(550) * 0.02)
        }
        val lossPenalty = lossPct * 0.65
        val jitterPenalty = (jitter * 0.15).coerceAtMost(20.0)
        val score = (100.0 - latencyPenalty - lossPenalty - jitterPenalty).roundToInt().coerceIn(1, 100)

        val tier = when {
            lossPct >= 100 || avg <= 0 -> StabilityTier.DEAD
            score >= 80 && lossPct == 0 && avg <= 220 -> StabilityTier.EXCELLENT
            score >= 55 && lossPct <= 34 -> StabilityTier.GOOD
            else -> StabilityTier.POOR
        }

        return PingQualityMetrics(
            avgMs = avg,
            minMs = min,
            maxMs = max,
            jitterMs = jitter,
            packetLossPct = lossPct,
            stabilityScore = score,
            tier = tier,
            samples = samplesMs
        )
    }
}
