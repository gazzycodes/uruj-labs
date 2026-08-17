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
    /**
     * Beat-to-beat RR intervals (ms) from a BLE chest strap during this sample
     * window. Empty when HR source is HC-batched or no HR available. Preserved
     * here so post-ride / on-device RMSSD HRV computation can replay the
     * authoritative per-beat data later. v0.5.1 introduction.
     */
    val rrIntervalsMs: List<Int> = emptyList(),
    /**
     * Source of the HR data for this sample (BLE_CHEST_STRAP / HC_BATCHED / null).
     * Lets per-ride analysis know whether HRV is computable + how to weight
     * accuracy. Stored as the string form of HrSample.Source for forward-compat
     * if more sources are added later. v0.5.1 introduction.
     */
    val hrSource: String? = null,
    /**
     * Crank cadence in rpm from a BLE CSC sensor (Magene S314) at this tick.
     * `0` is a real reading — the rider was freewheeling. `null` means no
     * cadence sensor was connected, which is why this is nullable rather than
     * defaulting to zero: post-ride analysis must be able to tell "coasting"
     * apart from "we had no sensor". v0.9.78 introduction; older NDJSON rides
     * simply omit the field and decode to null.
     */
    val cadenceRpm: Int? = null,
)
