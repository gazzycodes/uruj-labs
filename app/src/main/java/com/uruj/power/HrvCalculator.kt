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
 *    matches curr.rrMs within tolerance = max(150 ms, 30% of expected RR).
 *    Generous enough to absorb typical Android BLE scheduling jitter (30-200
 *    ms variance is normal under load), tight enough to reject genuine
 *    missed-beat gaps (which would be ~2× RR or more).
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
 *    ≥30 clean consecutive-pair diffs per 5-min window in windowed mode,
 *    ≥2 valid windows for a windowed result. Below these, return null rather
 *    than report a statistically meaningless number.
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
        /**
         * v0.9.48 — per-stage RMSSD breakdown when [computeWindowed] is
         * called with `perStageBreakdown = true`. Stage labels: "deep" /
         * "rem" / "light" / "asleep" / "unknown". Only stages with ≥3
         * valid windows (~15 min, Plews convention) appear. Empty when
         * stage filtering wasn't requested.
         */
        val perStageRmssdMs: Map<String, Float> = emptyMap(),
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
     * consecutive-pair diffs (default 30). If fewer than [minValidWindows]
     * (default 2) windows pass, returns null.
     *
     * Median aggregation is robust to outlier windows (e.g. windows during
     * sleep-stage transitions, brief sleep apnea events, BLE micro-glitches).
     *
     * v0.9.48 — three behavioral upgrades:
     *
     * 1. **Sliding-window mode**: pass `slidingOverlap = 0.5f` to enable
     *    50% overlap (5-min windows starting every 2.5 min). Coverage of
     *    the night goes from ~10% (non-overlap, 5 cherry-picked windows)
     *    to 80%+ (overlapping windows sweep the entire duration). Lower
     *    `minDiffsPerWindow` to 25 in this mode since 50% overlap doubles
     *    window count and median washes noisy ones.
     *
     * 2. **Stage filter**: pass `stageFilter` to restrict computation to
     *    beats falling within specific sleep-stage segments. Standard
     *    lab-grade filter: include deep/rem/light/asleep/unknown,
     *    EXCLUDE awake. Today's 9.7 ms was likely dragged down by awake
     *    transitions within the sleep window.
     *
     * 3. **Per-stage breakdown**: pass `perStageBreakdown = true` to
     *    return per-stage RMSSD in [TimeDomainHrv.perStageRmssdMs] map.
     *    Each window labeled by its dominant stage (>60% time-share).
     *    Stages with insufficient data (<15 min Plews convention) drop
     *    from the per-stage map.
     */
    fun computeWindowed(
        beats: List<Beat>,
        windowMs: Long = DEFAULT_WINDOW_MS,
        minDiffsPerWindow: Int = MIN_DIFFS,
        minValidWindows: Int = MIN_WINDOWS,
        slidingOverlap: Float = 0f,
        stageFilter: StageFilter? = null,
        perStageBreakdown: Boolean = false,
    ): TimeDomainHrv? {
        val filtered = beats
            .filter { it.rrMs in PHYSIOLOGICAL_MIN..PHYSIOLOGICAL_MAX }
            .filter { stageFilter?.shouldInclude(it.timestampMs) ?: true }
            .sortedBy { it.timestampMs }
        if (filtered.isEmpty()) return null

        // v0.9.48 — sliding-window mode emits overlapping windows at
        // (1 - slidingOverlap) × windowMs step size. slidingOverlap = 0
        // preserves the legacy non-overlapping behavior.
        val step = (windowMs * (1f - slidingOverlap.coerceIn(0f, 0.9f))).toLong().coerceAtLeast(1L)
        val firstTs = filtered.first().timestampMs
        val lastTs = filtered.last().timestampMs
        val windows = mutableListOf<Pair<Long, Long>>()  // (windowStart, windowEnd)
        var winStart = firstTs
        while (winStart < lastTs) {
            windows += winStart to (winStart + windowMs)
            winStart += step
        }
        // Assign beats to each window (a beat may belong to multiple
        // overlapping windows). Linear scan — windows sorted, beats sorted.
        val perWindowBeats: List<MutableList<Beat>> = List(windows.size) { mutableListOf() }
        var beatIdx = 0
        for ((wIdx, w) in windows.withIndex()) {
            // Walk beats from beatIdx forward while they overlap this window
            for (i in beatIdx until filtered.size) {
                val b = filtered[i]
                if (b.timestampMs < w.first) {
                    beatIdx = i + 1
                    continue
                }
                if (b.timestampMs >= w.second) break
                perWindowBeats[wIdx].add(b)
            }
        }
        // Now compute per-window HRV
        data class WindowResult(
            val hrv: TimeDomainHrv,
            val dominantStage: String?,
        )
        val results = perWindowBeats.mapIndexedNotNull { idx, winBeats ->
            if (winBeats.size < 2) return@mapIndexedNotNull null
            val diffs = consecutiveDiffsMs(winBeats)
            if (diffs.size < minDiffsPerWindow) return@mapIndexedNotNull null
            val hrv = buildHrv(winBeats, diffs, windowCount = 1)
            val dominant = if (perStageBreakdown && stageFilter != null) {
                stageFilter.dominantStage(windows[idx].first, windows[idx].second)
            } else null
            WindowResult(hrv, dominant)
        }
        if (results.size < minValidWindows) return null

        // Median aggregation across all valid windows
        val rmssdMedian = median(results.map { it.hrv.rmssdMs })
        val sdnnMedian = median(results.map { it.hrv.sdnnMs })
        val pnn50Median = median(results.map { it.hrv.pnn50Percent })
        val pnn20Median = median(results.map { it.hrv.pnn20Percent })
        val meanRrMedian = median(results.map { it.hrv.meanRrMs })
        val totalSamples = results.sumOf { it.hrv.sampleCount }

        // Per-stage breakdown — median RMSSD per dominant-stage bucket
        val perStageMap = if (perStageBreakdown) {
            results
                .filter { it.dominantStage != null }
                .groupBy { it.dominantStage!! }
                .mapNotNull { (stage, list) ->
                    // Plews convention: need ≥3 windows (~15 min) for valid
                    // per-stage stat. Skip stages with insufficient samples.
                    if (list.size < 3) null
                    else stage to median(list.map { it.hrv.rmssdMs })
                }
                .toMap()
        } else emptyMap()

        return TimeDomainHrv(
            rmssdMs = rmssdMedian,
            sdnnMs = sdnnMedian,
            pnn50Percent = pnn50Median,
            pnn20Percent = pnn20Median,
            sampleCount = totalSamples,
            meanRrMs = meanRrMedian,
            meanHrBpm = if (meanRrMedian > 0f) 60_000f / meanRrMedian else 0f,
            windowCount = results.size,
            perStageRmssdMs = perStageMap,
        )
    }

    /**
     * v0.9.48 — stage filter for HRV computation. Maps timestamps to
     * stage labels and decides whether a given beat should be included.
     * Default policy: include asleep stages (deep/rem/light/asleep/
     * unknown), exclude awake. Per [com.uruj.data.SleepStageSegment]
     * convention.
     *
     * Built from the [com.uruj.data.SleepStageSegment] list persisted
     * via [com.uruj.data.SleepSnapshot] or freshly read from HC.
     */
    class StageFilter(
        private val segments: List<com.uruj.data.SleepStageSegment>,
    ) {
        /** Returns true if the timestamp falls in an asleep stage (or no
         *  stage segment covers it — defensive: include rather than drop). */
        fun shouldInclude(timestampMs: Long): Boolean {
            val seg = segmentAt(timestampMs) ?: return true
            return seg.isAsleep
        }

        /** Returns the dominant stage label across a [windowStartMs,
         *  windowEndMs) range — i.e. the stage occupying >60% of the
         *  window. Returns null when no stage occupies >60% (mixed
         *  window). */
        fun dominantStage(windowStartMs: Long, windowEndMs: Long): String? {
            val windowDuration = windowEndMs - windowStartMs
            if (windowDuration <= 0) return null
            val coverage = mutableMapOf<String, Long>()
            for (seg in segments) {
                val overlap = minOf(seg.endMs, windowEndMs) - maxOf(seg.startMs, windowStartMs)
                if (overlap > 0) {
                    coverage[seg.stageType] = (coverage[seg.stageType] ?: 0L) + overlap
                }
            }
            val (topStage, topCoverage) = coverage.maxByOrNull { it.value } ?: return null
            return if (topCoverage.toFloat() / windowDuration > 0.60f) topStage else null
        }

        private fun segmentAt(timestampMs: Long): com.uruj.data.SleepStageSegment? {
            // Binary-search optimization possible; linear is fine here
            // since segments per night is small (typically <50).
            for (seg in segments) {
                if (timestampMs in seg.startMs until seg.endMs) return seg
            }
            return null
        }
    }

    /**
     * Returns the list of true consecutive-pair RR diffs (ms). Walks the
     * sorted beat list, keeps only pairs that pass:
     *
     * 1. Timestamp consecutiveness: `actualGap = curr.timestamp - prev.timestamp`
     *    should be close to `expectedGap = curr.rrMs`. We allow up to 30% jitter
     *    OR 150 ms (whichever is larger). This is generous enough to absorb
     *    typical Android BLE scheduling jitter (30-200 ms variance is common
     *    under normal load), tight enough to reject genuine missed-beat gaps
     *    (which would be 2× RR or more).
     *
     * 2. Ectopic / artifact filter: drop pairs with >20% RR delta (likely PVC
     *    or PAC). Kubios convention.
     *
     * Within a single BLE notification, timestamps are precisely back-calculated
     * from the same arrival timestamp so cross-RR pairs always satisfy (1) at
     * 100% precision. Cross-notification pairs are where the tolerance matters.
     */
    private fun consecutiveDiffsMs(sorted: List<Beat>): List<Int> {
        if (sorted.size < 2) return emptyList()
        val diffs = mutableListOf<Int>()
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val curr = sorted[i]
            val expectedGap = curr.rrMs.toLong()
            val actualGap = curr.timestampMs - prev.timestampMs
            // Generous percentage-based tolerance — 30% of expected RR, with
            // a floor of 150 ms (so very short RR at high HR don't reject
            // legitimate pairs).
            val tolerance = maxOf(MIN_TOLERANCE_MS, (expectedGap * 0.30).toLong())
            if (abs(actualGap - expectedGap) > tolerance) continue
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

        /** Minimum floor for the timestamp-consecutiveness tolerance. The
         *  actual tolerance used per-pair is max(MIN_TOLERANCE_MS, 30% × RR).
         *  150 ms absorbs typical Android BLE scheduling jitter without
         *  accepting genuine missed-beat gaps. */
        const val MIN_TOLERANCE_MS = 150L

        /** Ectopic filter: drop consecutive pairs differing >20% of prev RR. */
        const val ECTOPIC_THRESHOLD = 0.20f

        /** Minimum clean beats required for short-window HRV (≈30s @ HR60). */
        const val MIN_BEATS = 30

        /** Minimum clean consecutive-pair diffs per window. ~30 diffs ≈ 30s
         *  of clean continuous data at HR 60 — statistically robust RMSSD. */
        const val MIN_DIFFS = 30

        /** Default windowed-mode window size: 5 minutes. Research standard
         *  for short-term HRV (Task Force 1996). */
        const val DEFAULT_WINDOW_MS = 5L * 60L * 1000L

        /** Minimum valid windows for windowed-mode result. <2 = result not
         *  statistically robust → return null instead of misleading number. */
        const val MIN_WINDOWS = 2
    }
}
