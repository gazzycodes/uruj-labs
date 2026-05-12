package com.uruj.domain

import kotlinx.serialization.Serializable

/**
 * All rider+bike parameters feeding the live power model and zone calculations.
 * Defaults chosen for an "average endurance cyclist on a road bike, hoods position"
 * so first-time users get sane numbers before opening the profile screen.
 *
 * Crr (tire rolling resistance) and CdA (drag area) are constants derived from
 * tireType and ridingPosition — see the enums below.
 */
@Serializable
data class RiderProfile(
    val riderWeightKg: Float = 65f,
    val bikeWeightKg: Float = 10f,
    val ftpWatts: Int = 200,
    val maxHrBpm: Int = 190,
    val tireType: TireType = TireType.Road,
    val ridingPosition: RidingPosition = RidingPosition.Upright,
    val ageYears: Int = 30,
    val heightCm: Int = 175,
) {
    val totalMassKg: Float get() = riderWeightKg + bikeWeightKg
    val crr: Double get() = tireType.crr
    val cda: Double get() = ridingPosition.cda
}

enum class TireType(val crr: Double, val displayName: String) {
    Road(0.005, "Road (slick)"),
    Gravel(0.012, "Gravel"),
    MTB(0.018, "MTB (knobby)"),
}

/** Frontal-area coefficient (CdA, m²) — strongest single input to aero drag. */
enum class RidingPosition(val cda: Double, val displayName: String) {
    Upright(0.55, "Upright"),
    Tops(0.50, "On the tops"),
    Hoods(0.42, "On the hoods"),
    Drops(0.36, "In the drops"),
    Aero(0.28, "Aero / TT bars"),
}

/**
 * Power zones based on FTP — Coggan's classic 7-zone system, but we collapse to 5
 * for HUD simplicity. Z5 here covers both anaerobic and neuromuscular Coggan zones.
 */
enum class PowerZone(val ftpFraction: ClosedFloatingPointRange<Float>, val label: String) {
    Z1(0f..0.55f, "Recovery"),
    Z2(0.55f..0.75f, "Endurance"),
    Z3(0.75f..0.90f, "Tempo"),
    Z4(0.90f..1.05f, "Threshold"),
    Z5(1.05f..Float.POSITIVE_INFINITY, "VO2 / Sprint");

    companion object {
        fun forPower(powerWatts: Float, ftpWatts: Int): PowerZone {
            val frac = powerWatts / ftpWatts.toFloat()
            return entries.firstOrNull { frac in it.ftpFraction } ?: Z1
        }
    }
}
