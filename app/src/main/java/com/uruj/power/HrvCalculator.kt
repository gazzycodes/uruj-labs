package com.uruj.power

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * v0.7.0 — Pure-function time-domain HRV calculator. Takes raw RR-interval
 * data (ms) from a BLE chest strap (Magene H613 etc.) and returns the
 * standard time-domain HRV metrics: RMSSD, SDNN, pNN50, mean HR.
 *
 * **What "real HRV" means here**: RMSSD computed from beat-to-beat RR
 * intervals captured by ECG-precision chest strap. NOT a PPG estimate.
 * NOT std-dev of HR samples (the "proxy" we deleted in v0.4.0). This is
 * the same calculation Polar / Kubios / EliteHRV / HRV4Training use, on
 * the same input data.
 *
 * **Why each metric**:
 * - RMSSD (root mean square of successive differences) — primary marker of
 *   parasympathetic / vagal tone. Higher = better recovery. THE metric most
 *   apps surface.
 * - SDNN (standard deviation of NN intervals) — overall HRV including
 *   sympathetic + parasympathetic. Sensitive to total autonomic activity.
 * - pNN50 (% of consecutive pairs differing by >50ms) — clinically validated
 *   parasympathetic marker. Often used alongside RMSSD.
 * - Mean RR + Mean HR — anchors the readings to a physiological context
 *   (RMSSD 50ms at HR 50 ≠ RMSSD 50ms at HR 90).
 *
 * **Physiological filtering** before computation:
 *
 * 1. **Range filter**: RR < 300ms or > 2000ms = artifact (300ms = 200 BPM,
 *    2000ms = 30 BPM — outside any plausible sustained HR). Drop them.
 *
 * 2. **Ectopic / premature-beat filter**: consecutive RR differ by >20% =
 *    likely PVC/PAC or a missed beat. Drop the outlier. Standard HRV
 *    research convention (Tarvainen et al, Kubios manual).
 *
 * **Minimum sample requirement**: 30 valid RR intervals (~30 seconds of
 * data at HR 60). Below this, RMSSD is too noisy to trust — return null
 * rather than a misleading number.
 *
 * **Stateless / pure** — call from anywhere, no Android imports. Unit-testable
 * with known clinical sample data.
 */
class HrvCalculator {

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
    )

    /**
     * @param rrIntervalsMs raw RR intervals in milliseconds from the strap
     * @return time-domain HRV metrics, or null if fewer than 30 valid samples
     *   remain after filtering.
     */
    fun compute(rrIntervalsMs: List<Int>): TimeDomainHrv? {
        if (rrIntervalsMs.isEmpty()) return null

        // Stage 1: physiological range filter (200-30 BPM bounds).
        val physiological = rrIntervalsMs.filter { it in 300..2000 }
        if (physiological.size < 2) return null

        // Stage 2: ectopic / artifact filter. Walk the sequence; drop any RR
        // that's >20% different from the previous accepted one. Standard
        // Kubios / Tarvainen convention for short-term HRV cleaning.
        val cleaned = mutableListOf<Int>()
        cleaned.add(physiological[0])
        for (i in 1 until physiological.size) {
            val prev = cleaned.last()
            val curr = physiological[i]
            val deltaPct = abs(curr - prev).toFloat() / prev
            if (deltaPct < 0.20f) cleaned.add(curr)
        }
        if (cleaned.size < MIN_SAMPLES_FOR_RMSSD) return null

        // Mean RR + Mean HR
        val meanRrMs = cleaned.map { it.toDouble() }.average().toFloat()
        val meanHrBpm = 60_000f / meanRrMs

        // RMSSD: sqrt of mean of squared consecutive differences
        val diffs = cleaned.zipWithNext { a, b -> (b - a).toFloat() }
        val rmssdMs = sqrt(diffs.map { it * it }.average().toFloat())

        // SDNN: standard deviation of all NN intervals
        val rrMean = meanRrMs.toDouble()
        val variance = cleaned.map { (it - rrMean) * (it - rrMean) }.average()
        val sdnnMs = sqrt(variance).toFloat()

        // pNN50 + pNN20: percentage of consecutive pairs above thresholds
        val gt50 = diffs.count { abs(it) > 50f }
        val gt20 = diffs.count { abs(it) > 20f }
        val pnn50 = (gt50.toFloat() / diffs.size) * 100f
        val pnn20 = (gt20.toFloat() / diffs.size) * 100f

        return TimeDomainHrv(
            rmssdMs = rmssdMs,
            sdnnMs = sdnnMs,
            pnn50Percent = pnn50,
            pnn20Percent = pnn20,
            sampleCount = cleaned.size,
            meanRrMs = meanRrMs,
            meanHrBpm = meanHrBpm,
        )
    }

    companion object {
        /** Below this, RMSSD is too noisy to report. ~30 beats = 30s at HR 60. */
        const val MIN_SAMPLES_FOR_RMSSD = 30
    }
}
