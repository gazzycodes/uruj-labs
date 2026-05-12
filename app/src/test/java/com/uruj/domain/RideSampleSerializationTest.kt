package com.uruj.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class RideSampleSerializationTest {

    private val json = Json { encodeDefaults = false }

    @Test
    fun `roundtrips a sample with all fields`() {
        val sample = RideSample(
            timestampMs = 1_715_000_000_000L,
            latitude = 22.5726,
            longitude = 88.3639,
            altitudeMeters = 9.5,
            speedMetersPerSecond = 8.2f,
            horizontalAccuracyMeters = 4.1f,
            pressureHpa = 1013.25f,
            accelMagnitudeG = 0.08f,
            hrBpm = 142,
            hrAgeMs = 7_500L,
            isPaused = false,
        )

        val encoded = json.encodeToString(RideSample.serializer(), sample)
        val decoded = json.decodeFromString(RideSample.serializer(), encoded)

        assertEquals(sample, decoded)
    }

    @Test
    fun `roundtrips a sample with no barometer or HR`() {
        val sample = RideSample(
            timestampMs = 1_715_000_000_000L,
            latitude = 22.5726,
            longitude = 88.3639,
            altitudeMeters = 9.5,
            speedMetersPerSecond = 0.0f,
            horizontalAccuracyMeters = 12.0f,
            isPaused = true,
        )

        val encoded = json.encodeToString(RideSample.serializer(), sample)
        val decoded = json.decodeFromString(RideSample.serializer(), encoded)

        assertEquals(sample, decoded)
        // Nullable fields should not appear when encodeDefaults = false
        check(!encoded.contains("pressureHpa")) { "expected absent: $encoded" }
        check(!encoded.contains("hrBpm")) { "expected absent: $encoded" }
    }
}
