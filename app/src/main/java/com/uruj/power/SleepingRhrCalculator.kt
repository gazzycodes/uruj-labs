package com.uruj.power

import com.uruj.domain.SensorSource
import java.time.Instant

/**
 * Athletic sleeping RHR — for each detected sleep night, find the LOWEST
 * SUSTAINED 5-minute mean HR within that night, then return both the median
 * across nights AND the most-recent night's value separately.
 *
 * Used by Bio Lab (median for display) and Readiness scoring (most-recent for
 * "today" + median for "7d baseline"). Centralised so both screens compute
 * RHR with the same definition — eliminates the v0.2.9 inconsistency where
 * Bio Lab showed 50 and Readiness showed 55 because they used different
 * proxy algorithms.
 *
 * v0.9.83 — THE STATISTIC IS NOW ACTUALLY SUSTAINED. Read this before changing it.
 *
 * Until v0.9.83 this class returned `nightSamples.min()` — the SINGLE LOWEST
 * BEAT of the night — while this very comment claimed it matched "Garmin /
 * Whoop's definition: the lowest sustained HR". It did not. At rest, with
 * respiratory sinus arrhythmia, the single lowest sample is just the longest
 * RR interval of the night: a breathing trough, not a heart rate.
 *
 * Measured on this athlete, 2026-08-28 sleep window, 65,162 strap samples:
 *   single lowest sample .......... 43 bpm   <- what the app used to report
 *   lowest 5-min rolling mean ..... 49.5 bpm <- what it reports now
 *   lowest 30-min rolling mean .... 50.9 bpm <- Garmin's literal definition
 * Across 26 clean nights the single-min median was 42 and the 5-min median
 * 48.8, so the old number understated resting HR by 6-8 bpm on every night.
 *
 * That error did not stay local. VO2max is 15 x maxHR / RHR, and
 * dVO2/dRHR = -1.51 per bpm versus dVO2/dmaxHR = +0.35 — 4.3x more sensitive
 * to the input that was wrong. It reported 64.9 ("Elite, top 5%") where the
 * corrected input gives ~57 ("Excellent"). Karvonen zones were shifted ~3 bpm
 * low for the same reason.
 *
 * WHY 5 MINUTES and not 30: 30 min is Garmin's published definition and is the
 * more conservative choice, but it needs 30 contiguous covered minutes, which
 * this athlete's strap coverage cannot guarantee on every night. 5 min is long
 * enough to average out respiratory sinus arrhythmia (a breath cycle is ~4-6 s)
 * and short enough to survive real coverage. [SUSTAINED_WINDOW_MINUTES] is the
 * single place to change it; the value used is reported in [Result.statistic].
 *
 * PLAUSIBILITY GATE: a night whose median HR exceeds [MAX_PLAUSIBLE_SLEEP_BPM]
 * is not a sleep night — it is corrupt data — and is rejected outright rather
 * than averaged in. Nine such nights exist in this athlete's history (e.g.
 * 2026-08-15 sat above 140 bpm from 02:14 to 16:20, straight through a scored
 * sleep session). Rejections are counted in [Result.rejectedNights] so a
 * silently shrinking sample can never masquerade as a clean one.
 */
class SleepingRhrCalculator {

    data class Result(
        /** Median of nightly sustained values — robust against single-night outliers. */
        val medianBpm: Int,
        /** Lowest sustained 5-min mean during the most recent sleep night. */
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
        /** v0.9.83 — PROVENANCE. Which statistic produced these numbers, so a
         *  stored value can always be re-derived and compared like-for-like.
         *  "sustained-5min" normally; "single-min-fallback" when a night had
         *  too few contiguous covered minutes to form a rolling window. */
        val statistic: String = STAT_SUSTAINED,
        /** v0.9.83 — the OLD single-lowest-beat value for the most recent night.
         *  Kept so the v0.9.82-and-earlier history can be reconciled against the
         *  new series rather than showing a phantom step. Not for display. */
        val mostRecentNightSingleMinBpm: Int? = null,
        /** v0.9.83 — fraction of the most recent sleep window that actually had
         *  usable samples (0..1). Below [MIN_COVERAGE_FOR_TREND] the value is
         *  still returned but must not be trended. */
        val mostRecentNightCoverage: Float = 0f,
        /** v0.9.83 — nights thrown out by the plausibility gate. If this is
         *  non-zero the athlete has corrupt sensor days and should be told. */
        val rejectedNights: Int = 0,
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

        var rejected = 0
        val perNight = sleepWindows.mapNotNull { (start, end) ->
            // Try strap first (per-second precision)
            val strapStat = sustainedForWindow(strapSamples, start, end)
            val strapCount = strapSamples.count { (t, _) -> !t.isBefore(start) && !t.isAfter(end) }
            val winLengthSec = java.time.Duration.between(start, end).seconds.coerceAtLeast(1)
            val strapDensity = strapCount.toFloat() / winLengthSec.toFloat() // ~1.0 for full coverage
            if (strapStat != null && strapDensity >= COVERAGE_THRESHOLD) {
                if (!strapStat.plausible) { rejected++; return@mapNotNull null }
                return@mapNotNull NightMin(end, strapStat, SensorSource.STRAP)
            }
            // Fall back to HC
            val hcStat = sustainedForWindow(hcSamples, start, end)
            if (hcStat != null) {
                // If strap had some data but didn't pass coverage, this is
                // technically MIXED. But we use HC value (more samples), so
                // call it BAND-leaning. Source = BAND.
                if (!hcStat.plausible) { rejected++; return@mapNotNull null }
                return@mapNotNull NightMin(end, hcStat, SensorSource.BAND)
            }
            // Strap had partial coverage but HC empty — accept strap as MIXED.
            if (strapStat != null) {
                if (!strapStat.plausible) { rejected++; return@mapNotNull null }
                return@mapNotNull NightMin(end, strapStat, SensorSource.MIXED)
            }
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

        val sortedMins = medianPool.map { it.stat.sustainedBpm }.sorted()
        val median = if (sortedMins.size % 2 == 1) {
            sortedMins[sortedMins.size / 2]
        } else {
            (sortedMins[sortedMins.size / 2 - 1] + sortedMins[sortedMins.size / 2]) / 2
        }
        val mostRecent = perNight.maxBy { it.endTime }
        val breakdown = perNight.groupingBy { it.source }.eachCount()
        return Result(
            medianBpm = median,
            mostRecentNightBpm = mostRecent.stat.sustainedBpm,
            mostRecentNightEndTime = mostRecent.endTime,
            nightsCount = medianPool.size,
            mostRecentNightSource = mostRecent.source,
            sourceBreakdown = breakdown,
            medianSource = if (usePureStrap) SensorSource.STRAP else SensorSource.MIXED,
            medianIsSourcePure = usePureStrap,
            statistic = mostRecent.stat.statistic,
            mostRecentNightSingleMinBpm = mostRecent.stat.singleMinBpm,
            mostRecentNightCoverage = mostRecent.stat.coverage,
            rejectedNights = rejected,
        )
    }

    /**
     * v0.9.83 — the real statistic: the LOWEST SUSTAINED heart rate of the night.
     *
     * Samples are binned to per-minute means (which averages out respiratory
     * sinus arrhythmia), then the lowest mean over [SUSTAINED_WINDOW_MINUTES]
     * CONTIGUOUS minutes is taken. Contiguity is required — a "5-minute mean"
     * stitched across a two-hour coverage gap is not a sustained heart rate.
     *
     * Falls back to the lowest single MINUTE (still not a single beat) when the
     * night has no run of contiguous covered minutes long enough, and says so in
     * [NightStat.statistic] rather than silently returning a different quantity.
     *
     * Returns null when there is too little data to say anything at all.
     */
    private fun sustainedForWindow(
        samples: List<Pair<Instant, Int>>,
        start: Instant,
        end: Instant,
    ): NightStat? {
        val valid = samples.filter { (t, bpm) ->
            !t.isBefore(start) && !t.isAfter(end) && bpm >= GLITCH_FLOOR_BPM
        }
        if (valid.size < MIN_SAMPLES_PER_NIGHT) return null

        // Per-minute means. Bucket key is absolute epoch-minute so contiguity is
        // testable by simple difference.
        val buckets = HashMap<Long, IntArray>()          // key -> [sum, count]
        for ((t, bpm) in valid) {
            val k = t.toEpochMilli() / 60_000L
            val acc = buckets.getOrPut(k) { IntArray(2) }
            acc[0] += bpm
            acc[1] += 1
        }
        val keys = buckets.keys.sorted()
        val means = keys.map { buckets[it]!![0].toFloat() / buckets[it]!![1] }

        val windowSec = java.time.Duration.between(start, end).seconds.coerceAtLeast(1)
        val coverage = (keys.size * 60f / windowSec).coerceIn(0f, 1f)

        // PLAUSIBILITY: is this a sleep night at all? Median across covered
        // minutes, so a short artefact burst cannot trip it but a night that
        // genuinely sat at 150 bpm for hours will.
        val medianMinute = means.sorted()[means.size / 2]
        val plausible = medianMinute <= MAX_PLAUSIBLE_SLEEP_BPM

        val singleMin = valid.minOf { it.second }
        val lowestMinute = means.min()

        // Lowest CONTIGUOUS N-minute rolling mean.
        var bestRun: Float? = null
        val n = SUSTAINED_WINDOW_MINUTES
        if (keys.size >= n) {
            for (i in 0..keys.size - n) {
                if (keys[i + n - 1] - keys[i] != (n - 1).toLong()) continue   // gap inside the run
                var sum = 0f
                for (j in i until i + n) sum += means[j]
                val m = sum / n
                if (bestRun == null || m < bestRun!!) bestRun = m
            }
        }

        return if (bestRun != null) {
            NightStat(
                sustainedBpm = Math.round(bestRun!!),
                singleMinBpm = singleMin,
                coverage = coverage,
                plausible = plausible,
                statistic = STAT_SUSTAINED,
            )
        } else {
            NightStat(
                sustainedBpm = Math.round(lowestMinute),
                singleMinBpm = singleMin,
                coverage = coverage,
                plausible = plausible,
                statistic = STAT_SINGLE_MINUTE_FALLBACK,
            )
        }
    }

    /** One night's resolved statistics, with the provenance to audit them later. */
    private data class NightStat(
        val sustainedBpm: Int,
        val singleMinBpm: Int,
        val coverage: Float,
        val plausible: Boolean,
        val statistic: String,
    )

    private data class NightMin(val endTime: Instant, val stat: NightStat, val source: SensorSource)

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

        /** v0.9.83 — length of the sustained window, in contiguous covered
         *  minutes. Garmin publishes 30; 5 is used here because it survives
         *  this athlete's real strap coverage while still averaging out
         *  respiratory sinus arrhythmia (a breath cycle is ~4-6 s). Change
         *  HERE and nowhere else — the value in force is reported through
         *  [Result.statistic]. */
        const val SUSTAINED_WINDOW_MINUTES = 5

        /** v0.9.83 — a sleep window whose MEDIAN covered minute exceeds this is
         *  not sleep; it is corrupt sensor data, and it is rejected rather than
         *  averaged in. 100 bpm is far above any plausible sleeping heart rate
         *  (this athlete's clean nights median 46-70) and far below the 140-160
         *  seen on the nine known corrupt nights, so the gate cannot misfire on
         *  a genuinely poor night's sleep. */
        const val MAX_PLAUSIBLE_SLEEP_BPM = 100f

        /** Below this the sample is a dropout or a sensor glitch, not a beat.
         *  ~22% of this strap's raw samples are `bpm:0, contactDetected:false`. */
        const val GLITCH_FLOOR_BPM = 35

        /** Minimum usable samples before a night is allowed to produce a value. */
        const val MIN_SAMPLES_PER_NIGHT = 5

        /** Below this fraction of the sleep window covered, the value is still
         *  returned but MUST NOT be trended — see [Result.mostRecentNightCoverage]. */
        const val MIN_COVERAGE_FOR_TREND = 0.5f

        /** Provenance tags for [Result.statistic]. */
        const val STAT_SUSTAINED = "sustained-5min"
        const val STAT_SINGLE_MINUTE_FALLBACK = "single-min-fallback"
    }
}
