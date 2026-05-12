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
)

@Serializable
data class ReadinessResult(
    val score: Int, // 0–100
    val grade: ReadinessGrade,
    val components: List<ReadinessComponent>,
    val recommendation: String,
    /** 0.0–1.0 — fraction of input weight that came from real data. <0.5 means most
     *  inputs are missing and the score is unreliable. UI surfaces this to the user. */
    val dataConfidence: Float,
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
