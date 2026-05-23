package com.uruj.domain

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * v0.9.39 — Generic in-app tracker entry. One entry per subjective/behavioral
 * log event the rider taps (mood rating, hydration glass, caffeine drink,
 * supplement, cold exposure, meditation, etc.).
 *
 * **Why one generic schema for all tracker types**: avoids 13 separate data
 * classes + 13 serializers + 13 repositories. The `type` field discriminates;
 * `numericValue` covers quantitative metrics (mood/energy 1-10, ml, mg);
 * `textValue` covers qualitative (supplement names, dream notes); `note`
 * is always optional freeform context.
 *
 * Stored disk-first at `/files/trackers/<type>/<id>.json` per
 * [[reference_snapshot_persistence_architecture]]. Past entries are immutable.
 * Long-press in card or trend READINGS list → delete.
 *
 * **Lab-level rule 1**: source label preserved. "manual" for tap-marked;
 * future "auto" for sensor-triggered (e.g. cold-exposure auto-detect from
 * skin-temp). "voice" for future voice-journal capture.
 *
 * **Lab-level rule 4**: no fake numbers. Caller must provide actual rider
 * input; numericValue + textValue can both be null in edge cases but caller
 * should never auto-fill.
 */
@Serializable
data class TrackerEntry(
    /** Stable UUID for this entry — used as filename + delete reference. */
    val id: String = UUID.randomUUID().toString(),
    /** Tracker type discriminator. See [TrackerType] for enum keys. */
    val type: String,
    /** Quantitative value (mood 1-10, hydration ml, caffeine mg, etc.).
     *  Null when this tracker has no numeric input (pure-text trackers). */
    val numericValue: Float? = null,
    /** Qualitative value (supplement name, dream content, symptom text).
     *  Null when tracker is pure-numeric. */
    val textValue: String? = null,
    /** Wall-clock epoch ms when the rider logged this entry. */
    val timestampMs: Long,
    /** Optional freeform note for context — coexists with numericValue or textValue. */
    val note: String? = null,
    /** Source label per lab-level rule 1. "manual" / future "auto" / "voice". */
    val source: String = "manual",
)
