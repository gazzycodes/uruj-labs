package com.uruj.power

import kotlin.math.ln

/**
 * v0.9.74 — SINGLE SOURCE OF TRUTH for "is today's overnight HRV suppressed for
 * THIS rider?" Personal-baseline-relative + self-recalibrating. Replaces the
 * fixed absolute RMSSD thresholds (`<12/<15/<18/<20/<25 ms`) that were scattered
 * across the readiness engine, the score, the tier-ceiling, the unlock-day
 * estimates, and the UI — and that mis-fired on this rider's constitutional
 * ~13.8 ms baseline (flipped his recommendation Z2→Z1 on a 1 ms wobble).
 *
 * ## Why (deep-research wl2shde68, 2026-06-13 — see
 * `docs/research/2026-06-13-hrv-personal-baseline-readiness.md`)
 *
 * - Absolute RMSSD is NOT comparable between individuals: ~52-64% genetically
 *   heritable, healthy range ~19-75 ms, confounded by posture/respiration/method.
 *   A fixed cross-individual cutoff is scientifically unsound (Shaffer & Ginsberg
 *   2017; Nederend 2016; Sammito 2024).
 * - Peer-reviewed best practice (Plews 2016/2012, Buchheit, Schmitt 2015): compare
 *   the **7-day rolling mean of Ln rMSSD** to the individual's OWN baseline, using
 *   a "smallest worthwhile change" of **0.5 × the rider's own CV**. Auto-recalibrates
 *   as the baseline shifts (e.g. nicotine taper / sleep / fitness raise it).
 * - **Parasympathetic saturation:** in a fit athlete with a low resting HR, a low /
 *   falling RMSSD can reveal HIGH vagal tone (the opposite of fatigue), not
 *   suppression. So a low reading must be CORROBORATED by an elevated RHR before it
 *   counts as severe — never read HRV alone (Plews 2016: lowest-HRV rower had the
 *   lowest RHR and won).
 *
 * ## Method
 *
 * Work in Ln rMSSD (the literature standard; raw RMSSD is right-skewed). For the
 * small CVs seen in practice, CV(raw) ≈ SD(Ln), so the rider's `cvPercent` is used
 * directly as the Ln-space SD. Deviation is expressed in the rider's OWN standard
 * deviations below baseline:
 *
 *   deviation = (ln(today) − ln(baseline)) / (cvPercent/100)
 *
 *   ≥ −0.5 SD          → NORMAL  (within his smallest-worthwhile-change band)
 *   −0.5 .. −1.5 SD    → MILD    (mildly below his own normal)
 *   < −1.5 SD          → SEVERE only if RHR is also elevated (corroborated);
 *                        otherwise capped at MILD (likely benign vagal saturation)
 *   < −2.5 SD          → SEVERE regardless (extreme crash = illness/overreach safety)
 *
 * Pure, dependency-free, unit-testable. Consumers (engine flags, tier ceiling,
 * score cap, unlock-day estimate, card/rationale copy) map the [Verdict] onto their
 * own scales — none of them hold their own HRV threshold.
 */
object HrvReadiness {

    enum class Verdict {
        /** Today is within the rider's own normal range. Not a constraint. */
        NORMAL,
        /** Mildly below the rider's own baseline — caution, not a block. */
        MILD,
        /** Clearly below baseline AND corroborated (RHR up) OR an extreme crash. */
        SEVERE,
        /** Baseline not yet established (< [MIN_DAYS] nights / no CV). Caller should
         *  fall back to its day-1..6 absolute-tier logic. */
        NO_BASELINE,
    }

    data class Assessment(
        val verdict: Verdict,
        /** Deviation from baseline in the rider's OWN standard deviations
         *  (negative = below baseline). Null when [Verdict.NO_BASELINE]. */
        val deviationSds: Float?,
        /** The personal baseline used (7-day rolling mean RMSSD, ms). */
        val baselineMs: Float?,
        /** True when today is below baseline but RHR is NOT elevated — the
         *  parasympathetic-saturation pattern (likely high vagal tone, benign). */
        val saturationLikely: Boolean,
        /** Human-readable, baseline-relative label for the card + rationale.
         *  Never says a population number. */
        val label: String,
    )

    /** Smallest-worthwhile-change edge (Plews: 0.5 × individual CV). Below this = MILD. */
    private const val MILD_SD = 0.5f
    /** Clearly outside the normal range. Below this + corroboration = SEVERE. */
    private const val SEVERE_SD = 1.5f
    /** Extreme crash — SEVERE regardless of corroboration (illness/overreach safety). */
    private const val EXTREME_SD = 2.5f
    /** RHR delta (bpm vs baseline) that corroborates genuine suppression. */
    private const val RHR_ELEVATED_DELTA = 3
    /** Minimum nights of baseline data before personal comparison is valid. */
    const val MIN_DAYS = 7

    /**
     * @param todayMs    today's overnight RMSSD (ms). Prefer the 7-day-smoothed
     *                   value when available; a single night is noisier.
     * @param baselineMs personal baseline = the rider's own 7-day rolling mean RMSSD.
     * @param cvPercent  the rider's own day-to-day coefficient of variation of RMSSD (%).
     * @param rhrDelta   today's RHR − baseline RHR (bpm); null if unknown. Saturation guard.
     * @param daysOfData nights of HRV in the baseline window; < [MIN_DAYS] → NO_BASELINE.
     */
    fun assess(
        todayMs: Float?,
        baselineMs: Float?,
        cvPercent: Float?,
        rhrDelta: Int?,
        daysOfData: Int,
    ): Assessment {
        if (todayMs == null || baselineMs == null || baselineMs < 1f ||
            cvPercent == null || cvPercent <= 0f || daysOfData < MIN_DAYS
        ) {
            return Assessment(
                verdict = Verdict.NO_BASELINE,
                deviationSds = null,
                baselineMs = baselineMs,
                saturationLikely = false,
                label = "baseline still establishing — judged on trend, not a fixed cutoff",
            )
        }

        val sdLn = cvPercent / 100f // CV(raw) ≈ SD(Ln) for the small CVs seen here
        val deviation = ((ln(todayMs.toDouble()) - ln(baselineMs.toDouble())) / sdLn).toFloat()

        val rhrElevated = rhrDelta != null && rhrDelta >= RHR_ELEVATED_DELTA
        val belowBaseline = deviation < 0f
        val saturationLikely = belowBaseline && !rhrElevated

        val baseStr = "%.1f".format(baselineMs)

        return when {
            deviation >= -MILD_SD -> Assessment(
                Verdict.NORMAL, deviation, baselineMs, saturationLikely = false,
                label = "within your normal range (baseline ~$baseStr ms)",
            )

            deviation < -EXTREME_SD -> Assessment(
                Verdict.SEVERE, deviation, baselineMs, saturationLikely = false,
                label = "well below your $baseStr ms baseline — possible illness / overreach",
            )

            deviation < -SEVERE_SD && rhrElevated -> Assessment(
                Verdict.SEVERE, deviation, baselineMs, saturationLikely = false,
                label = "below your $baseStr ms baseline + RHR elevated — genuine suppression",
            )

            deviation < -SEVERE_SD -> Assessment(
                // Below severe edge but RHR calm → parasympathetic saturation more
                // likely than fatigue. Cap at MILD, don't slam the brakes.
                Verdict.MILD, deviation, baselineMs, saturationLikely = true,
                label = "below your $baseStr ms baseline but RHR calm — likely high vagal tone, not fatigue",
            )

            else -> Assessment(
                Verdict.MILD, deviation, baselineMs, saturationLikely = saturationLikely,
                label = "mildly below your $baseStr ms baseline — easy day",
            )
        }
    }
}
