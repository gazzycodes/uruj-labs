package com.uruj.domain

import kotlinx.serialization.Serializable

/**
 * One row of recorded telemetry. Written as a single NDJSON line per sample
 * during a ride — append-only, so a force-kill loses at most the last sample.
 *
 * Nullable fields mean the sensor was unavailable at this sample; never zero
 * as a sentinel. `hrAgeMs` accompanies `hrBpm` because Health Connect delivers
 * batched samples (not real-time), so HUD needs to render freshness honestly.
 */
@Serializable
data class RideSample(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val speedMetersPerSecond: Float,
    val horizontalAccuracyMeters: Float,
    val pressureHpa: Float? = null,
    val accelMagnitudeG: Float? = null,
    val hrBpm: Int? = null,
    val hrAgeMs: Long? = null,
    val isPaused: Boolean = false,
)
