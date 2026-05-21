package com.uruj.power

import com.uruj.data.RideHrSample
import com.uruj.data.StoredRideSummary
import kotlin.math.roundToInt
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * v0.9.22 — weekly polarized-compliance analyzer.
 *
 * Given a list of (ride summary, ride HR samples) pairs in the current ISO
 * week (Mon-Sun) plus the rider's profile, this aggregates each ride's
 * TimeInZoneCalculator result and produces:
 *
 *   - Per-day TIZ (one entry per day Mon-Sun; days with no rides → null)
 *   - Weighted-average weekly easy / gray / hard fractions
 *   - Total moving time in zones across the week
 *   - The rider's sub-Z1 floor for legend labelling (uses current profile)
 *
 * Architectural notes:
 *   - Uses [TimeInZoneCalculator] under the hood — same Karvonen classifier
 *     as TIZ card / Route map / HUD / Audio coach (v0.9.14 + v0.9.17). One
 *     source of zone truth.
 *   - TIZ is NOT cached anywhere persistent (v0.9.17 design — recomputed on
 *     summary open so historic rides auto-bucket under new rules).
 *   - Sub-Z1 floor displayed uses the CURRENT profile RHR — simpler than
 *     showing per-ride floors and more useful for "where should I be this
 *     week" framing. Per-ride floors live on each ride's own summary.
 *   - Weekly aggregate is a TIME-WEIGHTED average (not simple-average of
 *     percentages) so a 2-hour ride dominates a 20-min spin in the easy %.
 */
class WeeklyPolarizedAnalyzer(
    private val tizCalculator: TimeInZoneCalculator = TimeInZoneCalculator(),
) {

    data class DailyTiz(
        /** ISO day-of-week (Mon=1 ... Sun=7) */
        val dayOfWeek: DayOfWeek,
        /** Date label for the bar (e.g. "Mon" / "Tue" — caller can format) */
        val date: LocalDate,
        /**
         * TIZ buckets ms, indices match TimeInZoneCalculator.Result.timeInZoneMs
         * (0 = sub-Z1, 1-5 = Z1-Z5). Null when no ride OR no usable HR data.
         */
        val timeInZoneMs: LongArray?,
        /** Sum of all bucket times. 0 when no ride. */
        val totalMs: Long,
        /** Friendly summary for tap-to-inspect (future). Null when no ride. */
        val rideCount: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DailyTiz) return false
            return dayOfWeek == other.dayOfWeek &&
                date == other.date &&
                ((timeInZoneMs == null && other.timeInZoneMs == null) ||
                    (timeInZoneMs != null && other.timeInZoneMs != null &&
                        timeInZoneMs.contentEquals(other.timeInZoneMs))) &&
                totalMs == other.totalMs &&
                rideCount == other.rideCount
        }
        override fun hashCode(): Int {
            var h = dayOfWeek.hashCode()
            h = 31 * h + date.hashCode()
            h = 31 * h + (timeInZoneMs?.contentHashCode() ?: 0)
            h = 31 * h + totalMs.hashCode()
            h = 31 * h + rideCount
            return h
        }
    }

    data class WeekResult(
        /** Monday of the current ISO week (inclusive). */
        val weekStart: LocalDate,
        /** Sunday of the current ISO week (inclusive). */
        val weekEnd: LocalDate,
        /** Always 7 entries Mon..Sun, in order. Days with no rides have null TIZ. */
        val days: List<DailyTiz>,
        /** Sum of bucket times across all 7 days. 0 when nothing rides this week. */
        val weeklyTimeInZoneMs: LongArray,
        /** Sum of weeklyTimeInZoneMs. */
        val weeklyTotalMs: Long,
        /** Sub-Z1 + Z1 + Z2 fraction (v0.9.17 sub-Z1 counts as easy). */
        val weeklyEasyPct: Float,
        /** Z3 fraction (gray-zone trap). */
        val weeklyGrayPct: Float,
        /** Z4 + Z5 fraction (hard / threshold + VO2). */
        val weeklyHardPct: Float,
        /** Number of rides logged in the week (regardless of HR data quality). */
        val rideCount: Int,
        /** Number of rides that contributed HR data to the aggregate. */
        val ridesWithHrCount: Int,
        /** Sub-Z1 floor in bpm from current profile (legend display). */
        val subRecoveryFloorBpm: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WeekResult) return false
            return weekStart == other.weekStart &&
                weekEnd == other.weekEnd &&
                days == other.days &&
                weeklyTimeInZoneMs.contentEquals(other.weeklyTimeInZoneMs) &&
                weeklyTotalMs == other.weeklyTotalMs &&
                weeklyEasyPct == other.weeklyEasyPct &&
                weeklyGrayPct == other.weeklyGrayPct &&
                weeklyHardPct == other.weeklyHardPct &&
                rideCount == other.rideCount &&
                ridesWithHrCount == other.ridesWithHrCount &&
                subRecoveryFloorBpm == other.subRecoveryFloorBpm
        }
        override fun hashCode(): Int {
            var h = weekStart.hashCode()
            h = 31 * h + weekEnd.hashCode()
            h = 31 * h + days.hashCode()
            h = 31 * h + weeklyTimeInZoneMs.contentHashCode()
            h = 31 * h + weeklyTotalMs.hashCode()
            h = 31 * h + weeklyEasyPct.hashCode()
            h = 31 * h + weeklyGrayPct.hashCode()
            h = 31 * h + weeklyHardPct.hashCode()
            h = 31 * h + rideCount
            h = 31 * h + ridesWithHrCount
            h = 31 * h + subRecoveryFloorBpm
            return h
        }
    }

    /**
     * Compute the weekly result for the ISO week containing [today].
     *
     * @param today reference date (almost always LocalDate.now(zone))
     * @param zone timezone — must match the convention used elsewhere
     *   (ZoneId.systemDefault()) so ride dates line up consistently
     * @param ridesWithHr already-loaded ride summaries paired with their
     *   already-loaded HR samples (caller is responsible for the I/O and
     *   for filtering to MIN_USEFUL_HR_SAMPLES). Rides outside the week
     *   are tolerated (ignored).
     * @param maxHrBpm rider's effective max HR (profile)
     * @param restingHrBpm rider's Athletic RHR (profile)
     */
    fun compute(
        today: LocalDate,
        zone: ZoneId,
        ridesWithHr: List<Pair<StoredRideSummary, List<RideHrSample>>>,
        maxHrBpm: Int,
        restingHrBpm: Int,
    ): WeekResult {
        val weekStart = today.with(WeekFields.ISO.dayOfWeek(), 1L)  // Monday
        val weekEnd = weekStart.plusDays(6)

        // Group rides into days
        val ridesByDay = mutableMapOf<LocalDate, MutableList<Pair<StoredRideSummary, List<RideHrSample>>>>()
        for ((summary, samples) in ridesWithHr) {
            val rideDate = Instant.ofEpochMilli(summary.startedAtMs).atZone(zone).toLocalDate()
            if (rideDate < weekStart || rideDate > weekEnd) continue
            ridesByDay.getOrPut(rideDate) { mutableListOf() }.add(summary to samples)
        }

        // Build per-day TIZ — 7 entries, Mon..Sun
        val days = (0L..6L).map { offset ->
            val date = weekStart.plusDays(offset)
            val dayRides = ridesByDay[date].orEmpty()
            if (dayRides.isEmpty()) {
                DailyTiz(
                    dayOfWeek = date.dayOfWeek,
                    date = date,
                    timeInZoneMs = null,
                    totalMs = 0L,
                    rideCount = 0,
                )
            } else {
                // Sum TIZ buckets across all rides on this day. Each ride is
                // recomputed via TimeInZoneCalculator — same classifier as
                // every other zone-rendering surface.
                val dayBuckets = LongArray(6)
                var dayTotal = 0L
                for ((summary, samples) in dayRides) {
                    val timed = samples.map { Instant.ofEpochMilli(it.timestampMs) to it.bpm }
                    val tiz = tizCalculator.compute(
                        samples = timed,
                        maxHrBpm = maxHrBpm,
                        restingHrBpm = restingHrBpm,
                        rideEndMs = summary.endedAtMs,
                    )
                    if (tiz != null) {
                        for (i in 0..5) dayBuckets[i] += tiz.timeInZoneMs[i]
                        dayTotal += tiz.totalMs
                    }
                }
                DailyTiz(
                    dayOfWeek = date.dayOfWeek,
                    date = date,
                    timeInZoneMs = if (dayTotal > 0) dayBuckets else null,
                    totalMs = dayTotal,
                    rideCount = dayRides.size,
                )
            }
        }

        // Weekly aggregate is the SUM of per-day buckets — equivalent to a
        // time-weighted average of per-ride percentages, which is what we
        // want (a 2-hour ride dominates a 20-min spin in the easy %).
        val weeklyBuckets = LongArray(6)
        for (day in days) {
            val buckets = day.timeInZoneMs ?: continue
            for (i in 0..5) weeklyBuckets[i] += buckets[i]
        }
        val weeklyTotal = weeklyBuckets.sum()

        // v0.9.17 — easy = sub-Z1 + Z1 + Z2 (sub-Z1 is MORE low-intensity
        // than Z1, more easy per Seiler/Stöggl polarized model).
        val easyMs = weeklyBuckets[0] + weeklyBuckets[1] + weeklyBuckets[2]
        val grayMs = weeklyBuckets[3]
        val hardMs = weeklyBuckets[4] + weeklyBuckets[5]

        val easyPct = if (weeklyTotal > 0) easyMs.toFloat() / weeklyTotal else 0f
        val grayPct = if (weeklyTotal > 0) grayMs.toFloat() / weeklyTotal else 0f
        val hardPct = if (weeklyTotal > 0) hardMs.toFloat() / weeklyTotal else 0f

        val ridesWithHrCount = days.sumOf { day -> if (day.timeInZoneMs != null) day.rideCount else 0 }

        // Same Karvonen floor math as Karvonen card / TIZ card — kept here
        // (instead of calling the classifier) because we don't have an HR
        // sample, just the profile.
        val hrr = maxHrBpm - restingHrBpm
        val subRecoveryFloor = if (hrr > 0) {
            (restingHrBpm + 0.50f * hrr).roundToInt()
        } else restingHrBpm

        return WeekResult(
            weekStart = weekStart,
            weekEnd = weekEnd,
            days = days,
            weeklyTimeInZoneMs = weeklyBuckets,
            weeklyTotalMs = weeklyTotal,
            weeklyEasyPct = easyPct,
            weeklyGrayPct = grayPct,
            weeklyHardPct = hardPct,
            rideCount = ridesByDay.values.sumOf { it.size },
            ridesWithHrCount = ridesWithHrCount,
            subRecoveryFloorBpm = subRecoveryFloor,
        )
    }
}

/** Short display label for a [DayOfWeek] — Mon / Tue / Wed ... */
fun DayOfWeek.shortLabel(): String =
    getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
