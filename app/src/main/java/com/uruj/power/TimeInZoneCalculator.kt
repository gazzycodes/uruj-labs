package com.uruj.power

import java.time.Instant

/**
 * Computes time spent in each %max-HR zone for a recorded ride. Designed for
 * POST-RIDE analysis on Fit Band 3 — Samsung's HR batch sync lands in Health
 * Connect 5-30 min after workout ends, so live time-in-zone tracking during
 * the ride isn't reliable. After the batch arrives, we time-align the samples
 * and compute accurate zone distribution.
 *
 * Same %max-HR thresholds as RouteMapViewModel (Z1<60%, Z2<70%, Z3<80%,
 * Z4<90%, Z5≥90%) so the visualization (route map polyline) and analysis
 * (this calculator) are consistent.
 *
 * Polarized training compliance metric included — Blummenfelt's discipline
 * benchmark: 80% easy (Z1-Z2), ~0% gray (Z3), 20% hard (Z4-Z5). The Z3 "gray
 * zone" is the common training trap — not easy enough for aerobic base, not
 * hard enough for threshold/VO2 work. Tracking time spent in each band gives
 * the rider an honest weekly compliance signal.
 */
class TimeInZoneCalculator {

    data class Result(
        /** ms spent in each zone, indexed by HrZone.ordinal (0=Z1, 4=Z5). */
        val timeInZoneMs: LongArray,
        /** Total time covered by HR samples (sum of timeInZoneMs). */
        val totalMs: Long,
        /** Fraction of total in Z1+Z2 (easy / polarized base). */
        val easyPct: Float,
        /** Fraction in Z3 (gray zone — Blummenfelt's discouraged middle). */
        val grayPct: Float,
        /** Fraction in Z4+Z5 (hard / threshold + VO2). */
        val hardPct: Float,
        /** Number of HR samples that contributed to the analysis. */
        val sampleCount: Int,
    ) {
        // LongArray needs explicit equals/hashCode override
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Result) return false
            return timeInZoneMs.contentEquals(other.timeInZoneMs) &&
                totalMs == other.totalMs &&
                easyPct == other.easyPct &&
                grayPct == other.grayPct &&
                hardPct == other.hardPct &&
                sampleCount == other.sampleCount
        }
        override fun hashCode(): Int {
            var h = timeInZoneMs.contentHashCode()
            h = 31 * h + totalMs.hashCode()
            h = 31 * h + easyPct.hashCode()
            h = 31 * h + grayPct.hashCode()
            h = 31 * h + hardPct.hashCode()
            h = 31 * h + sampleCount
            return h
        }
    }

    /**
     * @param samples sorted ascending by timestamp, (Instant, bpm) pairs from HC
     * @param maxHrBpm rider's max HR (formula or measured)
     * @param rideEndMs ride end timestamp; used to cap the trailing sample's contribution
     */
    fun compute(
        samples: List<Pair<Instant, Int>>,
        maxHrBpm: Int,
        rideEndMs: Long,
    ): Result? {
        if (samples.size < 2 || maxHrBpm <= 0) return null

        val zoneMs = LongArray(5)
        var totalMs = 0L
        var sampleCount = 0

        // Each sample is treated as representative of the time until the NEXT
        // sample (or ride end, for the last one). Gap >5 min between samples
        // is skipped — likely a sensor dropout, don't fabricate continuous data.
        val maxGapMs = 5 * 60_000L

        for (i in samples.indices) {
            val (time, bpm) = samples[i]
            if (bpm < 35 || bpm > 250) continue  // glitch filter

            val nextTimeMs = if (i + 1 < samples.size) samples[i + 1].first.toEpochMilli()
            else rideEndMs

            val deltaMs = (nextTimeMs - time.toEpochMilli()).coerceAtLeast(0L)
            if (deltaMs > maxGapMs) continue
            if (deltaMs == 0L) continue

            val zoneIdx = classifyZoneIndex(bpm, maxHrBpm)
            zoneMs[zoneIdx] = zoneMs[zoneIdx] + deltaMs
            totalMs += deltaMs
            sampleCount++
        }

        if (totalMs == 0L) return null

        val easyMs = zoneMs[0] + zoneMs[1]
        val grayMs = zoneMs[2]
        val hardMs = zoneMs[3] + zoneMs[4]

        return Result(
            timeInZoneMs = zoneMs,
            totalMs = totalMs,
            easyPct = easyMs.toFloat() / totalMs,
            grayPct = grayMs.toFloat() / totalMs,
            hardPct = hardMs.toFloat() / totalMs,
            sampleCount = sampleCount,
        )
    }

    /** %max-HR zone classification. Z1<60%, Z2<70%, Z3<80%, Z4<90%, Z5≥90%. */
    private fun classifyZoneIndex(bpm: Int, maxHrBpm: Int): Int {
        val pct = bpm.toFloat() / maxHrBpm
        return when {
            pct < 0.60f -> 0
            pct < 0.70f -> 1
            pct < 0.80f -> 2
            pct < 0.90f -> 3
            else -> 4
        }
    }
}
