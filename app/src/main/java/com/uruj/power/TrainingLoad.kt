package com.uruj.power

/**
 * v0.9.73 — SINGLE SOURCE OF TRUTH for HR-based Training Stress Score (hrTSS),
 * used by BOTH the TSB engine ([com.uruj.data.ReadinessRepository]) AND the
 * ride-summary display ([com.uruj.ui.summary.RideSummaryScreen]). One formula,
 * so the per-ride card can never disagree with the CTL/ATL/TSB engine.
 *
 * Banister / HR-Reserve: IF = 1.0 at threshold (0.87 of HR Reserve), squared ×
 * hours × 100. Matches the long-standing Samsung non-cycling hrTSS calc.
 *
 * WHY HR, not estimated power (v0.9.72/73 decision): URUJ has no power meter;
 * physics-estimated watts are systematically biased — they inflate on stop-start
 * city surges (each re-acceleration is a power spike) and on GPS-speed noise when
 * light-pedalling / stationary. The chest-strap HR is the rider's MEASURED
 * internal load — not fooled by wind / traffic / GPS. If a real power meter is
 * ever added, a power-TSS path can branch in alongside this (all power fields
 * stay captured in StoredRideSummary, so re-enabling is trivial).
 */
object TrainingLoad {

    /** Coggan normalization constant: HR-Reserve fraction at lactate threshold. */
    private const val THRESHOLD_HR_RESERVE_FRAC = 0.87f

    /**
     * Intensity Factor from HR Reserve. IF = (HRr fraction / 0.87), capped 1.4.
     * Returns 0 when HR <= RHR or maxHR <= RHR (invalid / sub-rest).
     */
    fun hrIntensityFactor(avgHr: Int, rhr: Int, maxHr: Int): Float {
        if (avgHr <= rhr || maxHr <= rhr) return 0f
        val hrReserveFrac = (avgHr - rhr).toFloat() / (maxHr - rhr).toFloat()
        return (hrReserveFrac / THRESHOLD_HR_RESERVE_FRAC).coerceIn(0f, 1.4f)
    }

    /** hrTSS = IF² × hours × 100. Returns 0 for invalid inputs. */
    fun hrTss(avgHr: Int, rhr: Int, maxHr: Int, hours: Float): Float {
        if (hours <= 0f) return 0f
        val intensityFactor = hrIntensityFactor(avgHr, rhr, maxHr)
        return intensityFactor * intensityFactor * hours * 100f
    }
}
