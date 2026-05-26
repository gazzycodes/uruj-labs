package com.uruj.domain

import kotlinx.serialization.Serializable

/**
 * Raw inputs to readiness scoring — each is optional so the calculator can produce
 * a partial score when some sources are missing (e.g. user didn't wear the band).
 */
@Serializable
data class ReadinessInputs(
    val sleepLastNightHours: Float? = null,
    val hrvTodayRmssd: Float? = null,
    val hrvBaseline7d: Float? = null,
    val restingHrToday: Int? = null,
    val restingHrBaseline7d: Int? = null,
    /** Training Stress Balance — CTL minus ATL. Positive = fresh, negative = fatigued. */
    val trainingStressBalance: Float? = null,
    /** v0.7.0 follow-up — how many of the last 7 nights have HRV data captured.
     *  Drives scoring mode: 1-6 days → absolute tier scoring (a real 60ms RMSSD
     *  scores well even without baseline); 7+ days → ratio vs personal baseline.
     *  Fixes the bug where day-1 "+0% vs 7d avg" showed misleading score. */
    val hrvDaysOfDataIn7d: Int = 0,
)

@Serializable
data class ReadinessResult(
    val score: Int, // 0–100
    val grade: ReadinessGrade,
    val components: List<ReadinessComponent>,
    /** Headline call. v0.4.x called this the recommendation; v0.9.3 split it into
     *  headline + duration + rationale (see fields below). This field still carries
     *  the headline so old call sites keep working. */
    val recommendation: String,
    /** 0.0–1.0 — fraction of input weight that came from real data. <0.5 means most
     *  inputs are missing and the score is unreliable. UI surfaces this to the user. */
    val dataConfidence: Float,
    /**
     * v0.9.3 — duration cap or qualifier paired with the headline.
     * Example: headline "Rest day" + duration "walk + hydrate, that's it".
     * Nullable on limited-data path or older serialized snapshots.
     */
    val recommendationDuration: String? = null,
    /**
     * v0.9.3 — why-line surfacing the dominant drivers in plain language.
     * Example: "TSB −30 + 4.7h sleep + HRV 9 ms — don't dig the hole deeper."
     * Nullable on limited-data path.
     */
    val recommendationRationale: String? = null,
    /**
     * v0.9.4 — cross-metric insights bullets surfaced below the rationale.
     * Examples: "HRV trending down 3 nights running", "TSB underwater
     * 4 consecutive days". Empty when nothing notable.
     */
    val recommendationInsights: List<String> = emptyList(),
    /**
     * v0.9.4 — when an expected signal is missing today (no HRV last night,
     * no Samsung sync), surfaces a single-line callout so the rider knows
     * what the engine doesn't see. Null when all expected signals present.
     */
    val recommendationMissingSignals: String? = null,
    /**
     * v0.9.21 — raw TSB breakdown (CTL, ATL, 42d total TSS) so the
     * Readiness card's TRAINING LOAD ⓘ can render the same personalized
     * ATL:CTL ratio + Build-CTL prescription that the Bio Lab Training
     * State ⓘ shows. Nullable for backward-compat with older serialized
     * snapshots; the dialog falls back to non-personalized educational
     * content when null. Populated by ReadinessRepository when TSB compute
     * succeeds (same numbers persisted to TsbSnapshotRepository).
     */
    val tsbCtl: Float? = null,
    val tsbAtl: Float? = null,
    val tsbTotalLoad42d: Float? = null,
    /**
     * v0.9.46.B — HRV statistical context (7d rolling median + CV% + 14d
     * regression with significance + week-over-week comparison). Surfaces
     * the trend layer that ends the daily-noise-as-signal anti-pattern.
     * Null when fewer than 2 nights of HRV data available.
     */
    val hrvStats: HrvStatsSignals? = null,
)

enum class ReadinessGrade(val label: String) {
    Unknown("NO DATA YET"),
    /** Score computed from <50% of inputs — show it but mark it as low-confidence. */
    LimitedData("LIMITED DATA"),
    Rest("REST"),
    /**
     * v0.9.49.2 — new tier slot that matches the recommendation engine's
     * `ReadinessTier.ActiveRecovery`. Pre-v0.9.49.2 the badge was derived
     * purely from raw composite score, so a score=74 + HRV-absolute ceiling
     * showed "MODERATE" (yellow) while the prescription said "graded
     * recovery / Z1 only" (orange-red). The contradiction is now resolved —
     * the badge follows the engine's final tier (which already honors all
     * the absolute-floor + subjective ceilings) instead of just the raw
     * score band.
     */
    Recovery("ACTIVE RECOVERY"),
    Easy("EASY"),
    Moderate("MODERATE"),
    GoHard("GO HARD"),
}

@Serializable
data class ReadinessComponent(
    val label: String,
    /** 0–100 if available, null if data missing. */
    val score: Int?,
    val detail: String,
)
