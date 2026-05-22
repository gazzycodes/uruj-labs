package com.uruj.power

import com.uruj.data.PostprandialSnapshotRepository
import com.uruj.domain.MealMark
import com.uruj.domain.PostprandialSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * v0.9.31 — Pure compute layer for postprandial HRV response.
 *
 * Stateless calculator (mirrors [HrvCalculator] / [FrequencyDomainCalculator]
 * pattern): takes pre-meal HRV + post-meal HRV results from the existing
 * 5-min-windowed compute layer + a [MealMark] → returns a comparison
 * [PostprandialSnapshot].
 *
 * Does NOT read disk or compute HRV directly — caller (BioLabRepository)
 * is responsible for invoking [com.uruj.data.ContinuousBiometricRepository.
 * computeHrvForWindow] and [computeFrequencyDomainForWindow] on the
 * window bounds defined in [PostprandialSnapshotRepository]'s constants,
 * then passing both results here.
 *
 * **Why this is its own file** (architecture-wise):
 *   - Single responsibility: takes pre/post HRV, computes deltas
 *   - Easily testable (no Android dependencies)
 *   - Future Tier B tests (caffeine, alcohol, cold exposure) follow the
 *     same EventMark + pre/post window pattern; this calculator's shape
 *     can be generalized later, but for v0.9.31 we ship postprandial
 *     concretely (don't over-abstract prematurely).
 */
class PostprandialCalculator {

    /**
     * Compute the postprandial comparison snapshot.
     *
     * @param mealMark the meal event the user marked
     * @param preHrv time-domain HRV result for the -30..-5 min pre-meal window, null if insufficient strap data
     * @param postHrv time-domain HRV result for the +45..+75 min post-meal window, null if insufficient strap data
     * @param preFreqDomain frequency-domain HRV result for the pre-meal window, null when below MIN_BEATS
     * @param postFreqDomain frequency-domain HRV result for the post-meal window, null when below MIN_BEATS
     * @param zone local timezone for date-stamping the snapshot
     * @param nowMs wall-clock epoch ms for the compute time (for `computedAtMs` provenance)
     */
    fun compute(
        mealMark: MealMark,
        preHrv: HrvCalculator.TimeDomainHrv?,
        postHrv: HrvCalculator.TimeDomainHrv?,
        preFreqDomain: FrequencyDomainCalculator.FrequencyDomainHrv?,
        postFreqDomain: FrequencyDomainCalculator.FrequencyDomainHrv?,
        zone: ZoneId = ZoneId.systemDefault(),
        nowMs: Long = System.currentTimeMillis(),
        // v0.9.33 — edge-case context from caller (BioLabRepository)
        overlapsPriorMeal: Boolean = false,
        overlapsPriorMealId: String? = null,
        isDuringSleep: Boolean = false,
    ): PostprandialSnapshot {
        val preStart = mealMark.timestampMs + PostprandialSnapshotRepository.PRE_WINDOW_START_OFFSET_MS
        val preEnd = mealMark.timestampMs + PostprandialSnapshotRepository.PRE_WINDOW_END_OFFSET_MS
        val postStart = mealMark.timestampMs + PostprandialSnapshotRepository.POST_WINDOW_START_OFFSET_MS
        val postEnd = mealMark.timestampMs + PostprandialSnapshotRepository.POST_WINDOW_END_OFFSET_MS

        val dateIsoLocal = Instant.ofEpochMilli(mealMark.timestampMs)
            .atZone(zone)
            .toLocalDate()
            .toString()

        // Source label: if either window has no data, mark "insufficient";
        // else "strap" (24/7 BLE is the only source for short windows).
        val source = when {
            preHrv == null && postHrv == null -> "insufficient"
            preHrv == null || postHrv == null -> "partial"
            else -> "strap"
        }

        // v0.9.33 — coverage % per window. Each 5-min window contributes
        // 1/(window-size-in-minutes/5) to coverage. Pre-window is 25 min
        // (max 5 windows). Post-window is 30 min (max 6 windows).
        val preCoverage = preHrv?.windowCount?.let { (it.toFloat() / 5f).coerceIn(0f, 1f) }
        val postCoverage = postHrv?.windowCount?.let { (it.toFloat() / 6f).coerceIn(0f, 1f) }

        return PostprandialSnapshot(
            mealMarkId = mealMark.id,
            mealMarkMs = mealMark.timestampMs,
            dateIsoLocal = dateIsoLocal,
            eventType = mealMark.eventType,
            note = mealMark.note,

            preWindowStartMs = preStart,
            preWindowEndMs = preEnd,
            preRmssdMs = preHrv?.rmssdMs,
            preSdnnMs = preHrv?.sdnnMs,
            preMeanHrBpm = preHrv?.meanHrBpm,
            preHfMs2 = preFreqDomain?.hfMs2,
            preLfMs2 = preFreqDomain?.lfMs2,
            preLfHfRatio = preFreqDomain?.lfHfRatio,
            preSampleCount = preHrv?.sampleCount ?: 0,

            postWindowStartMs = postStart,
            postWindowEndMs = postEnd,
            postRmssdMs = postHrv?.rmssdMs,
            postSdnnMs = postHrv?.sdnnMs,
            postMeanHrBpm = postHrv?.meanHrBpm,
            postHfMs2 = postFreqDomain?.hfMs2,
            postLfMs2 = postFreqDomain?.lfMs2,
            postLfHfRatio = postFreqDomain?.lfHfRatio,
            postSampleCount = postHrv?.sampleCount ?: 0,

            rmssdDeltaPercent = percentDelta(preHrv?.rmssdMs, postHrv?.rmssdMs),
            hrDeltaBpm = absDelta(preHrv?.meanHrBpm, postHrv?.meanHrBpm),
            hfDeltaPercent = percentDelta(preFreqDomain?.hfMs2, postFreqDomain?.hfMs2),
            lfHfDeltaPercent = percentDelta(preFreqDomain?.lfHfRatio, postFreqDomain?.lfHfRatio),

            // v0.9.33 — edge-case fields
            overlapsPriorMeal = overlapsPriorMeal,
            overlapsPriorMealId = overlapsPriorMealId,
            isDuringSleep = isDuringSleep,
            preCoveragePct = preCoverage,
            postCoveragePct = postCoverage,

            source = source,
            computedAtMs = nowMs,
            methodologyVersion = PostprandialSnapshotRepository.METHODOLOGY_VERSION,
        )
    }

    /** (post − pre) / pre × 100. Null-safe; returns null if either value is null or pre ≈ 0. */
    private fun percentDelta(pre: Float?, post: Float?): Float? {
        if (pre == null || post == null) return null
        if (kotlin.math.abs(pre) < 0.0001f) return null
        return (post - pre) / pre * 100f
    }

    /** post − pre absolute. Null-safe. */
    private fun absDelta(pre: Float?, post: Float?): Float? {
        if (pre == null || post == null) return null
        return post - pre
    }

    /**
     * Verdict tier for the RMSSD drop magnitude (negative delta = bigger drop = larger response).
     * Returns null if delta is null. Pure interpretation, no UI dependencies.
     */
    fun tier(rmssdDeltaPercent: Float?): Tier? {
        if (rmssdDeltaPercent == null) return null
        // Use absolute magnitude of the drop. Per Hara 2016, healthy
        // postprandial HRV drop is typically 15-30%. Higher = sensitive.
        val dropPct = kotlin.math.abs(rmssdDeltaPercent.coerceAtMost(0f))
        return when {
            dropPct < 15f -> Tier.STABLE
            dropPct < 30f -> Tier.TYPICAL
            dropPct < 50f -> Tier.SENSITIVE
            else -> Tier.STRONG
        }
    }

    enum class Tier(val label: String, val description: String) {
        STABLE("Stable metabolism", "minimal postprandial autonomic response"),
        TYPICAL("Typical response", "healthy autonomic engagement to food"),
        SENSITIVE("Sensitive — consider meal composition", "moderate response; review carb load + pre-ride timing"),
        STRONG("Strong response", "large meal, glucose-sensitive, or chronic-stress amplified"),
    }

    /**
     * Should this meal mark be processed? True only when:
     *   - 75+ min have elapsed since the meal (full post-window time available)
     *   - The mark is not older than the safe NDJSON-backfill window
     */
    fun isReadyForCompute(
        mealMark: MealMark,
        nowMs: Long = System.currentTimeMillis(),
        maxBackfillMs: Long = 7L * 24L * 60L * 60L * 1000L,
    ): Boolean {
        val elapsed = nowMs - mealMark.timestampMs
        return elapsed >= PostprandialSnapshotRepository.MIN_ELAPSED_FOR_COMPUTE_MS &&
            elapsed <= maxBackfillMs
    }

    companion object {
        const val METHODOLOGY_VERSION = PostprandialSnapshotRepository.METHODOLOGY_VERSION
    }
}
