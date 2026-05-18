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
        val seatedBeats = samplesToBeats(seatedSamples)
        val standingBeats = samplesToBeats(standingSamples)

        val seatedHrv = hrvCalc.compute(seatedBeats) ?: return null
        val standingHrv = hrvCalc.compute(standingBeats) ?: return null

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
        )
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

    private fun samplesToBeats(samples: List<ContinuousSample>): List<HrvCalculator.Beat> {
        val beats = mutableListOf<HrvCalculator.Beat>()
        for (sample in samples) {
            var t = sample.timestampMs
            for (i in sample.rrIntervalsMs.indices.reversed()) {
                val rr = sample.rrIntervalsMs[i]
                if (rr <= 0) continue
                beats.add(HrvCalculator.Beat(timestampMs = t, rrMs = rr))
                t -= rr
            }
        }
        return beats.sortedBy { it.timestampMs }
    }
}
