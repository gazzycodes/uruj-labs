package com.uruj.data

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.uruj.util.rethrowCancellation
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
        }.rethrowCancellation()
            .onFailure { Log.w(TAG, "last-sleep read failed", it) }
            .getOrNull()
    }

    /**
     * v0.9.46 — Aggregated sleep duration across ALL sleep blocks within
     * the last 18 hours. Fixes 2026-05-25 bug where fragmented sleep
     * (multiple rollovers between 7 AM and 12 PM following a 7-hour main
     * block) appeared as 4.6h "severe deficit" in URUJ because [read]
     * returned only the LAST contiguous block. Samsung Health correctly
     * showed 9h 47m actual sleep. URUJ now matches.
     *
     * Semantic: "How much total sleep did the user get?"
     * vs [read]'s semantic: "Which window should I use for HRV/RHR math?"
     *
     * Two different questions, two different answers. This method is for
     * the user-facing SLEEP HOURS display + the SleepSnapshot total. HRV
     * window selection still uses [read] — that path needs a single clean
     * deep-sleep period for parasympathetic dominance, not the union of
     * all blocks including brief wakings.
     *
     * Overlap handling: intervals are merged before summing, so any
     * overlapping records (Samsung occasionally writes a "nap detected"
     * record overlapping the main sleep session) don't double-count.
     *
     * Window: 18 hours captures a night + morning rollovers without
     * pulling in yesterday's separate sleep block.
     *
     * Returns null when no sleep records found in window.
     */
    suspend fun readAggregated(
        client: HealthConnectClient,
        granted: Set<String>,
    ): Result? {
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted) return null
        val now = Instant.now()
        // v0.9.48.3 — widen window to 36h so we don't miss main-sleep blocks
        // when checking late in the day. The cluster algorithm below picks
        // the biological-night cluster regardless of clock-time position.
        val window = now.minus(Duration.ofHours(36))
        return runCatching {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(window, now),
                    ascendingOrder = true,
                ),
            )
            if (response.records.isEmpty()) return@runCatching null

            // Step 1: merge overlapping intervals (defensive — Samsung
            // sometimes writes nested session records, see v0.9.48.1 fix).
            val intervals = response.records
                .map { it.startTime to it.endTime }
                .sortedBy { it.first }
            val merged = mutableListOf<Pair<Instant, Instant>>()
            for ((start, end) in intervals) {
                if (merged.isEmpty() || merged.last().second.isBefore(start)) {
                    merged += start to end
                } else {
                    val (prevStart, prevEnd) = merged.last()
                    merged[merged.lastIndex] = prevStart to maxOf(prevEnd, end)
                }
            }

            // Step 2 (v0.9.48.3): cluster merged intervals into biological
            // nights. Adjacent intervals with gap < BIOLOGICAL_NIGHT_GAP_
            // THRESHOLD (4h) belong to the same cluster. Same approach
            // Whoop / Oura / Polar Flow / HRV4Training use for "last
            // night's sleep" detection — biological cluster, not clock
            // window. Fixes 2026-05-25 PM regression where main 7h sleep
            // dropped off the 18h rolling cutoff when the user checked
            // the app late in the day.
            val clusters = mutableListOf<MutableList<Pair<Instant, Instant>>>()
            for (interval in merged) {
                val lastCluster = clusters.lastOrNull()
                val lastEnd = lastCluster?.lastOrNull()?.second
                val gap = if (lastEnd != null) Duration.between(lastEnd, interval.first) else null
                if (gap == null || gap > BIOLOGICAL_NIGHT_GAP_THRESHOLD) {
                    clusters += mutableListOf(interval)
                } else {
                    lastCluster!!.add(interval)
                }
            }

            // Step 3: pick cluster with maximum total sleep duration. Tie-
            // breaker: most recent endTime (so naps don't displace main
            // sleep if equal length, which is statistically impossible
            // but defensive).
            val winnerPair = clusters
                .map { cluster ->
                    val total = cluster.sumOf {
                        Duration.between(it.first, it.second).toMillis()
                    }
                    cluster to total
                }
                .maxWithOrNull(
                    compareBy<Pair<List<Pair<Instant, Instant>>, Long>> { it.second }
                        .thenBy { it.first.last().second.toEpochMilli() },
                )
                ?: return@runCatching null

            val cluster = winnerPair.first
            val totalMs = winnerPair.second
            if (totalMs <= 0) return@runCatching null
            val earliestStart = cluster.first().first
            val latestEnd = cluster.last().second
            Log.d(
                TAG,
                "[v0.9.48.3] biological-night: ${clusters.size} clusters in last 36h, " +
                    "winner has ${cluster.size} blocks · " +
                    "total ${"%.1f".format(totalMs / 3_600_000f)}h " +
                    "(span $earliestStart → $latestEnd)",
            )
            Result(
                durationMs = totalMs,
                startedAt = earliestStart,
                endedAt = latestEnd,
            )
        }.rethrowCancellation()
            .onFailure { Log.w(TAG, "aggregated sleep read failed", it) }
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
        }.rethrowCancellation()
            .onFailure { Log.w(TAG, "list-last-N-days read failed", it) }
            .getOrDefault(emptyList())
    }

    /**
     * v0.9.48 — read sleep STAGE segments from HC for a given session.
     *
     * Returns the list of [SleepStageSegment] within the session window.
     * Empty list when:
     *   - HC has the session record but it has no stages (some firmware
     *     versions write only session boundaries, not detailed staging)
     *   - Permission missing
     *   - Session not found
     *
     * Caller should accept empty as a graceful degradation signal (fall
     * back to whole-window HRV — pre-v0.9.48 method, tagged accordingly).
     *
     * Stage mapping (HC constant → our string label):
     *   STAGE_TYPE_DEEP      = 5 → "deep"
     *   STAGE_TYPE_REM       = 6 → "rem"
     *   STAGE_TYPE_LIGHT     = 4 → "light"
     *   STAGE_TYPE_SLEEPING  = 2 → "asleep"  (generic asleep)
     *   STAGE_TYPE_AWAKE     = 1 → "awake"
     *   STAGE_TYPE_AWAKE_IN_BED = 7 → "awake"
     *   STAGE_TYPE_OUT_OF_BED   = 3 → "awake"
     *   STAGE_TYPE_UNKNOWN   = 0 → "unknown"
     */
    suspend fun readStagesForSession(
        client: HealthConnectClient,
        granted: Set<String>,
        sessionStart: Instant,
        sessionEnd: Instant,
    ): List<SleepStageSegment> {
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted) {
            return emptyList()
        }
        return runCatching {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(sessionStart, sessionEnd),
                    ascendingOrder = true,
                ),
            )
            if (response.records.isEmpty()) return@runCatching emptyList()
            val segments = mutableListOf<SleepStageSegment>()
            for (record in response.records) {
                for (stage in record.stages) {
                    val label = stageTypeToLabel(stage.stage)
                    segments += SleepStageSegment(
                        stageType = label,
                        startMs = stage.startTime.toEpochMilli(),
                        endMs = stage.endTime.toEpochMilli(),
                    )
                }
            }
            // Sort by start time. Overlapping segments preserved as-is
            // (rare; HC usually writes non-overlapping stages per session
            // but multiple sessions in the window can have adjacent stages).
            segments.sortBy { it.startMs }
            Log.d(
                TAG,
                "[v0.9.48] stages for session $sessionStart..$sessionEnd: " +
                    "${segments.size} segments (" +
                    segments.groupBy { it.stageType }
                        .mapValues { (_, list) ->
                            list.sumOf { it.endMs - it.startMs } / 60_000L
                        }
                        .entries.joinToString(", ") { "${it.key}=${it.value}m" } +
                    ")",
            )
            segments
        }.rethrowCancellation()
            .onFailure { Log.w(TAG, "stages read failed", it) }
            .getOrDefault(emptyList())
    }

    /**
     * v0.9.48 — convenience method: read stages for the result returned
     * by [read] or [readAggregated]. Spans the full result's window so
     * fragmented sleep nights get all stages across all blocks.
     */
    suspend fun readStagesForResult(
        client: HealthConnectClient,
        granted: Set<String>,
        result: Result,
    ): List<SleepStageSegment> = readStagesForSession(
        client = client,
        granted = granted,
        sessionStart = result.startedAt,
        sessionEnd = result.endedAt,
    )

    /**
     * Map HC SleepSessionRecord.Stage type int → string label used in our
     * disk schema. Constants per HC documentation; abstracted so future
     * HC API changes don't break our snapshots.
     */
    private fun stageTypeToLabel(stage: Int): String = when (stage) {
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "rem"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "asleep"
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "awake"
        SleepSessionRecord.STAGE_TYPE_UNKNOWN -> "unknown"
        else -> "unknown"
    }

    companion object {
        private const val TAG = "URUJ-LastSleep"

        /**
         * v0.9.48.3 — Max gap between adjacent sleep blocks for them to be
         * considered part of the SAME biological night. 4h is the standard
         * the consumer sleep-tracking industry converged on (Whoop, Oura,
         * Polar Flow, HRV4Training). Captures all reasonable fragmented-
         * sleep + brief-rollover patterns without merging a daytime nap
         * into the previous night.
         */
        private val BIOLOGICAL_NIGHT_GAP_THRESHOLD: Duration = Duration.ofHours(4)
    }
}
