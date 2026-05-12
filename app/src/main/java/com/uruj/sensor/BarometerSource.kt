package com.uruj.sensor

import kotlinx.coroutines.flow.Flow

data class BarometerSample(
    val timestampMs: Long,
    val pressureHpa: Float,
)

interface BarometerSource {
    val isAvailable: Boolean
    fun samples(): Flow<BarometerSample>
}
