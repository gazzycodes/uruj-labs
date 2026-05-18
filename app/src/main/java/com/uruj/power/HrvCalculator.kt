package com.uruj.power

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * v0.7.0 — Lab-grade time-domain HRV calculator.
 *
 * **Methodology** — research-standard short-term + windowed RMSSD:
 *
 * 1. Input is List<Beat> (timestamp + RR), NOT a flat List<Int>. This lets us
 *    verify that RR pairs being differenced are TRULY consecutive heartbeats.
 *
 * 2. Two beats are "consecutive" iff (curr.timestampMs - prev.timestampMs)
 *    matches curr.rrMs within ±50 ms (BLE transmission jitter tolerance).
 *    Across BLE disconnects, sleep-stage transitions with missed beats, or
 *    notification gaps, this rejects pseudo-consecutive pairs that would
 *    otherwise corrupt RMSSD.
 *
 * 3. Physiological range filter on every RR: 300-2000 ms (= 30-200 BPM).
 *    Anything outside is sensor noise or artifact, dropped.
 *
 * 4. Ectopic filter on consecutive pairs: if abs(curr - prev) / prev > 0.20,
 *    skip that diff (Kubios convention). Catches PVCs, PACs, missed beats.
 *
 * 5. RMSSD = √(mean of squared consecutive diffs). Standard time-domain
 *    parasympathetic marker. Higher = better vagal tone.
 *
 * 6. SDNN = std-dev of all RR. Overall HRV (mix of all branches).
 *
 * 7. pNN50, pNN20 = % of consecutive pairs with diff > 50, 20 ms.
 *
 * 8. For **long captures** (24/7 overnight), use `computeWindowed(beats,
 *    windowMs=5min)`. It chunks data into 5-min windows, computes per-window
 *    HRV, and returns the median across valid windows. This is THE standard
 *    practice for overnight HRV — same approach Kubios / Polar / EliteHRV /
 *    HRV4Training use. Computing RMSSD over a 12-hour flat list is
 *    methodologically wrong: it mixes parasympathetic beat-to-beat variability
 *    with circadian/sleep-stage drift (which is captured by SDNN, NOT RMSSD).
 *
 * 9. Minimum sample requirements: ≥30 clean RR for short-window mode,
 *    ≥60 clean RR per 5-min window in windowed mode, ≥3 valid windows for
 *    a windowed result. Below these, return null rather than report a
 *    statistically meaningless number.
 */
class HrvCalculator {

    /**
     * One detected heartbeat. Both fields needed for timestamp-aware
     * consecutiveness checking (the crucial fix in v0.7.0 follow-up).
     *
     * @param timestampMs when this beat occurred (epoch ms)
     * @param rrMs the RR interval ending at this beat (gap from previous beat)
     */
    data class Beat(
        val timestampMs: Long,
        val rrMs: Int,
    )

    data class TimeDomainHrv(
        /** RMSSD in milliseconds. Higher = better parasympathetic recovery. */
        val rmssdMs: Float,
        /** SDNN in milliseconds. Overall HRV (all autonomic branches). */
        val sdnnMs: Float,
        /** Percentage of successive RR pairs differing by >50 ms. */
        val pnn50Percent: Float,
        /** Percentage of successive RR pairs differing by >20 ms. */
        val pnn20Percent: Float,
        /** Number of clean RR intervals used (post-filtering). */
        val sampleCount: Int,
        /** Mean RR interval in ms. */
        val meanRrMs: Float,
        /** Mean HR derived from mean RR. */
        val meanHrBpm: Float,
        /** Number of 5-min windows aggregated into this result. 1 for short-window
         *  mode. ≥3 for windowed mode (otherwise null returned). */
        val windowCount: Int = 1,
    )

    /**
     * Short-window HRV — for 5-min seated rest tests, orthostatic tests,
     * post-meal HRV, etc. Uses timestamp-aware consecutiveness on the input
     * beats. Returns null when fewer than 30 clean consecutive-pair diffs
     * are available.
     */
    fun compute(beats: List<Beat>): TimeDomainHrv? {
        val sorted = beats
            .filter { it.rrMs in PHYSIOLOGICAL_MIN..PHYSIOLOGICAL_MAX }
            .sortedBy { it.timestampMs }
        if (sorted.size < MIN_BEATS) return null

        val diffs = consecutiveDiffsMs(sorted)
        if (diffs.size < MIN_DIFFS) return null

        return buildHrv(sorted, diffs, windowCount = 1)
    }

    /**
     * Backward-compatible: takes flat RR list, assigns synthetic timestamps
     * assuming all beats are consecutive (no gaps). Use for short captures
     * where you only have RR values, not timestamps. NOT recommended for
     * overnight captures — use `computeWindowed` with real beats instead.
     */
    fun computeFromFlatRr(rrIntervalsMs: List<Int>): TimeDomainHrv? {
        if (rrIntervalsMs.isEmpty()) return null
        var t = 0L
        val beats = rrIntervalsMs.map { rr ->
            t += rr
            Beat(timestampMs = t, rrMs = rr)
        }
        return compute(beats)
    }

    /**
     * Windowed HRV — for overnight / long captures. Chunks beats into time
     * windows (default 5 min), computes per-window HRV (using
     * timestamp-aware consecutiveness, same filters as short-window mode),
     * and aggregates by median across valid windows.
     *
     * Windows are rejected if they have fewer than [minDiffsPerWindow]
     * consecutive-pair diffs (default 60). If fewer than [minValidWindows]
     * (default 3) windows pass, returns null.
     *
     * Median aggregation is robust to outlier windows (e.g. windows during
     * sleep-stage transitions, brief sleep apnea events, BLE micro-glitches).
     */
    fun computeWindowed(
        beats: List<Beat>,
        windowMs: Long = DEFAULT_WINDOW_MS,
        minDiffsPerWindow: Int = MIN_DIFFS,
        minValidWindows: Int = MIN_WINDOWS,
    ): TimeDomainHrv? {
        val filtered = beats
            .filter { it.rrMs in PHYSIOLOGICAL_MIN..PHYSIOLOGICAL_MAX }
            .sortedBy { it.timestampMs }
        if (filtered.isEmpty()) return null

        val firstTs = filtered.first().timestampMs
        val perWindow = mutableMapOf<Long, MutableList<Beat>>()
        for (b in filtered) {
            val idx = (b.timestampMs - firstTs) / windowMs
            perWindow.getOrPut(idx) { mutableListOf() }.add(b)
        }

        val windowResults = perWindow.values.mapNotNull { winBeats ->
            val diffs = consecutiveDiffsMs(winBeats)
            if (diffs.size < minDiffsPerWindow) return@mapNotNull null
            buildHrv(winBeats, diffs, windowCount = 1)
        }
        if (windowResults.size < minValidWindows) return null

        // Median aggregation across windows
        val rmssdMedian = median(windowResults.map { it.rmssdMs })
        val sdnnMedian = median(windowResults.map { it.sdnnMs })
        val pnn50Median = median(windowResults.map { it.pnn50Percent })
        val pnn20Median = median(windowResults.map { it.pnn20Percent })
        val meanRrMedian = median(windowResults.map { it.meanRrMs })
        val totalSamples = windowResults.sumOf { it.sampleCount }
        return TimeDomainHrv(
            rmssdMs = rmssdMedian,
            sdnnMs = sdnnMedian,
            pnn50Percent = pnn50Median,
            pnn20Percent = pnn20Median,
            sampleCount = totalSamples,
            meanRrMs = meanRrMedian,
            meanHrBpm = if (meanRrMedian > 0f) 60_000f / meanRrMedian else 0f,
            windowCount = windowResults.size,
        )
    }

    /**
     * Returns the list of true consecutive-pair RR diffs (ms). Walks the
     * sorted beat list, keeps only pairs where (current.timestamp -
     * previous.timestamp) matches current.rrMs within tolerance AND the
     * delta is within ectopic threshold.
     */
    private fun consecutiveDiffsMs(sorted: List<Beat>): List<Int> {
        if (sorted.size < 2) return emptyList()
        val diffs = mutableListOf<Int>()
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val curr = sorted[i]
            val expectedGap = curr.rrMs.toLong()
            val actualGap = curr.timestampMs - prev.timestampMs
            // Consecutive iff actual ≈ expected
            if (abs(actualGap - expectedGap) > CONSECUTIVE_TOLERANCE_MS) continue
            // Ectopic / artifact filter
            val deltaPct = abs(curr.rrMs - prev.rrMs).toFloat() / prev.rrMs
            if (deltaPct > ECTOPIC_THRESHOLD) continue
            diffs.add(curr.rrMs - prev.rrMs)
        }
        return diffs
    }

    private fun buildHrv(
        sorted: List<Beat>,
        diffs: List<Int>,
        windowCount: Int,
    ): TimeDomainHrv {
        val meanRrMs = sorted.map { it.rrMs.toDouble() }.average().toFloat()
        val meanHr = if (meanRrMs > 0f) 60_000f / meanRrMs else 0f
        val rmssdMs = sqrt(diffs.map { (it * it).toDouble() }.average()).toFloat()
        val rrMean = meanRrMs.toDouble()
        val variance = sorted.map { (it.rrMs - rrMean) * (it.rrMs - rrMean) }.average()
        val sdnnMs = sqrt(variance).toFloat()
        val gt50 = diffs.count { abs(it) > 50 }
        val gt20 = diffs.count { abs(it) > 20 }
        val pnn50 = (gt50.toFloat() / diffs.size) * 100f
        val pnn20 = (gt20.toFloat() / diffs.size) * 100f
        return TimeDomainHrv(
            rmssdMs = rmssdMs,
            sdnnMs = sdnnMs,
            pnn50Percent = pnn50,
            pnn20Percent = pnn20,
            sampleCount = sorted.size,
            meanRrMs = meanRrMs,
            meanHrBpm = meanHr,
            windowCount = windowCount,
        )
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
    }

    companion object {
        /** Physiological RR-interval range: 300-2000 ms = 30-200 BPM. */
        const val PHYSIOLOGICAL_MIN = 300
        const val PHYSIOLOGICAL_MAX = 2000

        /** Two beats are "consecutive" iff their timestamp gap matches the
         *  RR within this tolerance (BLE jitter). 50 ms is generous. */
        const val CONSECUTIVE_TOLERANCE_MS = 50L

        /** Ectopic filter: drop consecutive pairs differing >20% of prev RR. */
        const val ECTOPIC_THRESHOLD = 0.20f

        /** Minimum clean beats required for short-window HRV (≈30s @ HR60). */
        const val MIN_BEATS = 30

        /** Minimum clean consecutive-pair diffs per window. ~60 diffs ≈ 1min
         *  of clean continuous data at HR 60 — robust RMSSD baseline. */
        const val MIN_DIFFS = 60

        /** Default windowed-mode window size: 5 minutes. Research standard
         *  for short-term HRV (Task Force 1996). */
        const val DEFAULT_WINDOW_MS = 5L * 60L * 1000L

        /** Minimum valid windows for windowed-mode result. <3 = result not
         *  statistically robust → return null instead of misleading number. */
        const val MIN_WINDOWS = 3
    }
}
