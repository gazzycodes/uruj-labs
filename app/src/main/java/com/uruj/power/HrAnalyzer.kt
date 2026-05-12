package com.uruj.power

import kotlin.math.sqrt

/**
 * Derives proxy RHR and proxy HRV from a list of HR samples (bpm) over a time window.
 * Used as a fallback when Samsung Health (or any wearable) doesn't write the dedicated
 * `RestingHeartRateRecord` / `HeartRateVariabilityRmssdRecord` types to Health Connect.
 *
 *   Proxy RHR  ≈ median of the lowest 5% of HR samples (Garmin / Fitbit pattern)
 *   Proxy HRV ≈ standard deviation of resting HR samples (Whoop pre-strap pattern)
 *
 * Not lab-grade RMSSD (that requires beat-to-beat R-R intervals at millisecond precision),
 * but directionally accurate enough for readiness scoring — when today's proxy values
 * deviate from a 7-day baseline of the same proxy, the trend is real even if the
 * absolute number isn't.
 *
 * The HR samples are passed as raw ints (bpm) — caller is responsible for filtering to
 * the desired time window (last 24h, last 7d, etc.).
 */
class HrAnalyzer {

    fun analyze(samples: List<Int>): HrAnalysisResult {
        if (samples.size < 5) {
            return HrAnalysisResult(samples.size, null, null, null, null, null)
        }
        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2]

        // Proxy RHR: median of lowest 5% (or at least 5 samples). This filters out
        // workout / active-period spikes and captures resting baseline.
        val lowestCount = (sorted.size / 20).coerceAtLeast(5).coerceAtMost(sorted.size)
        val lowest = sorted.take(lowestCount)
        val proxyRhr = lowest[lowest.size / 2]

        // Proxy HRV: standard deviation of the resting-quartile samples (bottom 25%).
        // High variability in resting HR correlates with parasympathetic tone /
        // good recovery, low variability = sympathetic dominance / fatigue.
        val restingQuartileCount = (sorted.size / 4).coerceAtLeast(10).coerceAtMost(sorted.size)
        val restingSamples = sorted.take(restingQuartileCount)
        val restingMean = restingSamples.average()
        val variance = restingSamples.sumOf { (it - restingMean) * (it - restingMean) } / restingSamples.size
        val proxyHrvSd = sqrt(variance).toFloat()

        return HrAnalysisResult(
            sampleCount = samples.size,
            medianBpm = median,
            proxyRestingHrBpm = proxyRhr,
            proxyHrvSdMs = proxyHrvSd,
            minBpm = sorted.first(),
            maxBpm = sorted.last(),
        )
    }
}

data class HrAnalysisResult(
    val sampleCount: Int,
    val medianBpm: Int?,
    /** Lowest sustained HR (median of lowest 5%). Proxy for RHR when no dedicated record available. */
    val proxyRestingHrBpm: Int?,
    /** Std deviation of resting-quartile HR samples. Proxy for HRV (not RMSSD, but correlated). */
    val proxyHrvSdMs: Float?,
    val minBpm: Int?,
    val maxBpm: Int?,
)
