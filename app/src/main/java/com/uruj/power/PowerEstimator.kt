package com.uruj.power

import com.uruj.domain.RiderProfile
import kotlin.math.max
import kotlin.math.sin

private const val GRAVITY_MS2 = 9.81
private const val AIR_DENSITY_KG_M3 = 1.225 // sea-level standard; refined via barometer in v2

/**
 * Standard cycling power model. Same physics used by Strava's estimated power, by
 * WKO+, and by every "virtual power" feature on the market.
 *
 *   P = (Crr·m·g·v) + (0.5·ρ·CdA·v³) + (m·g·sin(θ)·v) + (m·a·v)
 *
 *   • rolling resistance — Crr (tire) × mass × g × speed
 *   • aerodynamic drag    — ½ × ρ × CdA × speed³
 *   • gravity / climbing  — mass × g × sin(grade) × speed
 *   • inertia / accel     — mass × acceleration × speed (negligible when smoothed)
 *
 * For headwind/tailwind correction we'd pass apparent wind speed into the aero term
 * (v_apparent = v_bike + headwind_component). v1 uses bike speed only; weather wind
 * support is a flag we toggle on once the OpenWeather integration lands.
 */
class PowerEstimator(private val profile: RiderProfile) {

    /**
     * @param speedMs current bike ground speed in m/s
     * @param gradeFraction slope as a fraction (0.05 = 5% climb, -0.05 = 5% descent)
     * @param accelMs2 acceleration in m/s² (typically smoothed; pass 0 if unknown)
     * @return estimated instantaneous power in watts, floored at 0
     */
    fun estimateWatts(speedMs: Float, gradeFraction: Float, accelMs2: Float = 0f): Float {
        if (speedMs < 0.5f) return 0f // coasting / standstill — physics model not meaningful

        val v = speedMs.toDouble()
        val m = profile.totalMassKg.toDouble()

        val rolling = profile.crr * m * GRAVITY_MS2 * v
        val aero = 0.5 * AIR_DENSITY_KG_M3 * profile.cda * v * v * v
        // sin(grade) approximation: for small grades, sin(θ) ≈ tan(θ) ≈ gradeFraction
        val climb = m * GRAVITY_MS2 * sin(gradeFraction.toDouble()) * v
        val inertia = m * accelMs2 * v

        val total = rolling + aero + climb + inertia
        return max(0.0, total).toFloat()
    }
}
