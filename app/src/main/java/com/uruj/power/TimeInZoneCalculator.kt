package com.uruj.power

import java.time.Instant

/**
 * Computes time spent in each Karvonen zone for a recorded ride.
 *
 * v0.9.14 — migrated from %max-HR to Karvonen (HR Reserve). The two systems
 * disagree meaningfully for trained athletes: rider with RHR 43, max 194 at
 * 130 bpm reads as 67% max (Z2) under old system but 58% HRR (Z1) under
 * Karvonen. Karvonen is more accurate because it accounts for the rider's
 * specific reserve range, not just absolute %-of-max which treats untrained
 * and elite the same.
 *
 * All zone-rendering surfaces (TIZ card, Route map polyline, HUD waveform,
 * Audio coach, Polarized 80/20) now share
 * [KarvonenZonesCalculator.classifyKarvonenZone] as the single classifier.
 *
 * Past-ride retroactive re-classification: zones are NOT stored in
 * StoredRideSummary — recomputed on each summary open from saved HR
 * samples. So past rides re-bucket under the new system automatically.
 * Same HR data, more accurate math.
 *
 * Polarized training compliance metric: 80% easy (Z1-Z2), ~0% gray (Z3),
 * 20% hard (Z4-Z5). Blummenfelt/Seiler/Stöggl Norwegian-method discipline.
 * The Z3 "gray zone" trap stays the same concept; thresholds shift slightly
 * with Karvonen. For trained athletes with low RHR, the new system
 * typically shows MORE time in Z1-Z2 (better polarized compliance) because
 * %-of-max under-counted "easy" zone for them.
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
     * @param restingHrBpm rider's Athletic RHR (sleep-window median). Required
     *   for Karvonen — Karvonen needs HR Reserve = max - rest. v0.9.14+ all
     *   callers pass profile.restingHrBpm. Defaults to 50 (athletic floor) if
     *   the rider profile hasn't populated yet.
     * @param rideEndMs ride end timestamp; used to cap the trailing sample's contribution
     */
    fun compute(
        samples: List<Pair<Instant, Int>>,
        maxHrBpm: Int,
        restingHrBpm: Int,
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

            // v0.9.14 — Karvonen classification. Returns 0..5; we collapse the
            // "below Z1" bucket (return 0) into Z1 (index 0 here) for the
            // 5-zone bar chart + polarized compliance compatibility.
            // KarvonenZonesCalculator.classifyKarvonenZone returns 1-5 for in-
            // band readings and 0 for sub-recovery (resting / coast). We map
            // 0 → bar-chart index 0 = Z1, and 1-5 → indices 0-4.
            val karvonenZone = KarvonenZonesCalculator.classifyKarvonenZone(
                hrBpm = bpm,
                hrMax = maxHrBpm,
                hrRest = restingHrBpm,
            )
            val zoneIdx = when (karvonenZone) {
                0 -> 0      // below Z1 → display as Z1 for bar chart
                else -> (karvonenZone - 1).coerceIn(0, 4)
            }
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
}
