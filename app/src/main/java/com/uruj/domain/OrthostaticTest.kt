package com.uruj.domain

import kotlinx.serialization.Serializable

/**
 * v0.7.1 — Orthostatic autonomic test result.
 *
 * Protocol: 2-min seated, then sharp stand transition, then 2-min standing.
 * The strap (Magene H613 via 24/7 BiometricService) captures RR intervals
 * to NDJSON throughout. After the test ends, we slice the NDJSON by the
 * test timestamps and compute the two windows independently.
 *
 * Reference ranges synthesized from:
 *   - Klivington 1995 (orthostatic HR-response in athletes)
 *   - Plews et al. (elite endurance autonomic adaptation)
 *   - Polar / Suunto coaching algorithms (consumer adaptation)
 *
 * HR delta (standing HR - seated HR):
 *   <10 bpm   → minimal parasympathetic withdrawal (very well-rested elite)
 *   10-15 bpm → healthy autonomic flexibility
 *   15-25 bpm → moderate autonomic strain (fatigue, dehydration)
 *   >25 bpm   → significant autonomic strain (over-trained, illness)
 *
 * RMSSD ratio (standing/seated):
 *   >0.7      → suspicious — may indicate measurement artifact
 *   0.4-0.7   → normal autonomic flexibility
 *   0.3-0.4   → suppressed standing HRV (fatigue or over-reach)
 *   <0.3      → severe parasympathetic suppression on standing
 *
 * Interpretation: an over-trained athlete shows BOTH high HR delta AND low
 * RMSSD ratio. A well-rested athlete shows low HR delta + RMSSD ratio in
 * the 0.4-0.7 range. Trend matters more than absolute values — track week
 * over week.
 */
@Serializable
data class OrthostaticTestResult(
    val sessionId: String,
    val startedAtMs: Long,
    val seatedStartMs: Long,
    val seatedEndMs: Long,
    val standingStartMs: Long,
    val standingEndMs: Long,
    val seatedMeanHrBpm: Float,
    val seatedRmssdMs: Float,
    val seatedSampleCount: Int,
    val standingMeanHrBpm: Float,
    val standingRmssdMs: Float,
    val standingSampleCount: Int,
    val hrDeltaBpm: Float,
    val rmssdRatio: Float,
)

/** Tier labels derived from a result. Used for UI color-coding + interpretation. */
data class OrthostaticInterpretation(
    val hrDeltaTier: AutonomicTier,
    val rmssdRatioTier: AutonomicTier,
    val overallTier: AutonomicTier,
    val summary: String,
)

enum class AutonomicTier {
    ELITE,
    HEALTHY,
    MODERATE_STRAIN,
    SIGNIFICANT_STRAIN,
    SEVERE_STRAIN,
}
