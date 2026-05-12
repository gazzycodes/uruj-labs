package com.uruj.service

/**
 * Decides whether the rider has stopped. "Stopped" requires both signals to be below
 * threshold for at least `windowMs`; either signal exceeding threshold counts as motion
 * and resets the clock immediately.
 *
 * Combining GPS speed with linear accelerometer magnitude is important: GPS speed alone
 * drifts to non-zero at standstill, and accelerometer alone can be quiet on smooth
 * pavement at constant velocity. The intersection is more reliable than either signal.
 */
class AutoPauseDetector(
    private val windowMs: Long = 5_000L, // matches Garmin / Strava / Wahoo behavior
    private val speedThresholdMs: Float = 0.28f, // ~1 kph
    private val accelThresholdG: Float = 0.2f,
) {
    private var lastMovementMs: Long? = null

    fun observe(timestampMs: Long, speedMs: Float, accelG: Float): Boolean {
        val isMoving = speedMs >= speedThresholdMs || accelG >= accelThresholdG
        if (isMoving) {
            lastMovementMs = timestampMs
            return false
        }
        val last = lastMovementMs
        if (last == null) {
            // First observation, stillness — establish the baseline but don't pause yet.
            lastMovementMs = timestampMs
            return false
        }
        return (timestampMs - last) >= windowMs
    }

    fun reset() {
        lastMovementMs = null
    }
}
