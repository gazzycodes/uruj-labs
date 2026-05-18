package com.uruj.power

import com.uruj.domain.SensorSource
import java.time.Duration
import java.time.Instant

/**
 * Heart Rate Recovery (HRR1) — the drop in heart rate during the first 60 seconds
 * after stopping a hard effort. One of the strongest peer-reviewed predictors of
 * autonomic nervous system health and all-cause mortality (Cole et al. NEJM 1999,
 * subsequently confirmed in Framingham + dozens of follow-up cohorts).
 *
 * Clinical thresholds (Cole et al.):
 *   ≥ 18 bpm drop — excellent recovery, strong vagal reactivation
 *   12 – 17 bpm  — average / normal
 *   <  12 bpm    — abnormal recovery, elevated CV mortality risk
 *
 * Unlike VO₂ max formulas (which are estimates) and "biological age" heuristics
 * (which we invented), HRR1 is a *real, measured, clinically validated* metric
 * we can derive directly from Fit Band 3 + Health Connect data — provided the
 * rider actually pushed hard enough during a workout that the recovery curve is
 * visible. Sub-130-bpm "efforts" are excluded; the HR drop in a soft warm-down
 * walk isn't physiologically meaningful.
 */
class HrRecoveryCalculator {

    data class Sample(
        val sessionEnd: Instant,
        val effortPeakBpm: Int,
        val recoveryHrBpm: Int,
        val hrr1Bpm: Int,
        val classification: String,
        /** v0.7.7 — which sensor produced this reading. STRAP when 24/7 NDJSON
         *  covered the recovery window; BAND when only HC HR samples available;
         *  MIXED when both contributed parts of the window. */
        val source: SensorSource = SensorSource.UNKNOWN_LEGACY,
    )

    data class Result(
        val samples: List<Sample>,
        /** Median HRR1 across qualifying samples — robust against single-session noise. */
        val medianHrr1: Int,
        /** Worst-of, best-of, classification of the median. */
        val medianClassification: String,
        /** v0.7.7 — count of samples per source for the UI badge.
         *  e.g. "8 strap · 3 band" or "all strap". */
        val sourceBreakdown: Map<SensorSource, Int> = emptyMap(),
    )

    /**
     * v0.7.7 — bulletproof source-aware compute.
     *
     * Per session end, we evaluate the recovery window separately against BOTH
     * the BLE strap NDJSON samples and the HC HR samples, then pick:
     *   1. STRAP — if strap window has ≥60% sample density AND a usable
     *      recovery sample in the [30s, 180s] window
     *   2. BAND — strap missing or low coverage; HC has a usable recovery sample
     *   3. skip — neither produced a valid sample
     *
     * The 60% threshold protects against false-STRAP labels when the strap
     * dropped mid-recovery: if only a few seconds of strap data exist, fall
     * back to HC for that session. Conservative — better to call it BAND than
     * to mislabel a partial reading.
     *
     * @param strapHrSamples per-second BLE NDJSON samples (preferred). Pass
     *   emptyList() if no strap available or no NDJSON data.
     * @param hcHrSamples HC HeartRateRecord batched samples (fallback).
     */
    fun compute(
        exerciseSessionEndTimes: List<Instant>,
        hcHrSamples: List<Pair<Instant, Int>>,
        strapHrSamples: List<Pair<Instant, Int>> = emptyList(),
    ): Result? {
        if (exerciseSessionEndTimes.isEmpty()) return null
        if (hcHrSamples.isEmpty() && strapHrSamples.isEmpty()) return null

        // v0.3.7: filter sessions whose recovery window overlaps another workout.
        // User had 3 rides on 2026-05-14; recovery samples for one ride could
        // land inside the next ride's warmup HR, inflating the drop. Skip any
        // session where the NEXT session starts within the recovery window.
        val sortedEnds = exerciseSessionEndTimes.sorted()
        val cleanEnds = sortedEnds.filterIndexed { idx, end ->
            val next = sortedEnds.getOrNull(idx + 1) ?: return@filterIndexed true
            val gap = Duration.between(end, next)
            gap >= MIN_SESSION_GAP
        }
        if (cleanEnds.isEmpty()) return null

        val samples = cleanEnds.mapNotNull { end ->
            // Try strap first
            val strapSample = computeOne(end, strapHrSamples, SensorSource.STRAP)
            if (strapSample != null && hasGoodCoverage(end, strapHrSamples, RECOVERY_WINDOW_START, RECOVERY_WINDOW_END, expectedCadenceMs = 1000L)) {
                return@mapNotNull strapSample
            }
            // Fall back to HC
            val hcSample = computeOne(end, hcHrSamples, SensorSource.BAND)
            if (hcSample != null) return@mapNotNull hcSample
            // If strap produced a partial reading (didn't pass coverage) AND HC
            // didn't produce anything, accept the partial strap as MIXED-leaning
            // (better than no data — and the sensor was real).
            strapSample?.copy(source = SensorSource.MIXED)
        }
        if (samples.isEmpty()) return null

        val sorted = samples.map { it.hrr1Bpm }.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        }
        val breakdown = samples.groupingBy { it.source }.eachCount()
        return Result(
            samples = samples,
            medianHrr1 = median,
            medianClassification = classify(median),
            sourceBreakdown = breakdown,
        )
    }

    /** Compute a single HRR1 sample from one HR-sample stream for one session-end. */
    private fun computeOne(
        end: Instant,
        hrTimedSamples: List<Pair<Instant, Int>>,
        source: SensorSource,
    ): Sample? {
        if (hrTimedSamples.isEmpty()) return null
        val effortWindow = hrTimedSamples.filter { (t, _) ->
            !t.isBefore(end.minus(EFFORT_LOOKBACK)) && !t.isAfter(end)
        }
        val peak = effortWindow.maxOfOrNull { it.second } ?: return null
        if (peak < MIN_PEAK_BPM) return null

        val ideal = end.plus(Duration.ofSeconds(60))
        val recoveryWindow = hrTimedSamples.filter { (t, _) ->
            !t.isBefore(end.plus(RECOVERY_WINDOW_START)) &&
                !t.isAfter(end.plus(RECOVERY_WINDOW_END))
        }
        val recovery = recoveryWindow
            .minByOrNull { (t, _) -> kotlin.math.abs(Duration.between(ideal, t).seconds) }
            ?.second ?: return null
        val drop = peak - recovery
        if (drop !in 0..100) return null

        return Sample(
            sessionEnd = end,
            effortPeakBpm = peak,
            recoveryHrBpm = recovery,
            hrr1Bpm = drop,
            classification = classify(drop),
            source = source,
        )
    }

    /**
     * v0.7.7 — coverage check: does the sample stream have enough data points
     * in the [start, end] window to commit to that source?
     *
     * Threshold: ≥60% of expected sample count. Expected count = window length
     * divided by typical cadence. For BLE strap at ~1 Hz across 30-180s window
     * that's ~150 samples; need ≥90. For HC batched ~30s cadence, ~5 samples;
     * need ≥3. Same percentage threshold applied to both.
     */
    private fun hasGoodCoverage(
        sessionEnd: Instant,
        samples: List<Pair<Instant, Int>>,
        windowStart: Duration,
        windowEnd: Duration,
        expectedCadenceMs: Long,
    ): Boolean {
        val winStart = sessionEnd.plus(windowStart)
        val winEnd = sessionEnd.plus(windowEnd)
        val actual = samples.count { (t, _) ->
            !t.isBefore(winStart) && !t.isAfter(winEnd)
        }
        val expected = Duration.between(winStart, winEnd).toMillis() / expectedCadenceMs
        return actual.toFloat() / expected.toFloat() >= COVERAGE_THRESHOLD
    }

    private fun classify(drop: Int): String = when {
        drop >= 18 -> "Excellent — strong autonomic recovery"
        drop >= 12 -> "Average — normal recovery"
        drop >= 0 -> "Poor — elevated CV risk per Cole et al. (NEJM 1999)"
        else -> "Anomalous"
    }

    companion object {
        // Effort lookback widened from 5min → 30min because endurance riders
        // commonly cool down for several minutes before pressing stop. The
        // strict clinical HRR1 protocol assumes max effort right at the
        // termination point; real-world cyclists cool down. Capturing peak
        // within the closing 30 min still represents the working HR for
        // recovery comparison.
        private val EFFORT_LOOKBACK: Duration = Duration.ofMinutes(30)
        // Recovery window MUST stay close to Cole's 1-minute protocol. v0.2.6's
        // 10-min upper bound captured plenty of samples but produced HRR drops
        // of 50+ bpm — physiologically impossible for true HRR1 (Olympic-elite
        // tops around 35 bpm in 60s). The wider window was actually measuring
        // HRR3-HRR5 and mislabeling it as HRR1, and Cole's ≥18 excellent
        // threshold doesn't apply to a 5-min drop.
        //
        // Final window: 30-180s (3 min tolerance). Gives Fit Band 3 enough
        // slack to land a spot-check while staying close enough to 60s that
        // Cole's classification remains valid. Sessions with no sample in
        // this window are skipped — better no measurement than a wrong one.
        private val RECOVERY_WINDOW_START: Duration = Duration.ofSeconds(30)
        private val RECOVERY_WINDOW_END: Duration = Duration.ofSeconds(180)
        // Peak threshold lowered from 130 → 120 to include moderate-intensity
        // rides. Below 120 bpm the recovery curve is too shallow to be a
        // meaningful autonomic-recovery signal.
        private const val MIN_PEAK_BPM = 120
        /** Sessions that have ANOTHER session starting within this duration are
         *  skipped from HRR1 calc — their recovery sample would land in the
         *  next workout's warmup HR and produce inflated drops. 3 min is wide
         *  enough to fully clear our 180s recovery window. */
        private val MIN_SESSION_GAP: Duration = Duration.ofMinutes(3)
        /** v0.7.7 — fraction of expected samples required to commit to a
         *  source label as STRAP (vs falling back to HC or labeling MIXED). */
        private const val COVERAGE_THRESHOLD = 0.60f
    }
}
