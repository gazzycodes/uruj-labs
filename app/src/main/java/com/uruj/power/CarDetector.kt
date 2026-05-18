package com.uruj.power

import com.uruj.data.ContinuousSample
import com.uruj.domain.CarInterpretation
import com.uruj.domain.CarResult
import com.uruj.domain.CarTier
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v0.7.2 — Cortisol Awakening Response detector.
 *
 * Takes a window of ContinuousSamples spanning from end-of-sleep to ~45 min
 * post-wake (caller resolves the sleep-end timestamp from
 * SleepSessionRecord via LastSleepReader) plus a pre-wake baseline window
 * (last 10 min of sleep). Returns a CarResult or null when there isn't
 * enough data to compute reliably.
 *
 * Math is intentionally simple and robust:
 *   - Baseline HR = mean of the pre-wake samples' bpm
 *   - Baseline RMSSD = HrvCalculator over the pre-wake samples (flat-RR
 *     mode, since the strap was on continuously during sleep)
 *   - Post-wake peak HR = max bpm in the 0-45 min post-wake window
 *   - Latency = time from sleep-end to peak HR, in minutes
 *   - Amplitude = peak − baseline
 *   - RMSSD trajectory = HrvCalculator on the first 30 min post-wake;
 *     trough RMSSD is min across 5-min bins
 *
 * Tier classification follows Clow et al. 2010 + Pruessner et al. 1997
 * consensus thresholds, adapted from cortisol-curve amplitude norms to
 * HR-response amplitude (the proxy URUJ measures).
 */
class CarDetector(
    private val hrvCalc: HrvCalculator = HrvCalculator(),
) {

    /** Pre-wake window for baseline = last 10 min of sleep. */
    private val baselineWindowMinutes = 10
    /** Post-wake window for peak detection = 45 min. */
    private val postWakeWindowMinutes = 45
    /** Required minimum samples in each window to attempt compute. */
    private val minSamplesPerWindow = 60
    /** Per-bin width for RMSSD trajectory inside the post-wake window. */
    private val rmssdBinMinutes = 5

    /**
     * Compute the CAR for one wake event.
     *
     * @param sleepEndMs the instant the user woke (from SleepSessionRecord.endTime)
     * @param preWakeSamples last 10 min of sleep (caller pre-slices)
     * @param postWakeSamples 0-45 min after wake (caller pre-slices)
     */
    fun compute(
        sleepEndMs: Long,
        preWakeSamples: List<ContinuousSample>,
        postWakeSamples: List<ContinuousSample>,
    ): CarResult? {
        if (preWakeSamples.size < minSamplesPerWindow ||
            postWakeSamples.size < minSamplesPerWindow
        ) return null

        // --- Baseline HR + RMSSD ---
        val baselineBpm = preWakeSamples.mapNotNull { it.bpm.takeIf { v -> v > 0 } }
            .map { it.toFloat() }
        if (baselineBpm.isEmpty()) return null
        val baselineHr = baselineBpm.average().toFloat()

        val baselineRr = flatRr(preWakeSamples)
        val baselineHrv = hrvCalc.computeFromFlatRr(baselineRr) ?: return null

        // --- Post-wake peak HR + latency ---
        val sortedPost = postWakeSamples.sortedBy { it.timestampMs }
        var peakHr = 0f
        var peakAtMs = sleepEndMs
        for (s in sortedPost) {
            val bpm = s.bpm.toFloat()
            if (bpm > peakHr && bpm > 0f) {
                peakHr = bpm
                peakAtMs = s.timestampMs
            }
        }
        if (peakHr <= 0f) return null
        val latencyMin = ((peakAtMs - sleepEndMs).coerceAtLeast(0L)).toFloat() / 60_000f

        // --- RMSSD trajectory: 5-min bins inside post-wake window ---
        val binMs = rmssdBinMinutes * 60L * 1000L
        val bins = mutableMapOf<Long, MutableList<ContinuousSample>>()
        for (s in sortedPost) {
            val idx = (s.timestampMs - sleepEndMs) / binMs
            bins.getOrPut(idx) { mutableListOf() }.add(s)
        }
        val perBinRmssd = bins.values.mapNotNull { binSamples ->
            val rr = flatRr(binSamples)
            hrvCalc.computeFromFlatRr(rr)?.rmssdMs
        }
        val troughRmssd = perBinRmssd.minOrNull() ?: baselineHrv.rmssdMs
        val rmssdDropPct = if (baselineHrv.rmssdMs > 0f) {
            ((baselineHrv.rmssdMs - troughRmssd) / baselineHrv.rmssdMs) * 100f
        } else 0f

        val cleanBeats = sortedPost.sumOf { it.rrIntervalsMs.count { rr -> rr > 0 } }

        return CarResult(
            sessionId = UUID.randomUUID().toString().take(8),
            computedAtMs = System.currentTimeMillis(),
            sleepEndMs = sleepEndMs,
            windowEndMs = sleepEndMs + postWakeWindowMinutes * 60L * 1000L,
            baselineHrBpm = baselineHr,
            peakHrBpm = peakHr,
            amplitudeBpm = peakHr - baselineHr,
            latencyMinutes = latencyMin,
            baselineRmssdMs = baselineHrv.rmssdMs,
            troughRmssdMs = troughRmssd,
            rmssdDropPercent = rmssdDropPct,
            sampleCountInWindow = sortedPost.size,
            cleanBeatsInWindow = cleanBeats,
        )
    }

    /**
     * Tier classification + plain-English interpretation. Healthy CAR =
     * 10-20 bpm amplitude, 20-40 min latency. Blunted CAR (<5 bpm, late
     * latency) is the chronic-stress marker we want to flag.
     */
    fun interpret(r: CarResult): CarInterpretation {
        val amplitudeTier = when {
            r.amplitudeBpm < 5f -> CarTier.BLUNTED
            r.amplitudeBpm < 10f -> CarTier.SUPPRESSED
            r.amplitudeBpm < 20f -> CarTier.NORMAL
            r.amplitudeBpm < 30f -> CarTier.ROBUST
            else -> CarTier.EXAGGERATED
        }
        // Latency tier: typical healthy CAR peaks 20-40 min post-wake.
        // Very fast (<10 min) or very slow (>45 min) = atypical HPA dynamics.
        val latencyTier = when {
            r.latencyMinutes < 10f -> CarTier.EXAGGERATED
            r.latencyMinutes < 20f -> CarTier.ROBUST
            r.latencyMinutes < 40f -> CarTier.NORMAL
            r.latencyMinutes < 60f -> CarTier.SUPPRESSED
            else -> CarTier.BLUNTED
        }
        // Overall = worst-case of the two. Treat BLUNTED and EXAGGERATED
        // as equivalent "atypical" severity (both reflect HPA dysregulation),
        // map by distance from NORMAL.
        val overall = pickWorse(amplitudeTier, latencyTier)
        val summary = when (overall) {
            CarTier.BLUNTED ->
                "Blunted CAR — chronic-stress / burnout pattern"
            CarTier.SUPPRESSED ->
                "Suppressed CAR — HPA-axis dampened"
            CarTier.NORMAL ->
                "Healthy CAR ✓ HPA-axis activating normally"
            CarTier.ROBUST ->
                "Robust CAR ✓ strong morning activation"
            CarTier.EXAGGERATED ->
                "Exaggerated CAR — possible acute stress or anxiety"
        }
        return CarInterpretation(
            amplitudeTier = amplitudeTier,
            latencyTier = latencyTier,
            overallTier = overall,
            summary = summary,
        )
    }

    private fun pickWorse(a: CarTier, b: CarTier): CarTier {
        // Distance-from-NORMAL ranking; NORMAL is best, both BLUNTED and
        // EXAGGERATED are worst (in opposite directions).
        val rank = mapOf(
            CarTier.NORMAL to 0,
            CarTier.ROBUST to 1,
            CarTier.SUPPRESSED to 2,
            CarTier.EXAGGERATED to 3,
            CarTier.BLUNTED to 4,
        )
        return if ((rank[a] ?: 0) >= (rank[b] ?: 0)) a else b
    }

    private fun flatRr(samples: List<ContinuousSample>): List<Int> {
        val rr = mutableListOf<Int>()
        for (sample in samples.sortedBy { it.timestampMs }) {
            for (v in sample.rrIntervalsMs) {
                if (v > 0) rr += v
            }
        }
        return rr
    }
}
