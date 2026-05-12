package com.uruj.sensor

import kotlinx.coroutines.flow.Flow

data class AccelerometerSample(
    val timestampMs: Long,
    /** Magnitude of linear (gravity-subtracted) acceleration in g. */
    val magnitudeG: Float,
)

interface AccelerometerSource {
    val isAvailable: Boolean
    fun samples(): Flow<AccelerometerSample>
}
