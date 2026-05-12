package com.uruj.power

import kotlin.math.pow

private const val SEA_LEVEL_PRESSURE_HPA = 1013.25
private const val NOISE_THRESHOLD_M = 0.5
private const val MAX_DELTA_PER_SECOND_M = 3.0

/**
 * Fuses three possible altitude sources in priority order:
 *   1. Barometer (best when device has the sensor — sub-meter live precision)
 *   2. DEM elevation lookup (Open-Meteo /v1/elevation — what Strava does)
 *   3. Smoothed GPS altitude (last resort — noisy but always available)
 *
 * Tracks total gain/loss and live grade. Grade clamps tighten as the source quality
 * degrades — barometer ±15%, DEM ±10%, GPS-only ±8% (Kolkata-flat assumption baseline).
 *
 * Single-sample altitude deltas >3m/sec are rejected as physically impossible at
 * cycling speed regardless of source — this kills the 2375W "false climbing power"
 * spike we saw on the 2026-05-12 ride with GPS-only altitude.
 */
class ElevationTracker {

    private data class WindowSample(
        val timestampMs: Long,
        val altitudeMeters: Double,
        val cumulativeDistanceMeters: Double,
    )

    private val window = ArrayDeque<WindowSample>()
    private var cumulativeDistance: Double = 0.0
    private var lastSmoothedAltitude: Double? = null
    private var totalGain: Double = 0.0
    private var totalLoss: Double = 0.0
    private var lastSource: Source = Source.NONE
    private var previousRawAltitude: Double? = null

    enum class Source {
        /** Phone has a barometer — most accurate, sub-meter precision. */
        BAROMETER,
        /** DEM lookup from Open-Meteo — independent of phone sensors, ground-truth from satellite data. */
        DEM,
        /** Raw GPS altitude — noisy, last resort, heavy smoothing applied. */
        GPS,
        NONE,
    }

    fun update(
        timestampMs: Long,
        pressureHpa: Float?,
        demElevationM: Float?,
        gpsAltitudeM: Double,
        distanceMovedM: Double,
    ): ElevationSnapshot {
        // Pick the best available source.
        val (altitude, source) = when {
            pressureHpa != null && pressureHpa > 100f ->
                pressureToAltitudeMeters(pressureHpa) to Source.BAROMETER
            demElevationM != null ->
                demElevationM.toDouble() to Source.DEM
            else ->
                gpsAltitudeM to Source.GPS
        }
        lastSource = source

        // Reject sample if it implies physically impossible vertical motion (regardless of
        // source — catches barometer spikes from passing vehicles, DEM API noise, GPS jitter).
        val prevRaw = previousRawAltitude
        if (prevRaw != null && window.isNotEmpty()) {
            val dtSec = ((timestampMs - window.last().timestampMs) / 1000.0).coerceAtLeast(0.001)
            val verticalSpeed = kotlin.math.abs(altitude - prevRaw) / dtSec
            if (verticalSpeed > MAX_DELTA_PER_SECOND_M) {
                // Skip this sample for grade/gain calc but still update raw tracker.
                previousRawAltitude = altitude
                return ElevationSnapshot(
                    altitudeMeters = lastSmoothedAltitude ?: altitude,
                    totalGainMeters = totalGain,
                    totalLossMeters = totalLoss,
                    gradeFraction = computeGradeFromWindow(distanceMovedM),
                    vamMetersPerHour = computeVamFromWindow(),
                    source = source,
                )
            }
        }
        previousRawAltitude = altitude

        cumulativeDistance += distanceMovedM
        window.addLast(WindowSample(timestampMs, altitude, cumulativeDistance))

        // Window length depends on source quality — noisier source = longer smoothing.
        val windowSec = when (source) {
            Source.BAROMETER -> 10L
            Source.DEM -> 20L
            Source.GPS -> 60L  // GPS altitude is the noisiest; need much heavier smoothing
            Source.NONE -> 10L
        }
        val cutoff = timestampMs - windowSec * 1_000L
        while (window.size > 1 && window.first().timestampMs < cutoff) {
            window.removeFirst()
        }

        val smoothedAltitude = window.sumOf { it.altitudeMeters } / window.size
        val previousSmoothed = lastSmoothedAltitude
        if (previousSmoothed != null) {
            val delta = smoothedAltitude - previousSmoothed
            // Reject smaller threshold from noisier sources to avoid accumulating drift.
            val noiseFloor = if (source == Source.GPS) 1.5 else NOISE_THRESHOLD_M
            if (delta > noiseFloor) totalGain += delta
            else if (delta < -noiseFloor) totalLoss += -delta
        }
        lastSmoothedAltitude = smoothedAltitude

        val gradeFraction = computeGradeFromWindow(distanceMovedM).coerceIn(
            -maxGradeForSource(source),
            maxGradeForSource(source),
        )

        return ElevationSnapshot(
            altitudeMeters = smoothedAltitude,
            totalGainMeters = totalGain,
            totalLossMeters = totalLoss,
            gradeFraction = gradeFraction,
            vamMetersPerHour = computeVamFromWindow(),
            source = source,
        )
    }

    private fun maxGradeForSource(source: Source): Float = when (source) {
        Source.BAROMETER -> 0.15f
        Source.DEM -> 0.10f
        Source.GPS -> 0.08f
        Source.NONE -> 0.0f
    }

    private fun computeGradeFromWindow(distanceMovedM: Double): Float {
        if (window.size < 2) return 0f
        val first = window.first()
        val last = window.last()
        val dy = last.altitudeMeters - first.altitudeMeters
        val dx = last.cumulativeDistanceMeters - first.cumulativeDistanceMeters
        return if (dx > 1.0) (dy / dx).toFloat() else 0f
    }

    private fun computeVamFromWindow(): Float {
        if (window.size < 2) return 0f
        val first = window.first()
        val last = window.last()
        val dy = last.altitudeMeters - first.altitudeMeters
        val dtSec = (last.timestampMs - first.timestampMs) / 1000.0
        return if (dy > 0 && dtSec > 0) (dy * 3600.0 / dtSec).toFloat() else 0f
    }

    private fun pressureToAltitudeMeters(pressureHpa: Float): Double {
        return 44_330.0 * (1.0 - (pressureHpa.toDouble() / SEA_LEVEL_PRESSURE_HPA).pow(1.0 / 5.255))
    }
}

data class ElevationSnapshot(
    val altitudeMeters: Double,
    val totalGainMeters: Double,
    val totalLossMeters: Double,
    val gradeFraction: Float,
    val vamMetersPerHour: Float,
    val source: ElevationTracker.Source,
)
