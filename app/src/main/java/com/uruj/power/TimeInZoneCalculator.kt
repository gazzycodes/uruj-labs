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
        /**
         * ms spent in each zone, indexed as:
         *   [0] = Sub-Z1 / sub-recovery (HR < 50% HRR — below Karvonen Z1 floor)
         *   [1] = Z1 Recovery (50-60% HRR)
         *   [2] = Z2 Endurance (60-70% HRR)
         *   [3] = Z3 Tempo (70-80% HRR)
         *   [4] = Z4 Threshold (80-90% HRR)
         *   [5] = Z5 VO2/Sprint (≥90% HRR)
         *
         * v0.9.17 — 6-bucket array (was 5; sub-Z1 was collapsed into Z1 for
         * display compatibility). Sub-Z1 now has its own slot so rides like
         * deep-recovery spins that sit below the Z1 floor are visible as
         * such. SessionCoach + RouteMapViewModel unaffected (both classify
         * via KarvonenZonesCalculator directly, don't read this array).
         */
        val timeInZoneMs: LongArray,
        /** Total time covered by HR samples (sum of timeInZoneMs). */
        val totalMs: Long,
        /**
         * Fraction of total in Sub-Z1 + Z1 + Z2 (low-intensity / polarized
         * "easy" per Seiler/Stöggl). Sub-Z1 counts toward easy because it
         * is definitionally MORE below LT1 than Z1 — easier, not harder.
         * The gray-zone trap is Z3 mid-intensity bleed, not sub-recovery.
         */
        val easyPct: Float,
        /** Fraction in Z3 (gray zone — Blummenfelt's discouraged middle). */
        val grayPct: Float,
        /** Fraction in Z4+Z5 (hard / threshold + VO2). */
        val hardPct: Float,
        /** Number of HR samples that contributed to the analysis. */
        val sampleCount: Int,
        /**
         * The bpm value that separates Sub-Z1 from Z1 (= restingHrBpm +
         * 0.50 × HRR). Surfaced so the UI can label "Sub-Z1 < X bpm" without
         * recomputing the Karvonen math. v0.9.17.
         */
        val subRecoveryFloorBpm: Int,
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

        // v0.9.17 — 6 buckets: index 0 = Sub-Z1, 1-5 = Z1-Z5.
        val zoneMs = LongArray(6)
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

            // v0.9.17 — Karvonen classifier returns 0..5; we now keep its
            // semantics 1:1 (0 = Sub-Z1, 1-5 = Z1-Z5) so the TIZ bar chart
            // can display sub-recovery time honestly. Pre-v0.9.17 collapsed
            // 0 into Z1 for 5-zone bar compatibility — that hid the truth
            // when riders cruised below their Z1 floor (e.g. recovery rides
            // at HR < 50% HRR). SessionCoach + RouteMapViewModel are
            // unaffected (they call the classifier directly, not this array).
            val karvonenZone = KarvonenZonesCalculator.classifyKarvonenZone(
                hrBpm = bpm,
                hrMax = maxHrBpm,
                hrRest = restingHrBpm,
            )
            val zoneIdx = karvonenZone.coerceIn(0, 5)
            zoneMs[zoneIdx] = zoneMs[zoneIdx] + deltaMs
            totalMs += deltaMs
            sampleCount++
        }

        if (totalMs == 0L) return null

        // Polarized 80/20 (Seiler/Stöggl): "easy" = below LT1. Sub-Z1 + Z1 + Z2
        // are all below LT1, so all three count toward easy. Sub-Z1 is the
        // most-easy of all (definitionally more low-intensity than Z1), not
        // a "doesn't count" tier.
        val easyMs = zoneMs[0] + zoneMs[1] + zoneMs[2]
        val grayMs = zoneMs[3]
        val hardMs = zoneMs[4] + zoneMs[5]

        // Z1 floor = restHR + 50% HRR (Karvonen). Surfaced for UI labelling
        // ("Sub-Z1 < N bpm") without re-running the classifier.
        val hrr = maxHrBpm - restingHrBpm
        val subRecoveryFloor = if (hrr > 0) (restingHrBpm + 0.50f * hrr).toInt() else restingHrBpm

        return Result(
            timeInZoneMs = zoneMs,
            totalMs = totalMs,
            easyPct = easyMs.toFloat() / totalMs,
            grayPct = grayMs.toFloat() / totalMs,
            hardPct = hardMs.toFloat() / totalMs,
            sampleCount = sampleCount,
            subRecoveryFloorBpm = subRecoveryFloor,
        )
    }
}
