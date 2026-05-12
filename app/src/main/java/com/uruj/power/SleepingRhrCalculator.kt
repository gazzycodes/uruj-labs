package com.uruj.power

import java.time.Instant

/**
 * Athletic sleeping RHR — for each detected sleep night, find the minimum HR
 * sample within that night (with a < 35 bpm glitch filter), then return both
 * the median across nights AND the most-recent night's min separately.
 *
 * Used by Bio Lab (median for display) and Readiness scoring (most-recent for
 * "today" + median for "7d baseline"). Centralised so both screens compute
 * RHR with the same definition — eliminates the v0.2.9 inconsistency where
 * Bio Lab showed 50 and Readiness showed 55 because they used different
 * proxy algorithms.
 *
 * Matches Garmin / Whoop's definition of resting HR: the lowest sustained HR
 * during deep sleep, smoothed across multiple nights of data. The previous
 * percentile-of-all-sleep-samples approach was over-conservative for sparse
 * Fit Band 3 spot-check data; min-per-night + median-across-nights tracks
 * the band's actual lows without being thrown by single-night sensor weird.
 */
class SleepingRhrCalculator {

    data class Result(
        /** Median of nightly minimums — robust against single-night outliers. */
        val medianBpm: Int,
        /** Min HR observed during the most recent sleep night with HR samples. */
        val mostRecentNightBpm: Int,
        /** When the most recent night with HR data ended. */
        val mostRecentNightEndTime: Instant,
        /** Count of qualifying nights feeding the median. */
        val nightsCount: Int,
    )

    fun compute(
        timedSamples: List<Pair<Instant, Int>>,
        sleepWindows: List<Pair<Instant, Instant>>,
    ): Result? {
        if (sleepWindows.isEmpty() || timedSamples.isEmpty()) return null

        // For each sleep window: find min HR sample (with glitch filter), require
        // ≥5 samples per night so a stray wake-up sample can't masquerade as deep
        // sleep. Tag with end time so we can find "most recent".
        val perNight = sleepWindows.mapNotNull { (start, end) ->
            val nightSamples = timedSamples
                .filter { (t, bpm) -> !t.isBefore(start) && !t.isAfter(end) && bpm >= 35 }
                .map { it.second }
            if (nightSamples.size < 5) return@mapNotNull null
            NightMin(endTime = end, minBpm = nightSamples.min())
        }
        if (perNight.isEmpty()) return null

        val sortedMins = perNight.map { it.minBpm }.sorted()
        val median = if (sortedMins.size % 2 == 1) {
            sortedMins[sortedMins.size / 2]
        } else {
            (sortedMins[sortedMins.size / 2 - 1] + sortedMins[sortedMins.size / 2]) / 2
        }
        val mostRecent = perNight.maxBy { it.endTime }
        return Result(
            medianBpm = median,
            mostRecentNightBpm = mostRecent.minBpm,
            mostRecentNightEndTime = mostRecent.endTime,
            nightsCount = perNight.size,
        )
    }

    private data class NightMin(val endTime: Instant, val minBpm: Int)
}
