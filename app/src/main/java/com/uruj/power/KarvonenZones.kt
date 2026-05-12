package com.uruj.power

import kotlin.math.roundToInt

/**
 * Karvonen-method (heart rate reserve) training zones — more accurate than naive
 * percentage-of-max because it accounts for the individual's resting HR. Two
 * athletes with the same HRmax but different RHRs have genuinely different
 * effort levels at the same heart rate; HRR-based zones honor that.
 *
 *   HRR = HRmax − HRrest                              ← heart rate reserve
 *   zone_target_bpm = HRrest + (zone_intensity × HRR) ← Karvonen formula
 *
 * Zones map to standard 5-zone endurance training intensities.
 */
class KarvonenZonesCalculator {

    data class Zone(
        val number: Int,
        val name: String,
        val lowerBpm: Int,
        val upperBpm: Int,
        val intensityLow: Float,  // fraction of HRR
        val intensityHigh: Float,
    )

    data class Result(
        val hrReserve: Int,
        val hrMax: Int,
        val hrRest: Int,
        val zones: List<Zone>,
    )

    fun compute(hrMax: Int, hrRest: Int): Result? {
        if (hrMax <= hrRest || hrRest < 30) return null
        val hrr = hrMax - hrRest
        val zones = ZONE_RANGES.mapIndexed { idx, (low, high, name) ->
            Zone(
                number = idx + 1,
                name = name,
                lowerBpm = (hrRest + low * hrr).roundToInt(),
                upperBpm = (hrRest + high * hrr).roundToInt(),
                intensityLow = low,
                intensityHigh = high,
            )
        }
        return Result(hrReserve = hrr, hrMax = hrMax, hrRest = hrRest, zones = zones)
    }

    companion object {
        private val ZONE_RANGES: List<Triple<Float, Float, String>> = listOf(
            Triple(0.50f, 0.60f, "Recovery"),
            Triple(0.60f, 0.70f, "Endurance"),
            Triple(0.70f, 0.80f, "Tempo"),
            Triple(0.80f, 0.90f, "Threshold"),
            Triple(0.90f, 1.00f, "VO2 / Sprint"),
        )
    }
}
