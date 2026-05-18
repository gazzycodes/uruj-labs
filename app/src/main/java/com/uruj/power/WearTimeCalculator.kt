package com.uruj.power

/**
 * v0.7.8 — sensor wear-time computation. Pure function over sample
 * timestamps; no IO.
 *
 * Methodology: bucket the time window into 1-minute slots. A minute is
 * "worn" if AT LEAST ONE sample landed inside it. Counts:
 *   - strapWornMinutes — strap was streaming during that minute
 *   - bandWornMinutes — band wrote an HR sample during that minute
 *   - combinedWornMinutes — UNION (either sensor was capturing)
 *
 * The combined coverage is the lab-grade metric the user actually cares
 * about: even when one sensor is off (shower, charging), the other
 * captures. Running both gives near-100% continuity on a normal day.
 *
 * Longest-gap streaks are the consecutive-zeros runs in each minute
 * array — useful UX context ("strap was off for 2h 30m around noon —
 * that's the charging window").
 *
 * Edge cases:
 *   - Empty sample stream → 0 minutes worn, longest gap = total minutes
 *   - Window too short (<1 min) → 1-minute floor
 *   - Sample timestamp outside window → ignored
 *   - Multiple samples in same minute → minute counted ONCE
 */
class WearTimeCalculator {

    data class Result(
        /** Length of the analysis window in minutes. For "today" = minutes
         *  since local midnight. */
        val totalWindowMinutes: Int,
        /** Minutes strap captured at least one sample. */
        val strapWornMinutes: Int,
        /** Minutes band captured at least one sample. */
        val bandWornMinutes: Int,
        /** Minutes EITHER sensor captured a sample — the redundancy metric. */
        val combinedWornMinutes: Int,
        /** Longest run of consecutive minutes with no strap sample. */
        val longestStrapGapMinutes: Int,
        /** Longest run of consecutive minutes with no band sample. */
        val longestBandGapMinutes: Int,
        /** Longest run with NEITHER sensor — the only true blind window. */
        val longestCombinedGapMinutes: Int,
    ) {
        fun strapPercent(): Float = pct(strapWornMinutes, totalWindowMinutes)
        fun bandPercent(): Float = pct(bandWornMinutes, totalWindowMinutes)
        fun combinedPercent(): Float = pct(combinedWornMinutes, totalWindowMinutes)

        private fun pct(worn: Int, total: Int): Float =
            if (total > 0) (worn.toFloat() / total) * 100f else 0f
    }

    /**
     * @param strapSampleTimestampsMs epoch-ms timestamps of strap samples
     *   (e.g. from 24/7 NDJSON `bpm > 0` samples in the window). Strap
     *   writes per-second cadence → bucketize at 1-minute resolution.
     * @param bandSampleTimestampsMs epoch-ms timestamps of band HR samples
     *   (e.g. from HC HeartRateRecord samples). Band at IDLE writes 1 sample
     *   per 10-15 min, so 1-min bucketing would undercount wear. v0.7.9 fix:
     *   each band sample marks ±7.5 min around it as "worn" (inference
     *   window). A continuously-worn-at-rest period correctly reads ~100%
     *   instead of ~10%.
     * @param windowStartMs window start (inclusive)
     * @param windowEndMs window end (exclusive)
     * @param bandInferenceWindowMinutes how far on either side of a band
     *   sample to mark as worn. Default 15 = matches band's max-idle
     *   cadence. Lowering to 5 makes the band stat more conservative
     *   (only counts the tighter window around each sample); raising to
     *   30 is more generous.
     */
    fun compute(
        strapSampleTimestampsMs: List<Long>,
        bandSampleTimestampsMs: List<Long>,
        windowStartMs: Long,
        windowEndMs: Long,
        bandInferenceWindowMinutes: Int = 15,
    ): Result {
        val totalMinutes = ((windowEndMs - windowStartMs) / 60_000L)
            .toInt()
            .coerceAtLeast(1)

        // Strap: 1-min buckets (per-second cadence supports this resolution)
        val strapWorn = bucketByMinute(strapSampleTimestampsMs, windowStartMs, totalMinutes)
        // Band: inference window ±half-window around each sample
        val bandWorn = bucketWithInference(
            samples = bandSampleTimestampsMs,
            windowStartMs = windowStartMs,
            totalMinutes = totalMinutes,
            inferenceWindowMin = bandInferenceWindowMinutes,
        )
        val combinedWorn = BooleanArray(totalMinutes) { i ->
            strapWorn[i] || bandWorn[i]
        }
        return Result(
            totalWindowMinutes = totalMinutes,
            strapWornMinutes = strapWorn.count { it },
            bandWornMinutes = bandWorn.count { it },
            combinedWornMinutes = combinedWorn.count { it },
            longestStrapGapMinutes = longestZeroRun(strapWorn),
            longestBandGapMinutes = longestZeroRun(bandWorn),
            longestCombinedGapMinutes = longestZeroRun(combinedWorn),
        )
    }

    private fun bucketByMinute(
        samples: List<Long>,
        windowStartMs: Long,
        totalMinutes: Int,
    ): BooleanArray {
        val arr = BooleanArray(totalMinutes)
        val endMs = windowStartMs + totalMinutes * 60_000L
        for (t in samples) {
            if (t < windowStartMs || t >= endMs) continue
            val idx = ((t - windowStartMs) / 60_000L).toInt()
            if (idx in arr.indices) arr[idx] = true
        }
        return arr
    }

    /**
     * v0.7.9 — fill ±halfWindow minutes around each sample. Used for band
     * which writes only at ~10-15 min cadence at rest; without this
     * inference, idle-worn bands would falsely read as ~10% wear.
     */
    private fun bucketWithInference(
        samples: List<Long>,
        windowStartMs: Long,
        totalMinutes: Int,
        inferenceWindowMin: Int,
    ): BooleanArray {
        val arr = BooleanArray(totalMinutes)
        val endMs = windowStartMs + totalMinutes * 60_000L
        val halfWindow = inferenceWindowMin / 2
        for (t in samples) {
            if (t < windowStartMs || t >= endMs) continue
            val centerIdx = ((t - windowStartMs) / 60_000L).toInt()
            val startIdx = (centerIdx - halfWindow).coerceAtLeast(0)
            val endIdx = (centerIdx + halfWindow).coerceAtMost(totalMinutes - 1)
            for (i in startIdx..endIdx) arr[i] = true
        }
        return arr
    }

    private fun longestZeroRun(arr: BooleanArray): Int {
        var maxRun = 0
        var current = 0
        for (b in arr) {
            if (b) {
                current = 0
            } else {
                current++
                if (current > maxRun) maxRun = current
            }
        }
        return maxRun
    }
}
