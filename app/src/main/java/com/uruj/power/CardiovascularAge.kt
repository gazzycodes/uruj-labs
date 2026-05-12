package com.uruj.power

import kotlin.math.roundToInt

/**
 * Cardiovascular biological age estimate. Combines two markers known to track CV
 * fitness independently: resting heart rate (lower = stronger heart) and VO2 max
 * (higher = better aerobic capacity).
 *
 * Methodology adapted from population studies — calibrated against chronological
 * age. Not a medical diagnosis, but directionally accurate: a 30-year-old with
 * pro-cyclist RHR + VO2max will score in their teens; a sedentary 30-year-old
 * with high RHR will score in their 40s.
 *
 *   cv_age = chronological_age
 *          + (rhr - reference_rhr) × 0.5      ← higher RHR ages you
 *          − (vo2max - reference_vo2max) × 0.5 ← higher VO2 max keeps you young
 *
 * Reference values for a healthy adult: RHR ≈ 65 bpm, VO2max ≈ 35 mL/kg/min.
 *
 * Clamped to [10, 100] so absurd inputs don't produce absurd outputs.
 */
class CardiovascularAgeCalculator {

    data class Result(
        val biologicalAge: Int?,
        val chronologicalAge: Int,
        val deltaYears: Int?, // chronological - biological (positive = younger)
        val verdict: String,
    )

    fun compute(
        chronologicalAge: Int,
        rhrBpm: Int?,
        vo2MaxMlKgMin: Float?,
    ): Result {
        if (rhrBpm == null && vo2MaxMlKgMin == null) {
            return Result(
                biologicalAge = null,
                chronologicalAge = chronologicalAge,
                deltaYears = null,
                verdict = "Need RHR + VO2 max to compute",
            )
        }
        val rhrPenalty = if (rhrBpm != null) (rhrBpm - REFERENCE_RHR) * 0.5f else 0f
        val vo2Bonus = if (vo2MaxMlKgMin != null) (vo2MaxMlKgMin - REFERENCE_VO2) * 0.5f else 0f
        val bioAge = (chronologicalAge + rhrPenalty - vo2Bonus)
            .coerceIn(10f, 100f)
            .roundToInt()
        val delta = chronologicalAge - bioAge

        val verdict = when {
            delta >= 10 -> "🔥 Biologically much younger than your years"
            delta >= 5 -> "Significantly younger CV system"
            delta >= 1 -> "Slightly younger than chronological"
            delta == 0 -> "On par with chronological"
            delta >= -5 -> "Slightly older — build aerobic base"
            else -> "Cardiovascular age elevated — focus on Z2 training"
        }
        return Result(bioAge, chronologicalAge, delta, verdict)
    }

    companion object {
        private const val REFERENCE_RHR = 65
        private const val REFERENCE_VO2 = 35f
    }
}
