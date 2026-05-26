package com.uruj.domain

import kotlinx.serialization.Serializable

/**
 * v0.9.31 — Postprandial HRV response analysis result.
 *
 * Comparison of pre-meal vs post-meal HRV windows for a single meal
 * event. Computed once per [MealMark] when ≥75 min have elapsed since
 * the mark (giving the full +45..+75 min post-meal window time to
 * accumulate strap data).
 *
 * **Methodology** (Tarvainen 2018; Hara 2016; Task Force 1996 short-term):
 *   - Pre-meal window: mealMarkMs − 30 min to mealMarkMs − 5 min
 *     (25 min of recent baseline-ish data, 5 min buffer before eating)
 *   - Post-meal window: mealMarkMs + 45 min to mealMarkMs + 75 min
 *     (30 min covering typical postprandial autonomic response peak)
 *   - Each window uses [com.uruj.power.HrvCalculator.computeWindowed]
 *     for time-domain + the same Lomb-Scargle pipeline for freq-domain
 *     as overnight HRV.
 *   - Deltas computed as: (post − pre) / pre × 100 for percent change
 *
 * **Tier interpretation** (RMSSD drop is primary signal):
 *   - 0–15 %  → Stable metabolism (minimal autonomic perturbation)
 *   - 15–30 % → Typical healthy response
 *   - 30–50 % → Sensitive — consider meal composition + pre-ride timing
 *   - 50 %+   → Strong response — large meal, glucose-sensitive, or
 *                chronic stress amplifying the dip
 *
 * **Lab-level rules followed** (per [[reference_lab_level_uruj]]):
 *   - Source label preserved per window (strap / band / mixed)
 *   - Timestamps explicit (mealMarkMs, computedAtMs)
 *   - Methodology version tagged for forward calc-change traceability
 *   - Null deltas surfaced honestly when either window lacks strap data
 *     (no fake numbers)
 *   - Disk-persisted (deep-view trend chart in v0.9.32+)
 *   - No new hardware required (uses existing strap NDJSON)
 *   - Cycling-relevance: meal timing affects training readiness within
 *     2–3h; postprandial sensitivity flags help tune pre-ride meals
 */
@Serializable
data class PostprandialSnapshot(
    /** Stable id linking back to the originating [MealMark.id]. */
    val mealMarkId: String,
    /** When the user marked the meal. */
    val mealMarkMs: Long,
    /** Local-date the meal was marked (for trend chart x-axis). */
    val dateIsoLocal: String,
    /** v0.9.35 — Event category (denormalized from MealMark.eventType for
     *  fast UI access). Backward-compat default "meal" for snapshots
     *  written before v0.9.35. */
    val eventType: String = "meal",
    /** v0.9.35 — Optional rider note (denormalized from MealMark.note). */
    val note: String? = null,

    // ── Pre-meal window (-30 to -5 min before mark) ──
    val preWindowStartMs: Long,
    val preWindowEndMs: Long,
    val preRmssdMs: Float?,
    val preSdnnMs: Float?,
    val preMeanHrBpm: Float?,
    val preHfMs2: Float?,
    val preLfMs2: Float?,
    val preLfHfRatio: Float?,
    val preSampleCount: Int = 0,

    // ── Post-meal window (+45 to +75 min after mark) ──
    val postWindowStartMs: Long,
    val postWindowEndMs: Long,
    val postRmssdMs: Float?,
    val postSdnnMs: Float?,
    val postMeanHrBpm: Float?,
    val postHfMs2: Float?,
    val postLfMs2: Float?,
    val postLfHfRatio: Float?,
    val postSampleCount: Int = 0,

    // ── Deltas (computed from above) ──
    /** RMSSD % change: (post − pre) / pre × 100. Negative = HRV dropped post-meal (typical). */
    val rmssdDeltaPercent: Float?,
    /** Mean HR absolute delta in bpm: post − pre. Positive = HR rose post-meal (typical). */
    val hrDeltaBpm: Float?,
    /** HF power % change: (post − pre) / pre × 100. Negative = parasympathetic withdrew (typical). */
    val hfDeltaPercent: Float?,
    /** LF/HF ratio % change: (post − pre) / pre × 100. Positive = sympathetic dominance rose (typical). */
    val lfHfDeltaPercent: Float?,

    // ── v0.9.33 — edge-case flags (overlap + sleep + coverage) ──
    /**
     * True if the pre-meal window overlaps with another meal mark's
     * post-meal window (meals were <90 min apart). Pre-meal baseline is
     * then NOT a clean baseline — it's another meal's post-response
     * still recovering. Card shows a warning chip and interpretation
     * should be cautious.
     */
    val overlapsPriorMeal: Boolean = false,
    /**
     * Id of the prior meal mark whose post-window overlaps with this one's
     * pre-window. Null if no overlap. Surfaced in ⓘ dialog.
     */
    val overlapsPriorMealId: String? = null,
    /**
     * True if the meal mark fell within a Samsung-detected sleep window.
     * Indicates a midnight snack / during-sleep eating event. Pre-window
     * baseline is then "sleep state" (not "awake at-rest"), so deltas
     * have different interpretation. Card shows a warning chip; data
     * is still computed + saved.
     */
    val isDuringSleep: Boolean = false,
    /**
     * Fraction of the 25-min pre-meal window that contained valid strap
     * data, from 0.0 to 1.0. 1.0 = strap on continuously for full window;
     * 0.5 = strap data for ~12.5 of the 25 min. Surfaced on card.
     */
    val preCoveragePct: Float? = null,
    /**
     * Fraction of the 30-min post-meal window that contained valid strap
     * data, from 0.0 to 1.0.
     */
    val postCoveragePct: Float? = null,

    // ── v0.9.38 — ride-overlap flag (#190) ──
    /**
     * True if any ride session overlapped with the post-meal window
     * (mealMarkMs + 45 min to + 75 min). When this fires, the post-window
     * captures exercise sympathetic dominance rather than meal-induced
     * digestion response — the reading is exercise-confounded.
     *
     * Card surfaces a warning chip; rider knows to interpret cautiously
     * (or delete the reading from the trend chart).
     */
    val rideOverlapsPostprandial: Boolean = false,
    /**
     * Minutes from meal mark to next ride start. Null when no ride
     * occurred within 4 hours after the meal. Surfaced in the warning
     * chip + ⓘ so rider can see how late the ride started.
     */
    val minutesToNextRide: Long? = null,

    // ── Provenance ──
    /** Source of the strap data: "strap" / "band" / "mixed" / "insufficient" / "partial". */
    val source: String,
    /** Wall-clock epoch ms when this snapshot was computed. */
    val computedAtMs: Long,
    // v0.9.49.1 — default "legacy" per [[reference_lab_grade_architecture_rules]]
    // Rule 2 so pre-versioning snapshots deserialize gracefully + future
    // methodology bumps invalidate via mismatch instead of accidental match.
    val methodologyVersion: String = "legacy",
)
