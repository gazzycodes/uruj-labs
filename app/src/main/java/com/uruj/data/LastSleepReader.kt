package com.uruj.data

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant

/**
 * Single source of truth for "last sleep" across the app.
 *
 * Pre-v0.3.7 bug: ReadinessRepository.readLastNightSleepHours used a 20h window
 * AND summed all sleep sessions inside it; BioLabRepository.readLastNightSleep
 * used a 24h window AND summed too. Result: night-shift users with multiple
 * sleep blocks saw DIFFERENT sleep totals on the readiness card vs the Bio Lab
 * card (5.3h vs 9.2h on the user's 2026-05-14 data).
 *
 * Fix: ONE reader. Returns the MOST RECENT sleep session block (not a sum).
 * Matches Samsung Health's "last sleep" semantic — when the user asks "how
 * did I sleep last night?" they mean their most recent main sleep block, not
 * the sum of every nap in the last day. Both screens now report the same value.
 *
 * Window: 36 hours. Wide enough to catch night-shift gaps where the most
 * recent sleep ended >24h ago. If somehow no sleep in 36h, returns null.
 *
 * Tie-breaking: when multiple sleep sessions ended at the same time (rare),
 * the longest wins.
 */
class LastSleepReader {

    data class Result(
        /** Duration of the most recent sleep session, in milliseconds. */
        val durationMs: Long,
        /** When that session ended (UTC). */
        val endedAt: Instant,
        /** When that session started (UTC). */
        val startedAt: Instant,
    ) {
        val hours: Float get() = durationMs / 3_600_000f
    }

    suspend fun read(
        client: HealthConnectClient,
        granted: Set<String>,
    ): Result? {
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted) return null
        val now = Instant.now()
        val window = now.minus(Duration.ofHours(36))
        return runCatching {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(window, now),
                    ascendingOrder = false,
                ),
            )
            if (response.records.isEmpty()) return@runCatching null
            // Most recent by endTime; longest if tied.
            val mostRecent = response.records.maxWithOrNull(
                compareBy<SleepSessionRecord> { it.endTime }
                    .thenBy { Duration.between(it.startTime, it.endTime).toMillis() },
            )
            mostRecent?.let {
                val ms = Duration.between(it.startTime, it.endTime).toMillis()
                if (ms <= 0) null else Result(
                    durationMs = ms,
                    endedAt = it.endTime,
                    startedAt = it.startTime,
                )
            }
        }.onFailure { Log.w(TAG, "last-sleep read failed", it) }
            .getOrNull()
    }

    /**
     * v0.7.4 follow-up — list the last N days of sleep sessions, one per day
     * (the longest session ending on each calendar date). Used by the HRV
     * trend chart so per-day RMSSD is sliced from the SAME sleep window that
     * the Bio Lab Autonomic card uses, instead of a hardcoded 22:00-09:00
     * heuristic.
     *
     * Returns sessions newest-first. Days with no sleep recorded are simply
     * omitted from the list.
     *
     * Tie-breaking within a date: longest session wins (same convention as
     * single-`read` path).
     */
    suspend fun listLastNDays(
        client: HealthConnectClient,
        granted: Set<String>,
        days: Int,
    ): List<Result> {
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted) return emptyList()
        val now = Instant.now()
        val window = now.minus(Duration.ofDays(days.toLong() + 1))
        return runCatching {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(window, now),
                    ascendingOrder = false,
                ),
            )
            // Group by the LocalDate of the session's endTime (when the user
            // woke up) — that's the date this sleep "belongs to" in URUJ's
            // calendar convention.
            val zone = java.time.ZoneId.systemDefault()
            response.records
                .groupBy { it.endTime.atZone(zone).toLocalDate() }
                .mapNotNull { (_, sessions) ->
                    val longest = sessions.maxByOrNull {
                        Duration.between(it.startTime, it.endTime).toMillis()
                    } ?: return@mapNotNull null
                    val ms = Duration.between(longest.startTime, longest.endTime).toMillis()
                    if (ms <= 0) null else Result(
                        durationMs = ms,
                        endedAt = longest.endTime,
                        startedAt = longest.startTime,
                    )
                }
                .sortedByDescending { it.endedAt }
        }.onFailure { Log.w(TAG, "list-last-N-days read failed", it) }
            .getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "URUJ-LastSleep"
    }
}
