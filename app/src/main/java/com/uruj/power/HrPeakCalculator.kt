package com.uruj.power

/**
 * Single source of truth for the rider's "observed peak HR" over a window — the
 * "30d PEAK / athletic ceiling" shown on the Bio Lab Heart Rate card.
 *
 * WHY THIS EXISTS (v0.9.77 — task #11):
 * Bio Lab used to derive the 30d peak ONLY from Health Connect samples (the
 * Samsung band's wrist PPG). But the rider's most ACCURATE heart-rate sensor is
 * the chest strap, and it only records during RIDES — exactly the hardest efforts.
 * So the strap's true ride peaks never reached the "hardest observed effort"
 * metric: a ride that peaked 177 bpm on the strap displayed as 174 (Samsung's
 * lower wrist reading). This merges BOTH sources so the ceiling reflects the
 * genuine hardest beat from the best available sensor.
 *
 * ARTIFACT GUARD: both sources can throw spurious highs (chest-strap dry-electrode
 * / cadence-lock spikes; Samsung motion artifacts). A real max HR essentially
 * never exceeds the age-predicted max by a wide margin, so any candidate above a
 * rider-aware physiological ceiling is rejected. A single artifact beat can
 * therefore never define the displayed ceiling.
 *
 * SCOPE: this drives the DISPLAYED peak only. The profile max-HR auto-detect /
 * write-back (which powers every Karvonen zone + hrTSS) deliberately stays on its
 * robust Health-Connect path (median of the top 1% of samples, see [HrAnalyzer]) —
 * a single strap beat must never silently mutate the rider's zone ceiling.
 */
object HrPeakCalculator {

    /** Hard human-plausibility ceiling. Beyond this is sensor artifact, never a
     *  real heartbeat (highest verified human HR is ~220 in elite youth; a
     *  chest-strap double-count jumps to 230-255). */
    const val ABSOLUTE_CEILING_BPM = 215

    /** Lowest value that can count as an "effort peak" (rejects zero/garbage). */
    const val FLOOR_BPM = 35

    /**
     * Rider-aware artifact ceiling. A genuine max rarely exceeds the age-predicted
     * max (220 − age) by more than ~16 bpm (≈1.3× the formula's ±12 bpm SD).
     * Clamped to [195, [ABSOLUTE_CEILING_BPM]] so we never reject a believable high
     * effort (younger or fitter riders) nor accept an implausible artifact.
     */
    fun credibleCeiling(ageYears: Int): Int =
        (220 - ageYears + 16).coerceIn(195, ABSOLUTE_CEILING_BPM)

    /**
     * The observed peak HR across all sensors: Health Connect (band) samples +
     * chest-strap ride peaks. Each candidate is filtered to
     * [[FLOOR_BPM], [credibleCeiling]] so an artifact spike from either sensor
     * can't define the ceiling. Single pass, no intermediate allocation
     * (hcSamples can be tens of thousands of points over 30 days).
     *
     * @return the highest credible beat, or null if no credible candidate exists.
     */
    fun observedPeak(
        hcSamples: List<Int>,
        rideMaxes: List<Int>,
        ageYears: Int,
    ): Int? {
        val ceiling = credibleCeiling(ageYears)
        var peak: Int? = null
        for (v in hcSamples) {
            if (v in FLOOR_BPM..ceiling && (peak == null || v > peak)) peak = v
        }
        for (v in rideMaxes) {
            if (v in FLOOR_BPM..ceiling && (peak == null || v > peak)) peak = v
        }
        return peak
    }
}
