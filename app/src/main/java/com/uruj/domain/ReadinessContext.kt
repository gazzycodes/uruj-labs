package com.uruj.domain

/**
 * v0.9.4 — the unified signal pack. **The single struct that every reasoner
 * (rule-based today, AI tomorrow) reads from when deciding what URUJ should
 * tell the rider.**
 *
 * See [[reference_readiness_context_architecture]] for the architectural law.
 *
 * # Why this exists
 *
 * Pre-v0.9.4 ReadinessCalculator took 6 scalar inputs (sleep, HRV today, HRV
 * baseline, RHR today, RHR baseline, TSB). It ignored CAR, Orthostatic, HRR1
 * trajectory, RHR trajectory, VO2 trajectory, missing-data days, consecutive-
 * rest streaks, and confidence per signal. The 2026-05-19 morning ritual
 * exposed it: rider had +49 bpm exaggerated CAR signal saved on disk, engine
 * never read it.
 *
 * `ReadinessContext` is the bulletproof fix. One struct, every signal, with
 * provenance + confidence + trends + patterns. Engine reads it. AI coach
 * (v0.5 Groq, Task #105) reads the **same** struct. Trend charts read it.
 * Future biomarker cards read it. Plug-and-play.
 *
 * # Hard rule
 *
 * Every biomarker, metric, test result, or physiological signal that URUJ
 * captures **MUST** be wired into [ReadinessContext] from PR 1. No exceptions.
 *
 * # AI HOOK
 *
 * Future [com.uruj.power.ReadinessReasoner] implementations (Groq, local LLM,
 * etc.) consume this struct unchanged. The reasoner interface is the seam.
 * Search the codebase for `AI HOOK` to find every plug point in 5 seconds.
 *
 * Naming convention: `*Today` data classes here are EPHEMERAL view models
 * built each Bio Lab open. They are NOT the same as `*Snapshot` disk schemas
 * (e.g. [com.uruj.data.RhrSnapshot]) which persist forever per
 * [[reference_snapshot_persistence_architecture]]. The context BUILDER reads
 * snapshots from disk; reasoners and AI read the context's `*Today` views.
 */
data class ReadinessContext(
    val today: TodaySnapshot,
    val trends: Trends,
    val patterns: Patterns,
    val provenance: Provenance,
) {
    /**
     * Today's physiological snapshot — each field nullable because data may
     * be missing (no strap last night, no Samsung sync, no recent CAR, etc.).
     * Reasoner must handle null gracefully — missing-data is itself a signal
     * surfaced via [Patterns.missingHrvNights7d] + [Provenance.missingSignals].
     */
    data class TodaySnapshot(
        val sleep: SleepToday? = null,
        val hrv: HrvToday? = null,
        val rhr: RhrToday? = null,
        val tsb: TsbToday? = null,
        val car: CarToday? = null,
        val orthostatic: OrthostaticToday? = null,
        val hrr1: Hrr1Today? = null,
        val vo2: Vo2Today? = null,
        // Extensible: add weight, body comp, CGM, lactate, blood panels here
        // when the corresponding biomarker repository ships.
    )

    /**
     * Direction + magnitude over rolling windows. Reasoner uses these to
     * answer "is the rider trending up, down, or flat?" — answers that
     * absolute today-values can't give. The 2026-05-19 case: HRV 8.7 ms
     * absolute is below athletic, BUT the 7-night trend is FALLING (12.2 →
     * 8.7 = −29% over 2 nights). Both signals matter; trend is often the
     * earlier warning.
     */
    data class Trends(
        val hrv: TrendSeries? = null,
        val rhr: TrendSeries? = null,
        val tsb: TrendSeries? = null,
        val vo2: TrendSeries? = null,
        val hrr1: TrendSeries? = null,
        val sleep: TrendSeries? = null,
    )

    /**
     * Longitudinal counters — "how long has this been going on?" Drives
     * multi-day rest enforcement, missing-data detection, illness early
     * warning patterns.
     */
    data class Patterns(
        /** Days in a row the recommendation was Tier.FullRest (read from RecommendationSnapshotRepository). */
        val consecutiveRestDays: Int = 0,
        /** Days in a row composite readiness score was < 50 (read from RecommendationSnapshotRepository). */
        val consecutiveLowReadinessDays: Int = 0,
        /** Days in last 7 with no HRV reading captured (strap off / band missing). */
        val missingHrvNights7d: Int = 0,
        /** Days in last 7 with no sleep session (Samsung not synced / band off). */
        val missingSleepNights7d: Int = 0,
        /** Nights with HRV ratio < 0.70 OR absolute < 12 ms in a row. */
        val hrvCrashStreak: Int = 0,
        /** Days in a row TSB ≤ −25 (over-trained band). */
        val tsbUnderwaterDays: Int = 0,
        /** Sum(7h - actual_hours) over last 7 days when actual < 7h. */
        val sleepDebt7d: Float = 0f,
    )

    /**
     * Provenance + confidence — answers "should the reasoner trust today's
     * context?" Affects copy firmness (high confidence → decisive, low →
     * hedged) and surfaces missing-signal callouts in the UI.
     */
    data class Provenance(
        val builtAtMs: Long,
        /** 0.0–1.0, weighted by signal availability + freshness. */
        val overallConfidence: Float,
        /** Human-readable list: ["hrv-last-night", "car-today", "rhr-7d-baseline"]. */
        val missingSignals: List<String> = emptyList(),
        /** Per-signal methodology version tags for traceability across calc upgrades. */
        val methodologyVersions: Map<String, String> = emptyMap(),
    )
}

// region Today snapshot field types

data class SleepToday(
    val hours: Float,
    val sessionEndMs: Long,
    /** "samsung-hc" / "manual" / "imputed". */
    val source: String,
)

data class HrvToday(
    val rmssdMs: Float,
    /** Today / baseline ratio. Null when baseline < 7 nights (absolute-tier scoring). */
    val ratioVsBaseline: Float?,
    /** 0–7+ — count of overnight HRV nights captured in last 7d from URUJ NDJSON. */
    val daysOfDataIn7d: Int,
    /** [SensorSource] short label: "strap" / "band" / "mixed" / "legacy" / "hc-direct". */
    val source: String,
    val capturedAtMs: Long,
)

data class RhrToday(
    val todayBpm: Int,
    val baselineBpm: Int?,
    /** "sleep:strap" / "sleep:band" / "sleep:mixed" / "direct" / null. */
    val source: String?,
    val capturedAtMs: Long,
)

data class TsbToday(
    val value: Float,
    val ctl: Float,
    val atl: Float,
    val totalLoad42d: Float,
    val capturedAtMs: Long,
)

data class CarToday(
    val tier: CarTier,
    val amplitudeBpm: Float,
    val latencyMinutes: Float,
    val rmssdDropPercent: Float,
    /** Hours since the CAR sleep-end. > 24h → reasoner treats as stale. */
    val ageHours: Float,
    val capturedAtMs: Long,
)

data class OrthostaticToday(
    val hrDeltaBpm: Int,
    val rmssdRatio: Float,
    /** Hours since test. > 72h → reasoner treats as historical, not "today's". */
    val ageHours: Float,
    val capturedAtMs: Long,
)

data class Hrr1Today(
    val medianBpm: Int,
    /** "EXCELLENT" / "AVERAGE" / "ELEVATED_CV_RISK" — Cole 1999 classifications. */
    val classification: String,
    val samplesCount: Int,
    /** Days since last HRR1 reading. Older → reasoner discounts weight in tier decision. */
    val lastReadingAgeDays: Int,
)

data class Vo2Today(
    val urujConsensusMlKgMin: Float,
    val samsungMlKgMin: Float?,
    /** "ELITE" / "VERY_HIGH" / "HIGH" / "ABOVE_AVERAGE" / etc. — Cooper-style band. */
    val classification: String,
    val capturedAtMs: Long,
)

// endregion

// region Trend series

/**
 * Direction + magnitude over a rolling window. Slope normalized to per-day
 * (linear regression coefficient). Direction collapses noise into 3-state
 * label for fast reasoning.
 */
data class TrendSeries(
    /** Window length in days (typical: 7, 30, 42, 90). */
    val windowDays: Int,
    /** Sample count actually present in the window (≤ windowDays). */
    val sampleCount: Int,
    /** Slope: units per day (e.g. ms/day for HRV, bpm/day for RHR, watts/day for FTP). */
    val slopePerDay: Float,
    val direction: TrendDirection,
    /** Most-recent value at index 0, oldest last. Same units as snapshot. */
    val mostRecentValues: List<Float>,
)

enum class TrendDirection {
    /** Slope positive enough to call it improving (signal-specific threshold). */
    RISING,
    /** Slope within ±noise band. */
    FLAT,
    /** Slope negative enough to call it deteriorating. */
    FALLING,
    /** Not enough data to compute (< 3 samples in window). */
    INSUFFICIENT_DATA,
}

// endregion
