package com.uruj.power

import com.uruj.data.ContinuousSample
import com.uruj.domain.AutonomicTier
import com.uruj.domain.OrthostaticInterpretation
import com.uruj.domain.OrthostaticTestResult

/**
 * v0.7.1 — computes an OrthostaticTestResult from two windowed lists of
 * ContinuousSamples (the seated minutes and the standing minutes).
 *
 * Internally reuses HrvCalculator for the RMSSD math — same lab-grade
 * timestamp-aware consecutive-pair filter, same physiological + ectopic
 * filtering. The orthostatic test is just two short captures that get the
 * same trustworthy math the overnight pipeline uses.
 */
class OrthostaticTestCalculator(
    private val hrvCalc: HrvCalculator = HrvCalculator(),
) {

    /**
     * Compute the result. Returns null if either window lacks enough data
     * for a meaningful RMSSD (fewer than 30 clean RR diffs).
     *
     * Methodology note: uses `computeFromFlatRr` (skips timestamp-aware
     * consecutive-pair filter) because for a 2-min continuous strap-on
     * capture, we KNOW the beats are consecutive — no BLE drops or sleep-
     * stage gaps to defend against. The timestamp filter was designed for
     * overnight windowed analysis where cross-gap pairs would otherwise
     * corrupt RMSSD. Using it here over-rejects legitimate pairs due to
     * normal Android BLE delivery jitter, especially at elevated daytime
     * HR (smaller RR → tighter percentage-based tolerance). Same approach
     * Elite HRV / HRV4Training / Polar take for short captures.
     *
     * Filters that DO still apply: physiological RR range (300-2000 ms)
     * and 20% ectopic delta cap (Kubios). So we still reject sensor noise
     * and PVC/PAC artifacts — we just trust temporal consecutiveness.
     */
    fun compute(
        sessionId: String,
        startedAtMs: Long,
        seatedSamples: List<ContinuousSample>,
        seatedStartMs: Long,
        seatedEndMs: Long,
        standingSamples: List<ContinuousSample>,
        standingStartMs: Long,
        standingEndMs: Long,
    ): OrthostaticTestResult? {
        val seatedRr = flatRrList(seatedSamples)
        val standingRr = flatRrList(standingSamples)

        val seatedHrv = hrvCalc.computeFromFlatRr(seatedRr) ?: return null
        val standingHrv = hrvCalc.computeFromFlatRr(standingRr) ?: return null

        val hrDelta = standingHrv.meanHrBpm - seatedHrv.meanHrBpm
        val rmssdRatio = if (seatedHrv.rmssdMs > 0f) {
            standingHrv.rmssdMs / seatedHrv.rmssdMs
        } else 0f

        return OrthostaticTestResult(
            sessionId = sessionId,
            startedAtMs = startedAtMs,
            seatedStartMs = seatedStartMs,
            seatedEndMs = seatedEndMs,
            standingStartMs = standingStartMs,
            standingEndMs = standingEndMs,
            seatedMeanHrBpm = seatedHrv.meanHrBpm,
            seatedRmssdMs = seatedHrv.rmssdMs,
            seatedSampleCount = seatedHrv.sampleCount,
            standingMeanHrBpm = standingHrv.meanHrBpm,
            standingRmssdMs = standingHrv.rmssdMs,
            standingSampleCount = standingHrv.sampleCount,
            hrDeltaBpm = hrDelta,
            rmssdRatio = rmssdRatio,
            // v0.9.49.1 — explicit methodology tag for cache invalidation per
            // [[reference_lab_grade_architecture_rules]] Rule 2.
            methodologyVersion = CURRENT_METHODOLOGY,
        )
    }

    companion object {
        /**
         * Current orthostatic methodology — mean HR per 2-min window for
         * seated + standing, RMSSD ratio derived from windowed flat-RR
         * (Bonjer 2006 / Plews 2013 protocol). Bump when math changes
         * (e.g. excluding first-30s standing settling period would warrant
         * v0.9.50-bonjer-mean-skip30s).
         */
        const val CURRENT_METHODOLOGY = "v0.9.49.1-bonjer-mean"
    }

    /** Tier classification + plain-English interpretation. */
    fun interpret(result: OrthostaticTestResult): OrthostaticInterpretation {
        val hrTier = when {
            result.hrDeltaBpm < 10f -> AutonomicTier.ELITE
            result.hrDeltaBpm < 15f -> AutonomicTier.HEALTHY
            result.hrDeltaBpm < 25f -> AutonomicTier.MODERATE_STRAIN
            result.hrDeltaBpm < 35f -> AutonomicTier.SIGNIFICANT_STRAIN
            else -> AutonomicTier.SEVERE_STRAIN
        }
        val ratioTier = when {
            result.rmssdRatio >= 0.7f -> AutonomicTier.HEALTHY  // could be artifact at high end
            result.rmssdRatio >= 0.4f -> AutonomicTier.HEALTHY
            result.rmssdRatio >= 0.3f -> AutonomicTier.MODERATE_STRAIN
            result.rmssdRatio >= 0.2f -> AutonomicTier.SIGNIFICANT_STRAIN
            else -> AutonomicTier.SEVERE_STRAIN
        }
        // Combined tier = worse of the two
        val overall = listOf(hrTier, ratioTier).maxByOrNull { it.ordinal } ?: AutonomicTier.HEALTHY
        val summary = when (overall) {
            AutonomicTier.ELITE -> "Elite autonomic flexibility ✓"
            AutonomicTier.HEALTHY -> "Healthy autonomic response ✓"
            AutonomicTier.MODERATE_STRAIN -> "Moderate strain — recovery in progress"
            AutonomicTier.SIGNIFICANT_STRAIN -> "Significant strain — prioritize rest"
            AutonomicTier.SEVERE_STRAIN -> "Severe strain — over-reaching territory"
        }
        return OrthostaticInterpretation(
            hrDeltaTier = hrTier,
            rmssdRatioTier = ratioTier,
            overallTier = overall,
            summary = summary,
        )
    }

    /**
     * Flatten the RR arrays from a list of samples, preserving order
     * (sample-by-sample, then within each sample oldest-to-newest per BLE HRP
     * spec). Drops zero / negative RR. No timestamps — short-capture compute
     * doesn't need them.
     */
    private fun flatRrList(samples: List<ContinuousSample>): List<Int> {
        val rr = mutableListOf<Int>()
        for (sample in samples.sortedBy { it.timestampMs }) {
            for (v in sample.rrIntervalsMs) {
                if (v > 0) rr += v
            }
        }
        return rr
    }
}
