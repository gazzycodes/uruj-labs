package com.uruj.domain

import kotlinx.serialization.Serializable

/**
 * v0.7.7 — which physical sensor produced a given HR-derived reading.
 *
 * URUJ accepts multiple data sources for HR-based metrics (HRR1, RHR sleep):
 *   - BLE chest strap (Magene H613) via 24/7 BiometricService NDJSON — per-beat
 *     resolution, low latency, ECG-precision RR intervals
 *   - Samsung Fit Band 3 via Health Connect HeartRateRecord — batched samples
 *     at ~30 sec intervals, written 15-30 min after the activity
 *
 * Same metric, different precision. Per [[reference_lab_level_uruj]] rule 1
 * (source label), EVERY reading carries its source so the rider knows what
 * fed the number. No more "where did this come from?" guessing.
 *
 * Selection logic (in calculators):
 *   1. STRAP — strap covered the relevant window with ≥60% sample density
 *   2. BAND — strap missing or low coverage, HC has data, use HC
 *   3. MIXED — both contributed parts of the window (e.g. strap dropped
 *      mid-recovery, HC filled the gap)
 *   4. UNKNOWN_LEGACY — reading was computed BEFORE v0.7.7 shipped (no
 *      source recorded at the time). Don't retroactively relabel.
 */
@Serializable
enum class SensorSource {
    STRAP,           // BLE chest strap NDJSON
    BAND,            // Samsung Fit Band 3 → HC HeartRateRecord
    MIXED,           // Both contributed within the window
    UNKNOWN_LEGACY,  // Computed pre-v0.7.7, source not recorded
    ;

    fun displayShort(): String = when (this) {
        STRAP -> "strap"
        BAND -> "band"
        MIXED -> "mixed"
        UNKNOWN_LEGACY -> "legacy"
    }

    fun displayFull(): String = when (this) {
        STRAP -> "from chest strap (per-beat)"
        BAND -> "from band (batched)"
        MIXED -> "strap + band (mixed window)"
        UNKNOWN_LEGACY -> "legacy (pre-v0.7.7 reading)"
    }
}
