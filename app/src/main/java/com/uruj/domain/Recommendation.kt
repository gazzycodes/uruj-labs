package com.uruj.domain

import kotlinx.serialization.Serializable

/**
 * v0.9.4 — what a [com.uruj.power.ReadinessReasoner] produces for the UI to
 * render. Richer than the pre-v0.9.4 single string: carries the tier, the
 * full multi-line breakdown, cross-metric insights, and missing-data
 * callouts.
 *
 * # AI HOOK
 *
 * Future Groq AI reasoner (Task #105) returns the same shape. Rule-based
 * generates structured rationale + insights from severe-flag analysis; AI
 * generates the rationale as a free-form sentence over the same context.
 * UI consumes either identically.
 */
data class Recommendation(
    val tier: ReadinessTier,
    /** Headline call: "Hold the line" / "Easy Z2 endurance" / etc. */
    val headline: String,
    /** Duration cap / qualifier: "Z1 spin ≤ 30 min" / "let adaptation catch up". Nullable. */
    val duration: String?,
    /** Single-sentence rationale surfacing dominant drivers. */
    val rationale: String,
    /**
     * v0.9.4 — cross-metric insights surfaced separately so UI can render as
     * bullets below the rationale. Examples:
     *   "HRV trending down 2 nights in a row (12.2 → 8.7 ms)"
     *   "TSB stuck below −25 for 3 consecutive days"
     *   "CAR +49 bpm exaggerated this morning — acute stress signal"
     * Empty when nothing notable.
     */
    val insights: List<String> = emptyList(),
    /**
     * v0.9.4 — callout when an expected signal is missing.
     * "HRV strap not worn last night — readiness reads sleep + TSB only."
     */
    val missingSignalsCallout: String? = null,
    /** v0.9.4 — which severe flags fired (for snapshot persistence + audit trail). */
    val severeFlags: List<String> = emptyList(),
    /** v0.9.4 — mild flags fired alongside. */
    val mildFlags: List<String> = emptyList(),
)

enum class ReadinessTier(val displayLabel: String) {
    FullRest("FULL REST"),
    ActiveRecovery("ACTIVE RECOVERY"),
    EasyAerobic("EASY AEROBIC"),
    ModerateEndurance("MODERATE"),
    HardGreenLight("HARD CLEARED"),
}

/**
 * v0.9.4 — daily disk-persisted record of what URUJ recommended each day.
 *
 * Why persist: unlocks (1) multi-day rest tracking (count consecutive
 * `tier == FullRest` going backwards from today), (2) AI feedback loop (when
 * v0.5 Groq ships, compare what rules said vs what AI said vs what rider
 * did vs outcome), (3) audit trail (rider asks "why did URUJ say rest 4
 * days ago?" → load that day's snapshot and replay).
 *
 * Same persistence pattern as [com.uruj.data.HrrSnapshot] +
 * [com.uruj.data.RhrSnapshot] etc. Per
 * [[reference_snapshot_persistence_architecture]] all training-signal data
 * persists daily.
 */
@Serializable
data class RecommendationSnapshot(
    val dateIsoLocal: String,
    val score: Int,
    val tier: String,
    val headline: String,
    val duration: String?,
    val rationale: String,
    val severeFlags: List<String>,
    val mildFlags: List<String>,
    val missingSignals: List<String>,
    val computedAtMs: Long,
    val methodologyVersion: String,
)
