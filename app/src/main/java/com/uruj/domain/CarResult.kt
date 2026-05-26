package com.uruj.domain

import kotlinx.serialization.Serializable

/**
 * v0.7.2 — Cortisol Awakening Response result, computed automatically from
 * the first 30-45 min post-wake window in the 24/7 BiometricService NDJSON.
 *
 * Why this matters: cortisol surges ~50-160% in the first 30-45 min after
 * waking. The HPA-axis activation shows up in HR + HRV — HR climbs 10-25 bpm
 * above sleep baseline, RMSSD drops 30-60%. The AMPLITUDE and LATENCY of
 * that response is the CAR signal.
 *
 * Blunted CAR (low amplitude, slow latency) is a published marker of:
 *   - Chronic stress / burnout
 *   - Over-training syndrome
 *   - Depression
 *   - HPA-axis dysfunction
 *
 * Robust CAR (10-25 bpm rise within 30 min) = healthy stress-response system.
 *
 * Reference: Pruessner et al. 1997, Clow et al. 2010, Stalder et al. 2016
 * (consensus methodology for CAR measurement).
 *
 * URUJ proxies cortisol via HR/HRV inflection because we cannot measure
 * salivary cortisol non-invasively. The HR pattern correlates strongly with
 * cortisol curve — both driven by sympathetic activation on waking.
 *
 * Detection methodology:
 *   1. Find sleep END timestamp (from HC SleepSessionRecord)
 *   2. Pre-wake baseline = mean HR over last 10 min of sleep
 *   3. Post-wake window = sleep-end to sleep-end + 45 min
 *   4. Detect peak HR in window + time-to-peak
 *   5. Compute amplitude = peak − baseline, latency = time-to-peak
 *   6. RMSSD trajectory: split window into 5-min bins, compute per-bin
 *      → look for typical "drop then recover" pattern
 */
@Serializable
data class CarResult(
    val sessionId: String,
    val computedAtMs: Long,
    val sleepEndMs: Long,
    val windowEndMs: Long,
    val baselineHrBpm: Float,
    val peakHrBpm: Float,
    val amplitudeBpm: Float,
    val latencyMinutes: Float,
    val baselineRmssdMs: Float,
    val troughRmssdMs: Float,
    val rmssdDropPercent: Float,
    val sampleCountInWindow: Int,
    val cleanBeatsInWindow: Int,
    // v0.9.48.8 — rigorous quiet-window CAR per Pruessner/Clow/Stalder.
    // 5-min bin MEANS within 0-30 min post-wake. Filters out post-wake
    // activity (walking, kitchen, etc.) that the wide-window peak-HR
    // approach incorrectly captures as HPA-axis surge.
    // The PRIMARY interpretation now uses quietWindowAmplitudeBpm; the
    // wide-window amplitudeBpm is retained as informational ("HR rose to X
    // within 45 min, including any post-wake activity").
    val quietWindowAmplitudeBpm: Float? = null,
    val quietWindowPeakMeanBpm: Float? = null,
    val quietWindowPeakBinMinutes: Int? = null,
    val methodologyVersion: String = "v0.9.48.8",
)

/** Tier classification of the CAR signal. */
data class CarInterpretation(
    val amplitudeTier: CarTier,
    val latencyTier: CarTier,
    val overallTier: CarTier,
    val summary: String,
)

enum class CarTier {
    BLUNTED,       // <5 bpm rise — flat HPA response, chronic-stress marker
    SUPPRESSED,    // 5-10 bpm — below typical healthy range
    NORMAL,        // 10-20 bpm — typical healthy adult range
    ROBUST,        // 20-30 bpm — strong healthy HPA-axis activation
    EXAGGERATED,   // >30 bpm — possible acute stress / anxiety
}
