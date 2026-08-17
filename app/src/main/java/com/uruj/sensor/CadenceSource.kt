package com.uruj.sensor

/**
 * v0.9.78 — one cadence reading from a BLE Cycling Speed and Cadence sensor
 * (Magene S314 on the left crank, or any standards-compliant CSC device).
 *
 * Unlike [HrSample] there is no "measured vs received" split: the sensor stamps
 * the crank event with its own 1024 Hz clock and the rate is derived from those
 * stamps, so the wall clock here is only ever used for freshness / coasting
 * detection — never for the rpm itself.
 */
data class CadenceSample(
    /** When the GATT notification arrived. */
    val receivedAtMs: Long,
    /** Live cadence. 0 means freewheeling — a real measurement, not "unknown". */
    val cadenceRpm: Float,
    /** Crank revolutions counted since this connection opened (ride stroke count). */
    val cumulativeCrankRevs: Long,
    /**
     * False when the sensor is reporting wheel data only — i.e. it is configured
     * or mounted as a SPEED sensor. Dual-mode sensors like the S314 can end up
     * here after a re-mount, and silently showing no cadence would look like a
     * broken app rather than a mis-configured sensor.
     */
    val hasCrankData: Boolean,
)
