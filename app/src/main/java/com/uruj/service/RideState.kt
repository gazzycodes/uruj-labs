package com.uruj.service

import com.uruj.domain.PowerZone
import com.uruj.domain.RideSample
import com.uruj.power.ElevationTracker
import com.uruj.power.NewPr
import com.uruj.weather.WeatherSample
import com.uruj.weather.WeatherStatus

data class RideState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val sessionId: String? = null,
    val startedAtMs: Long? = null,
    val latestSample: RideSample? = null,
    val totalDistanceMeters: Double = 0.0,
    val movingTimeMs: Long = 0L,
    val totalElapsedMs: Long = 0L,

    // Power model outputs (v2 additions)
    val instantPowerWatts: Float = 0f,
    val smoothedPower3sWatts: Float = 0f,
    val smoothedPower30sWatts: Float = 0f,
    val averagePowerWatts: Float = 0f,
    val maxPowerWatts: Float = 0f,
    val totalWorkKj: Float = 0f,
    val currentZone: PowerZone? = null,
    val ftpWatts: Int = 200,
    /** Best 20-minute sliding-window average power across the ride. Drives FTP
     *  auto-update at ride end: profile.ftpWatts ← 0.95 × this, if higher. */
    val best20MinPowerWatts: Float = 0f,

    // Heart rate tracking (in-ride observation, feeds the ride-end max-HR write-back)
    /** Highest HR sample seen during this ride. Drives auto-bump of profile.maxHrBpm
     *  at ride end when the rider exceeds their declared max. */
    val maxHrBpmObserved: Int = 0,

    // Elevation tracker outputs (barometer-fused)
    val totalElevGainMeters: Float = 0f,
    val totalElevLossMeters: Float = 0f,
    val currentGradeFraction: Float = 0f,
    val vamMetersPerHour: Float = 0f,
    val elevationSource: ElevationTracker.Source = ElevationTracker.Source.NONE,
    val maxSpeedMs: Float = 0f,

    // GPS quality gating — when accuracy is poor (indoors / urban canyon / cold start)
    // we mark this false and refuse to trust speed / distance / power. Saves NDJSON
    // from corruption and prevents false PRs from cell-tower-fused indoor "movement".
    val gpsAccurate: Boolean = false,
    val gpsAccuracyMeters: Float = 0f,

    // Weather + wind (v2.5)
    val weather: WeatherSample? = null,
    val weatherStatus: WeatherStatus = WeatherStatus.Idle,
    val headwindMs: Float = 0f, // positive = headwind (slowing you), negative = tailwind

    // Live PR alert — UI checks (now - prAnnouncedAtMs) < 6s to flash the overlay.
    val latestPr: NewPr? = null,
    val prAnnouncedAtMs: Long? = null,
) {
    val averageSpeedMovingKph: Float
        get() = if (movingTimeMs > 0) {
            (totalDistanceMeters / (movingTimeMs / 1000.0) * 3.6).toFloat()
        } else 0f

    val currentSpeedKph: Float
        get() = when {
            isPaused -> 0f
            !gpsAccurate -> 0f
            else -> (latestSample?.speedMetersPerSecond ?: 0f) * 3.6f
        }

    val currentGradePercent: Float get() = currentGradeFraction * 100f

    val powerPerKilogram: Float
        get() {
            val watts = smoothedPower3sWatts
            // riderWeightKg is not in state — UI passes profile separately for display.
            return watts
        }
}
