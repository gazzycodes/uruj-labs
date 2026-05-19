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
)

enum class ReadinessGrade(val label: String) {
    Unknown("NO DATA YET"),
    /** Score computed from <50% of inputs — show it but mark it as low-confidence. */
    LimitedData("LIMITED DATA"),
    Rest("REST"),
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
