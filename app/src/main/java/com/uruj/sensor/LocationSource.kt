package com.uruj.sensor

import kotlinx.coroutines.flow.Flow

data class LocationSample(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val speedMetersPerSecond: Float,
    val horizontalAccuracyMeters: Float,
    val bearingDeg: Float? = null,
)

interface LocationSource {
    val isAvailable: Boolean

    /**
     * Cold Flow that starts GPS updates on subscription and stops on cancellation.
     * `intervalMillis` is a request, not a guarantee — Android fuses with system policy.
     */
    fun samples(intervalMillis: Long = 1_000L): Flow<LocationSample>
}
