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

        /**
         * v0.9.14 — single source of truth for HR → Karvonen zone classification.
         * Every surface that bins HR into zones (TIZ card, Route map polyline,
         * HUD waveform, Audio coach, Polarized 80/20 compliance) calls THIS
         * function. Pre-v0.9.14 TIZ + Route map used %-of-max (unpersonalized);
         * unification on Karvonen gives accurate, RHR-aware zones across every
         * surface.
         *
         * Returns:
         *   0 = below Z1 (below 50% HRR — true recovery / rest)
         *   1 = Z1 Recovery (50-60% HRR)
         *   2 = Z2 Endurance (60-70% HRR)
         *   3 = Z3 Tempo (70-80% HRR)
         *   4 = Z4 Threshold (80-90% HRR)
         *   5 = Z5 VO2/Sprint (≥90% HRR)
         *
         * Note: index 0 represents BELOW Z1 — historically TIZ collapsed this
         * into Z1, but Karvonen recognizes "below recovery zone" (e.g. resting
         * HR while standing still) as distinct. Callers can map to display
         * however they want. TimeInZoneCalculator treats 0 as Z1 for the
         * polarized 80/20 + 5-zone bar chart compatibility.
         */
        @JvmStatic
        fun classifyKarvonenZone(hrBpm: Int, hrMax: Int, hrRest: Int): Int {
            if (hrMax <= hrRest || hrRest < 30) return 1  // bad inputs → safe Z1
            val hrr = hrMax - hrRest
            if (hrr <= 0) return 1
            val fraction = (hrBpm - hrRest).toFloat() / hrr.toFloat()
            return when {
                fraction < 0.50f -> 0      // below Z1 — caller decides display
                fraction < 0.60f -> 1
                fraction < 0.70f -> 2
                fraction < 0.80f -> 3
                fraction < 0.90f -> 4
                else -> 5
            }
        }
    }
}
