package com.uruj.domain

/**
 * v0.8.0 — what kind of training session the rider is doing today.
 *
 * Drives the audio coach's zone-discipline cues + HUD target indicator.
 * Each intent has a target HR zone band; the coach fires TTS when live
 * BLE HR drifts outside the band for a sustained period (not brief hill
 * climbs or descents).
 *
 * `EXPLORATORY` = no target, no coaching. Rider just wants to ride.
 * Default for first-time use until user picks one in pre-ride.
 *
 * Zone definitions are the standard Karvonen-derived bands URUJ already
 * uses on the HUD + post-ride breakdowns:
 *   Z1 Recovery
 *   Z2 Endurance
 *   Z3 Tempo
 *   Z4 Threshold
 *   Z5 VO2 / Sprint
 */
enum class SessionIntent(
    val displayLabel: String,
    val shortLabel: String,
    val targetZoneMin: Int?,   // inclusive (1-5), null = no target
    val targetZoneMax: Int?,   // inclusive (1-5), null = no target
    val description: String,
) {
    RECOVERY(
        displayLabel = "Recovery",
        shortLabel = "RECOVERY",
        targetZoneMin = 1,
        targetZoneMax = 1,
        description = "Easy spin. Stay in Z1. Build base, clear fatigue.",
    ),
    ENDURANCE(
        displayLabel = "Endurance",
        shortLabel = "ENDURANCE",
        targetZoneMin = 2,
        targetZoneMax = 2,
        description = "Aerobic base. Z2 only. Long-ride pace, polarized 80/20 base.",
    ),
    TEMPO(
        displayLabel = "Tempo",
        shortLabel = "TEMPO",
        targetZoneMin = 3,
        targetZoneMax = 3,
        description = "Gray-zone work. Z3. Use sparingly — easy to overdo.",
    ),
    THRESHOLD(
        displayLabel = "Threshold intervals",
        shortLabel = "THRESHOLD",
        targetZoneMin = 4,
        targetZoneMax = 4,
        description = "Z4 intervals with recovery between. Sustained threshold work.",
    ),
    VO2(
        displayLabel = "VO2 intervals",
        shortLabel = "VO2",
        targetZoneMin = 5,
        targetZoneMax = 5,
        description = "Max-aerobic. Z5 intervals. Short, hard, full recovery between.",
    ),
    EXPLORATORY(
        displayLabel = "Exploratory",
        shortLabel = "EXPLORATORY",
        targetZoneMin = null,
        targetZoneMax = null,
        description = "Just ride. No target zone, no coaching cues.",
    ),
    ;

    fun hasTarget(): Boolean = targetZoneMin != null && targetZoneMax != null
}
