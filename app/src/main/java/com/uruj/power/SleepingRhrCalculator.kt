package com.uruj.power

import com.uruj.domain.SensorSource
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
        /** v0.7.7 — which sensor produced the most-recent-night RHR reading.
         *  STRAP if 24/7 NDJSON had ≥60% coverage of the sleep window,
         *  otherwise BAND (HC samples). The card UI shows this as a badge. */
        val mostRecentNightSource: SensorSource = SensorSource.UNKNOWN_LEGACY,
        /** Per-source nightly count for the overall breakdown badge. */
        val sourceBreakdown: Map<SensorSource, Int> = emptyMap(),
        /** v0.9.82 — which population the median was actually taken over.
         *  STRAP = strap-only nights (trustworthy for trending). MIXED = the
         *  pool had to include band nights, so the value carries a systematic
         *  offset and MUST NOT be trended or fed to derived metrics without
         *  saying so. See [medianIsSourcePure]. */
        val medianSource: SensorSource = SensorSource.UNKNOWN_LEGACY,
        /** v0.9.82 — true when the median came from strap nights alone. */
        val medianIsSourcePure: Boolean = false,
    )

    /**
     * v0.7.7 — bulletproof source-aware compute.
     *
     * For each sleep window we evaluate strap + HC streams separately. Strap
     * wins if it has ≥60% sample-density coverage of the window AND ≥5 valid
     * samples post-glitch-filter. Else fall back to HC if it has ≥5 valid
     * samples. Each per-night result carries its source.
     *
     * @param hcSamples HC HeartRateRecord batches (fallback)
     * @param strapSamples BLE 24/7 NDJSON samples (preferred). Pass empty list
     *   if no strap data available for this period.
     */
    fun compute(
        hcSamples: List<Pair<Instant, Int>>,
        sleepWindows: List<Pair<Instant, Instant>>,
        strapSamples: List<Pair<Instant, Int>> = emptyList(),
    ): Result? {
        if (sleepWindows.isEmpty()) return null
        if (hcSamples.isEmpty() && strapSamples.isEmpty()) return null

        val perNight = sleepWindows.mapNotNull { (start, end) ->
            // Try strap first (per-second precision)
            val strapMin = minForWindow(strapSamples, start, end)
            val strapCount = strapSamples.count { (t, _) -> !t.isBefore(start) && !t.isAfter(end) }
            val winLengthSec = java.time.Duration.between(start, end).seconds.coerceAtLeast(1)
            val strapDensity = strapCount.toFloat() / winLengthSec.toFloat() // ~1.0 for full coverage
            if (strapMin != null && strapDensity >= COVERAGE_THRESHOLD) {
                return@mapNotNull NightMin(end, strapMin, SensorSource.STRAP)
            }
            // Fall back to HC
            val hcMin = minForWindow(hcSamples, start, end)
            if (hcMin != null) {
                // If strap had some data but didn't pass coverage, this is
                // technically MIXED. But we use HC value (more samples), so
                // call it BAND-leaning. Source = BAND.
                return@mapNotNull NightMin(end, hcMin, SensorSource.BAND)
            }
            // Strap had partial coverage but HC empty — accept strap as MIXED.
            if (strapMin != null) return@mapNotNull NightMin(end, strapMin, SensorSource.MIXED)
            null
        }
        if (perNight.isEmpty()) return null

        // v0.9.82 — SOURCE PURITY. Pooling strap and band nights into one median
        // is invalid: the two sensors do not measure the same thing. Measured on
        // this athlete's own data (2026-06-30 .. 2026-08-23), the wrist band read
        // a mean of 47.3 bpm on nights the strap read 39.8 — a systematic
        // +7.5 bpm offset on the same person, same nights' worth of sleep.
        //
        // Pooling them means the median moves whenever the rider happens to
        // change which device they slept in, and that movement is then read as
        // physiology. It produced a false "RHR creeping up — illness / over-reach
        // early warning" on 2026-08-23 (median 45 -> 46) driven purely by more
        // band nights entering the window, and it propagates into VO2 max, which
        // is 15 x maxHR / RHR and therefore swings ~15% on sensor choice alone.
        //
        // Prefer strap-only nights whenever there are enough for a stable median.
        // Fall back to the mixed pool only when there aren't, and label it so
        // every downstream consumer can see the value is not trend-safe.
        val strapNights = perNight.filter { it.source == SensorSource.STRAP }
        val usePureStrap = strapNights.size >= MIN_STRAP_NIGHTS_FOR_PURE_MEDIAN
        val medianPool = if (usePureStrap) strapNights else perNight

        val sortedMins = medianPool.map { it.minBpm }.sorted()
        val median = if (sortedMins.size % 2 == 1) {
            sortedMins[sortedMins.size / 2]
        } else {
            (sortedMins[sortedMins.size / 2 - 1] + sortedMins[sortedMins.size / 2]) / 2
        }
        val mostRecent = perNight.maxBy { it.endTime }
        val breakdown = perNight.groupingBy { it.source }.eachCount()
        return Result(
            medianBpm = median,
            mostRecentNightBpm = mostRecent.minBpm,
            mostRecentNightEndTime = mostRecent.endTime,
            nightsCount = medianPool.size,
            mostRecentNightSource = mostRecent.source,
            sourceBreakdown = breakdown,
            medianSource = if (usePureStrap) SensorSource.STRAP else SensorSource.MIXED,
            medianIsSourcePure = usePureStrap,
        )
    }

    /** Apply glitch filter + ≥5-sample requirement, return min bpm or null. */
    private fun minForWindow(
        samples: List<Pair<Instant, Int>>,
        start: Instant,
        end: Instant,
    ): Int? {
        val nightSamples = samples
            .filter { (t, bpm) -> !t.isBefore(start) && !t.isAfter(end) && bpm >= 35 }
            .map { it.second }
        if (nightSamples.size < 5) return null
        return nightSamples.min()
    }

    private data class NightMin(val endTime: Instant, val minBpm: Int, val source: SensorSource)

    companion object {
        /** v0.7.7 — minimum sample density (samples per second of window) to
         *  commit to STRAP source. 0.10 = ~36 samples per hour-long window,
         *  generous enough to absorb BLE drops while still meaningful. */
        private const val COVERAGE_THRESHOLD = 0.10f

        /** v0.9.82 — nights of strap data required before the median is taken
         *  over strap nights ALONE. Five is enough for a median to be robust to
         *  one bad night while still being reachable for a rider who wears the
         *  strap most nights. Below this we fall back to the mixed pool and
         *  flag it rather than report a strap median off one or two nights. */
        private const val MIN_STRAP_NIGHTS_FOR_PURE_MEDIAN = 5
    }
}
