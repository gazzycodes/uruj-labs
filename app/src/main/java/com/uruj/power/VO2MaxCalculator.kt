package com.uruj.power

/**
 * VO2 Max estimation using two cross-validated formulas. Neither is lab-grade
 * (only a metabolic cart in a sports science lab gives "true" VO2 max), but
 * both correlate well enough with actual measurements (~85–90%) that Garmin /
 * Fitbit / Wahoo all use one or both as their internal estimate.
 *
 *   1. Uth–Sørensen (HR-based):     VO2max ≈ 15 × (HRmax / HRrest)
 *   2. Power-based (Garmin-style):  VO2max ≈ 10.8 × (FTP / weight_kg) + 7
 *
 * The two are cross-validated — when they agree, confidence is high. When they
 * diverge significantly, one of the input parameters is off (most often: FTP
 * default is too generous, or HR baselines are not yet established).
 */
class VO2MaxCalculator {

    data class Result(
        val hrBased: Float?,
        val powerBased: Float?,
        /** Average of available estimates, the "consensus" value we display. */
        val consensus: Float?,
        /** Sports-science classification (Cooper Institute style for men/women). */
        val classification: String,
    )

    fun compute(
        hrMaxBpm: Int?,
        hrRestBpm: Int?,
        ftpWatts: Int?,
        bodyWeightKg: Float?,
    ): Result {
        val hrBased = if (hrMaxBpm != null && hrRestBpm != null && hrRestBpm > 0) {
            15f * (hrMaxBpm.toFloat() / hrRestBpm.toFloat())
        } else null

        val powerBased = if (ftpWatts != null && bodyWeightKg != null && bodyWeightKg > 0f) {
            10.8f * (ftpWatts.toFloat() / bodyWeightKg) + 7f
        } else null

        val consensus = when {
            hrBased != null && powerBased != null -> (hrBased + powerBased) / 2f
            hrBased != null -> hrBased
            powerBased != null -> powerBased
            else -> null
        }

        return Result(
            hrBased = hrBased,
            powerBased = powerBased,
            consensus = consensus,
            classification = classify(consensus),
        )
    }

    /** Cooper Institute classification — generic adult male reference ranges.
     *  For absolute precision, gender + age-stratified tables are needed; this is
     *  a directional descriptor good enough for the user to know if they're
     *  trending toward elite or sedentary. */
    private fun classify(vo2: Float?): String {
        if (vo2 == null) return "—"
        // v0.9.83 — THRESHOLDS UNIFIED. This table used >= 55 for "Elite (top 5%)"
        // while Vo2SnapshotRepository:170, Vo2TrendScreen and BioLabInfoDialogs all
        // used >= 60. This function drives the BioLab hero label, so a value of 57
        // rendered as "Elite (top 5%)" on the card and "Excellent" everywhere else
        // — the same number, two verdicts, in one app. The >= 60 table is the
        // Cooper-consistent one; it wins.
        return when {
            vo2 >= 60 -> "Elite (top 5%)"
            vo2 >= 52 -> "Excellent"
            vo2 >= 47 -> "Good"
            vo2 >= 42 -> "Above average"
            vo2 >= 37 -> "Average"
            vo2 >= 33 -> "Below average"
            else -> "Poor — build aerobic base"
        }
    }
}
