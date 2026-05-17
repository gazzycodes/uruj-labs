package com.uruj.power

import kotlin.math.pow

private const val SEA_LEVEL_PRESSURE_HPA = 1013.25
private const val NOISE_THRESHOLD_M = 0.5
private const val GPS_NOISE_THRESHOLD_M = 1.5
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
 *
 * v0.2.11 — dual-tracker gain/loss
 *   The DEM API (Open-Meteo SRTM) has 30m horizontal resolution — too coarse to
 *   capture urban flyovers (5-10m tall, often shorter than the grid cell). On the
 *   2026-05-13 Kolkata ride, the rider crossed flyovers but DEM stayed flat at
 *   42m the whole ride → 43m gain / 0m loss in summary, missing real elevation
 *   change. Fix: ALWAYS track GPS altitude in a parallel accumulator regardless
 *   of which source is "primary". GPS catches short bridges that SRTM misses
 *   (3D positioning has altitude data even with horizontal noise). Final gain
 *   and loss = MAX of (primary-tracker, gps-tracker). Live altitude display +
 *   grade still come from primary source (cleaner number). This adds urban-
 *   bridge sensitivity without polluting the rest of the metric.
 */
class ElevationTracker {

    private data class WindowSample(
        val timestampMs: Long,
        val altitudeMeters: Double,
        val cumulativeDistanceMeters: Double,
    )

    // ---- Primary tracker — uses best available source per update ----
    private val primaryWindow = ArrayDeque<WindowSample>()
    private var primaryLastSmoothed: Double? = null
    private var primaryGain: Double = 0.0
    private var primaryLoss: Double = 0.0
    private var lastSource: Source = Source.NONE
    private var previousRawPrimaryAltitude: Double? = null

    // ---- GPS-only parallel tracker — always runs to catch short bridges ----
    private val gpsWindow = ArrayDeque<WindowSample>()
    private var gpsLastSmoothed: Double? = null
    private var gpsGain: Double = 0.0
    private var gpsLoss: Double = 0.0
    private var previousRawGpsAltitude: Double? = null

    private var cumulativeDistance: Double = 0.0

    enum class Source {
        /** Phone has a barometer — most accurate, sub-meter precision. */
        BAROMETER,
        /** DEM lookup from Open-Meteo — independent of phone sensors, ground-truth from satellite data. */
        DEM,
        /** Raw GPS altitude — noisy, last resort, heavy smoothing applied. */
        GPS,
        NONE,
    }

    /**
     * Seed the gain/loss accumulators with prior totals — used when a ride is
     * RESUMED after the service was killed. Without seeding, the dual trackers
     * would restart at zero and the post-resume update would clobber prior
     * elevation totals via RideStateHolder writes. Smoothing windows stay
     * fresh (only the first few samples post-resume have a less stable grade,
     * which is acceptable). Seeds both primary and GPS trackers so the
     * MAX-merge picks up where it left off regardless of which source is
     * primary after resume.
     */
    fun seed(initialGainMeters: Double, initialLossMeters: Double) {
        primaryGain = initialGainMeters
        primaryLoss = initialLossMeters
        gpsGain = initialGainMeters
        gpsLoss = initialLossMeters
    }

    fun update(
        timestampMs: Long,
        pressureHpa: Float?,
        demElevationM: Float?,
        gpsAltitudeM: Double,
        distanceMovedM: Double,
    ): ElevationSnapshot {
        // Pick the best available source for primary tracking (live altitude + grade).
        val (primaryAltitude, source) = when {
            pressureHpa != null && pressureHpa > 100f ->
                pressureToAltitudeMeters(pressureHpa) to Source.BAROMETER
            demElevationM != null ->
                demElevationM.toDouble() to Source.DEM
            else ->
                gpsAltitudeM to Source.GPS
        }
        lastSource = source

        cumulativeDistance += distanceMovedM

        // Primary source tracking — feeds live altitude display + grade.
        val primarySmoothed = feedPrimaryTracker(timestampMs, primaryAltitude, source)

        // GPS-only parallel tracker — captures urban bridges/flyovers that DEM
        // smooths out. Always runs (regardless of primary source). When primary
        // IS GPS, this duplicates the primary tracker but with longer smoothing,
        // which is fine — the MAX-merge at the end handles it.
        feedGpsTracker(timestampMs, gpsAltitudeM)

        val gradeFraction = computeGradeFromWindow(primaryWindow).coerceIn(
            -maxGradeForSource(source),
            maxGradeForSource(source),
        )

        // Final gain/loss = MAX of two trackers. Rationale: more sensitive
        // detection wins. SRTM DEM may miss a 5m flyover, GPS-tracker catches
        // it. The MAX prevents false low readings while still benefiting from
        // DEM's stability for long gradual climbs (where it shines).
        val combinedGain = maxOf(primaryGain, gpsGain)
        val combinedLoss = maxOf(primaryLoss, gpsLoss)

        return ElevationSnapshot(
            altitudeMeters = primarySmoothed ?: primaryAltitude,
            totalGainMeters = combinedGain,
            totalLossMeters = combinedLoss,
            gradeFraction = gradeFraction,
            vamMetersPerHour = computeVamFromWindow(primaryWindow),
            source = source,
        )
    }

    private fun feedPrimaryTracker(
        timestampMs: Long,
        altitude: Double,
        source: Source,
    ): Double? {
        // Reject physically-impossible vertical motion (vehicle pass-by, GPS jitter, API noise).
        val prevRaw = previousRawPrimaryAltitude
        if (prevRaw != null && primaryWindow.isNotEmpty()) {
            val dtSec = ((timestampMs - primaryWindow.last().timestampMs) / 1000.0).coerceAtLeast(0.001)
            val verticalSpeed = kotlin.math.abs(altitude - prevRaw) / dtSec
            if (verticalSpeed > MAX_DELTA_PER_SECOND_M) {
                previousRawPrimaryAltitude = altitude
                return primaryLastSmoothed
            }
        }
        previousRawPrimaryAltitude = altitude

        primaryWindow.addLast(WindowSample(timestampMs, altitude, cumulativeDistance))

        // Window length depends on source quality — noisier source = longer smoothing.
        val windowSec = when (source) {
            Source.BAROMETER -> 10L
            Source.DEM -> 20L
            Source.GPS -> 60L
            Source.NONE -> 10L
        }
        evictOldSamples(primaryWindow, timestampMs, windowSec)

        val smoothed = primaryWindow.sumOf { it.altitudeMeters } / primaryWindow.size
        val previousSmoothed = primaryLastSmoothed
        if (previousSmoothed != null) {
            val delta = smoothed - previousSmoothed
            val noiseFloor = if (source == Source.GPS) GPS_NOISE_THRESHOLD_M else NOISE_THRESHOLD_M
            if (delta > noiseFloor) primaryGain += delta
            else if (delta < -noiseFloor) primaryLoss += -delta
        }
        primaryLastSmoothed = smoothed
        return smoothed
    }

    private fun feedGpsTracker(timestampMs: Long, gpsAltitude: Double) {
        // Same physical-motion sanity bound as primary tracker.
        val prevRaw = previousRawGpsAltitude
        if (prevRaw != null && gpsWindow.isNotEmpty()) {
            val dtSec = ((timestampMs - gpsWindow.last().timestampMs) / 1000.0).coerceAtLeast(0.001)
            val verticalSpeed = kotlin.math.abs(gpsAltitude - prevRaw) / dtSec
            if (verticalSpeed > MAX_DELTA_PER_SECOND_M) {
                previousRawGpsAltitude = gpsAltitude
                return
            }
        }
        previousRawGpsAltitude = gpsAltitude

        gpsWindow.addLast(WindowSample(timestampMs, gpsAltitude, cumulativeDistance))
        // 30s smoothing — balances responsiveness (catch short bridges) vs noise
        // suppression (GPS altitude is the noisiest source). Shorter than primary's
        // 60s GPS fallback because we want to detect flyover transitions; the
        // MAX-merge with primary still smooths long-term drift.
        evictOldSamples(gpsWindow, timestampMs, 30L)

        val smoothed = gpsWindow.sumOf { it.altitudeMeters } / gpsWindow.size
        val previousSmoothed = gpsLastSmoothed
        if (previousSmoothed != null) {
            val delta = smoothed - previousSmoothed
            // Higher noise floor for GPS — its inherent altitude noise is ±2-3m
            // even with smoothing. Anything under that is sensor jitter.
            if (delta > GPS_NOISE_THRESHOLD_M) gpsGain += delta
            else if (delta < -GPS_NOISE_THRESHOLD_M) gpsLoss += -delta
        }
        gpsLastSmoothed = smoothed
    }

    private fun evictOldSamples(window: ArrayDeque<WindowSample>, nowMs: Long, windowSec: Long) {
        val cutoff = nowMs - windowSec * 1_000L
        while (window.size > 1 && window.first().timestampMs < cutoff) {
            window.removeFirst()
        }
    }

    private fun maxGradeForSource(source: Source): Float = when (source) {
        Source.BAROMETER -> 0.15f
        Source.DEM -> 0.10f
        Source.GPS -> 0.08f
        Source.NONE -> 0.0f
    }

    private fun computeGradeFromWindow(window: ArrayDeque<WindowSample>): Float {
        if (window.size < 2) return 0f
        val first = window.first()
        val last = window.last()
        val dy = last.altitudeMeters - first.altitudeMeters
        val dx = last.cumulativeDistanceMeters - first.cumulativeDistanceMeters
        return if (dx > 1.0) (dy / dx).toFloat() else 0f
    }

    private fun computeVamFromWindow(window: ArrayDeque<WindowSample>): Float {
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
