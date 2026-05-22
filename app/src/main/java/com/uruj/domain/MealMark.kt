package com.uruj.domain

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * v0.9.31 — A user-marked meal event. Triggers the postprandial HRV
 * response test (Tier B test, [[reference_biohacker_lab_vision]] tier 5).
 *
 * Captured the moment user taps "MARK MEAL" in Bio Lab. URUJ then uses
 * the timestamp to slice pre-meal (-30 to -5 min before mark) and
 * post-meal (+45 to +75 min after mark) windows from the 24/7 strap
 * NDJSON for autonomic comparison.
 *
 * Stored disk-first at `/files/meal_marks/<id>.json` per
 * [[reference_snapshot_persistence_architecture]]. One file per mark.
 * Past marks are immutable (no editing); user can delete via long-press
 * in a future UX iteration.
 *
 * Note vs [[reference_lab_level_uruj]] rule 4 (no fake numbers): if the
 * pre or post window has insufficient strap data (strap was off, NDJSON
 * gap, etc.), the resulting PostprandialSnapshot will surface that
 * explicitly with null deltas — never inferred or fabricated numbers.
 */
@Serializable
data class MealMark(
    /** Stable UUID for this mark — used as filename + PostprandialSnapshot link. */
    val id: String = UUID.randomUUID().toString(),
    /** Wall-clock epoch ms when the consumption event started. */
    val timestampMs: Long,
    /**
     * v0.9.35 — Event category. The pre/post HRV math is event-agnostic
     * (same windows for all), but the card title + interpretation copy
     * vary by type so the rider can read "Coffee response" / "Alcohol
     * response" instead of always "Postprandial".
     *
     * Values: "meal" (full meal — default + backward-compat for v0.9.31-34
     * marks that lacked this field), "snack" (light/small), "coffee"
     * (caffeine), "drink" (sugary/soda), "alcohol", "custom" (other).
     *
     * Per-type tier interpretation refinements deferred to v0.9.36.
     */
    val eventType: String = "meal",
    /**
     * Optional freeform note for memory-aid. E.g. "Rice + chicken + eggs",
     * "Black coffee + 1 sugar", "2 Cokes during walk". Surfaces on the
     * card + trend chart detail formatter so rider can correlate
     * specific consumed items with autonomic response later.
     */
    val note: String? = null,
    /** Source label per lab-level rule 1. "manual" for tap-marked; future:
     *  "auto-detected" for HR-signature-based meal detection. */
    val source: String = "manual",
)
