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
    /** Current smoothed altitude from the elevation tracker (best available source). */
    val currentAltitudeMeters: Float = 0f,
    val maxSpeedMs: Float = 0f,

    /** Wall-clock timestamp of the last 30s checkpoint save. HUD shows
     *  "saved Xs ago" so the rider can confirm the service is alive even when
     *  the phone is backgrounded and the notification might be hidden. */
    val lastCheckpointAtMs: Long? = null,

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

    // v0.5.1 — BLE chest strap (Magene H613 etc.) state surfaced to HUD top bar.
    /** True while the BLE chest strap is connected and streaming HR. */
    val bleConnected: Boolean = false,
    /** Strap battery level 0-100, null when unread. */
    val bleBatteryPct: Int? = null,
    /** Contact-detected flag from HR Measurement bit 1. Null = strap doesn't
     *  report status. True = electrodes seated. False = wet electrodes/tighten. */
    val bleContactDetected: Boolean? = null,
    /** Display name for the strap on the HUD (model/firmware/MAC fallback). */
    val bleStrapName: String? = null,
    /** v0.5.2 — beat-by-beat live HR from the strap. Updated on EVERY BLE
     *  notification (sub-100ms latency) rather than every GPS tick (1s).
     *  HUD's STRAP row + main HEART RATE display read this when present,
     *  falling back to latestSample.hrBpm (HC-batched). Null when strap
     *  disconnected — UI shows OFFLINE state. */
    val bleLiveBpm: Int? = null,
    /** v0.8.0 — active session intent (audio coach target). Updated at ride
     *  start from SessionIntentStore + on mid-ride overrides. Drives the HUD
     *  session indicator + the audio coach's zone-discipline cues. Default
     *  EXPLORATORY means no coaching (silent). */
    val sessionIntent: com.uruj.domain.SessionIntent = com.uruj.domain.SessionIntent.EXPLORATORY,
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
