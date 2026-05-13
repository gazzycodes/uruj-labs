package com.uruj.power

import java.time.Instant
import kotlin.math.sqrt

/**
 * Sleeping HRV proxy — std-dev of HR samples filtered to sleep windows only.
 *
 * The old "HR-proxy HRV" (std-dev across ALL 24h HR samples) had a fatal flaw:
 * it conflated activity level with autonomic variability. A rest day (mostly
 * low/stable HR) showed LOW std-dev → readiness score penalized it as "low HRV"
 * even though the rider had recovered well. A workout day showed HIGH std-dev
 * (HR ranged 50-180) → registered as "high HRV" even though it just reflected
 * effort. Backwards signal.
 *
 * Same fix pattern as SleepingRhrCalculator: restrict to sleep windows so the
 * sampled HR ranges are comparable day-to-day. Std-dev WITHIN a sleep window
 * reflects sleep-stage cycling + autonomic transitions, which is closer to
 * (though not identical to) clinical RMSSD HRV. Real RMSSD needs beat-to-beat
 * RR intervals from a chest strap (v1.5).
 *
 * Returns:
 *   todayMs = most recent sleep night's HR std-dev
 *   baselineMs = median of last N nights' std-devs (one per sleep night)
 *
 * Both numbers use the SAME methodology — apples-to-apples ratio in the
 * readiness calculator. Today's value drops are now meaningful autonomic
 * signals, not activity-level artifacts.
 */
class SleepingHrvProxyCalculator {

    data class Result(
        val todayMs: Float,
        val baselineMs: Float,
        val nightsCount: Int,
    )

    fun compute(
        timedSamples: List<Pair<Instant, Int>>,
        sleepWindows: List<Pair<Instant, Instant>>,
    ): Result? {
        if (sleepWindows.isEmpty() || timedSamples.isEmpty()) return null

        // For each sleep night, compute std-dev of HR samples within. Require
        // ≥10 samples — under that, std-dev is too noisy to trust as an HRV
        // proxy. With Fit Band 3's spot-check cadence (1-5 min during sleep),
        // a 6h sleep yields ~70+ samples on a typical night.
        val perNight = sleepWindows.mapNotNull { (start, end) ->
            val nightSamples = timedSamples
                .filter { (t, bpm) -> !t.isBefore(start) && !t.isAfter(end) && bpm >= 35 }
                .map { it.second.toDouble() }
            if (nightSamples.size < 10) return@mapNotNull null
            NightStdDev(endTime = end, stdDev = stdDevOf(nightSamples))
        }
        if (perNight.isEmpty()) return null

        // Most recent first
        val sorted = perNight.sortedByDescending { it.endTime }
        val today = sorted.first().stdDev
        // Baseline = median of all nights including today (median is robust)
        val sortedStdDevs = perNight.map { it.stdDev }.sorted()
        val median = if (sortedStdDevs.size % 2 == 1) {
            sortedStdDevs[sortedStdDevs.size / 2]
        } else {
            (sortedStdDevs[sortedStdDevs.size / 2 - 1] +
                sortedStdDevs[sortedStdDevs.size / 2]) / 2.0
        }
        return Result(
            todayMs = today.toFloat(),
            baselineMs = median.toFloat(),
            nightsCount = perNight.size,
        )
    }

    private fun stdDevOf(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance)
    }

    private data class NightStdDev(val endTime: Instant, val stdDev: Double)
}
