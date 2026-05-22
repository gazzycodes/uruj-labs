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
    /** Wall-clock epoch ms when the user tapped "MARK MEAL". */
    val timestampMs: Long,
    /** Optional rider note (e.g. "rice + chicken", "post-ride snack"). v1: unused. */
    val note: String? = null,
    /** Source label per lab-level rule 1. "manual" for tap-marked; future:
     *  "auto-detected" for HR-signature-based meal detection. */
    val source: String = "manual",
)
