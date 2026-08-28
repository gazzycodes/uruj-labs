package com.uruj.data

import android.content.Context
import android.util.Log
import com.uruj.domain.SensorSource
import com.uruj.util.rethrowCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * v0.9.1 — disk-persisted daily Athletic RHR (sleep-window resting HR).
 *
 * ## Why
 *
 * Same data-loss reason as HRR1 (see HrrSnapshotRepository): Health Connect
 * only retains ~30 days of HR + sleep windows. Without snapshotting, the
 * rider's nightly RHR history disappears at 30d, capping any RHR trend chart
 * at that horizon. RHR is one of the most important long-term recovery
 * signals (declining RHR = adaptation; rising RHR = accumulated fatigue or
 * illness coming on). The trend across months matters more than the last day.
 *
 * ## How
 *
 * One file per calendar day at `/files/snapshots/rhr/YYYY-MM-DD.json`. The
 * "for-day" key is the date the RHR was COMPUTED (BioLab compute day), not
 * the night-end date — there's a 1:1 correspondence for the user's typical
 * usage (open BioLab in the morning, captures last night's RHR for today).
 *
 * v0.9.1 stores the DAILY AGGREGATE: today's effective RHR from
 * SleepingRhrCalculator's Result (the rolling median across recent nights +
 * the most-recent-night's actual reading + source).
 *
 * v0.9.x+ may refactor to per-NIGHT snapshots once SleepingRhrCalculator
 * exposes its perNight list. Until then, daily aggregate captures the
 * evolution of effective RHR over time, which is what the trend chart wants
 * to display anyway.
 *
 * ## File schema
 *
 * ```json
 * {
 *   "dateIsoLocal": "2026-05-19",
 *   "medianBpm": 44,                         // median of recent nights
 *   "mostRecentNightBpm": 42,                // last night's actual reading
 *   "mostRecentNightEndMs": 1779100000000,
 *   "mostRecentNightSource": "STRAP",        // SensorSource enum name
 *   "nightsContributing": 7,                 // n of nights feeding median
 *   "methodologyVersion": "v0.7.7-sleep-window-min-with-glitch-filter",
 *   "computedAtMs": 1779120000000
 * }
 * ```
 *
 * ## API parity with HrrSnapshotRepository
 *
 * Same shape: `save()`, `load()`, `listAll()`, `count()`. Idempotent save —
 * already-saved today's file is left alone so the original methodology
 * version stays tagged with the original compute.
 *
 * @see [[reference_snapshot_persistence_architecture]]
 */
class RhrSnapshotRepository(context: Context) {

    private val appContext = context.applicationContext
    private val baseDir: File
        get() = File(appContext.getExternalFilesDir(null), "snapshots/rhr").apply {
            if (!exists()) mkdirs()
        }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Persist today's RHR snapshot. Idempotent: skipped silently if today's
     * file already exists. Returns true on a new write, false on skip.
     *
     * `date` parameter accepts a local-date override for testing; default is
     * the device's current local date.
     */
    suspend fun save(snapshot: RhrSnapshot, date: LocalDate = LocalDate.now()): Boolean =
        withContext(Dispatchers.IO) {
            val file = File(baseDir, "${date}.json")
            // v0.9.7 — today's snapshot is MUTABLE during the day. RHR median
            // can refine intra-day if Samsung syncs new sleep windows or HC
            // HR samples. Past dates immutable for trend chart integrity.
            val today = LocalDate.now()
            if (date != today && file.exists()) {
                Log.d(TAG, "skipping save: $date is historical (immutable)")
                return@withContext false
            }
            runCatching {
                file.writeText(json.encodeToString(RhrSnapshot.serializer(), snapshot))
                Log.d(TAG, "saved RHR snapshot $date: median ${snapshot.medianBpm}bpm")
                true
            }.getOrElse { e ->
                Log.w(TAG, "failed to save RHR snapshot $date", e)
                false
            }
        }

    /** Read a specific day's snapshot. Null when not on disk. */
    suspend fun load(date: LocalDate): RhrSnapshot? = withContext(Dispatchers.IO) {
        val file = File(baseDir, "$date.json")
        if (!file.exists()) return@withContext null
        runCatching {
            json.decodeFromString(RhrSnapshot.serializer(), file.readText())
        }.getOrElse { e ->
            Log.w(TAG, "corrupt RHR snapshot $date — skipping", e)
            null
        }
    }

    /** Read ALL daily snapshots, sorted by date descending (most recent first). */
    suspend fun listAll(): List<RhrSnapshot> = withContext(Dispatchers.IO) {
        val files = baseDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?: return@withContext emptyList()
        files.mapNotNull { file ->
            runCatching {
                json.decodeFromString(RhrSnapshot.serializer(), file.readText())
            }.getOrElse { e ->
                Log.w(TAG, "corrupt RHR snapshot ${file.name} — skipping", e)
                null
            }
        }.sortedByDescending { it.dateIsoLocal }
    }

    /** Count files on disk. Cheap (no read). */
    suspend fun count(): Int = withContext(Dispatchers.IO) {
        baseDir.listFiles { f -> f.isFile && f.extension == "json" }?.size ?: 0
    }

    /**
     * v0.9.11 — one-time backfill from HC retention window.
     *
     * Iterates over historical [com.uruj.data.SleepSnapshot] entries (already
     * persisted by v0.9.9's sleep backfill or forward-saves). For each past
     * night with a sleep snapshot AND HC HR samples within the sleep window,
     * runs [com.uruj.power.SleepingRhrCalculator] on that single night's
     * data and saves the resulting single-night RHR as a [RhrSnapshot].
     *
     * Semantic note: forward-saved entries (from BioLab compute) have
     * `medianBpm` = rolling 8-night median across nights. Backfilled entries
     * have `medianBpm` = single-night value with `nightsContributing = 1`
     * + methodology tag "v0.9.11-backfill-single-night-from-hc". Trend chart
     * mixes both — the methodology tag in the readings list distinguishes.
     * Future improvement: re-compute rolling median for backfilled days where
     * 8 days of HC data is still available.
     *
     * - One HC HR samples read for the entire retention window
     * - Buckets per night via sleep snapshot window endpoints
     * - Past-date immutability rejects overwrites — idempotent re-run
     * - Gated by [HcReadGuard.isPostRideQuietWindow]
     */
    suspend fun backfillFromHc(
        client: androidx.health.connect.client.HealthConnectClient,
        granted: Set<String>,
        sleepSnapshots: SleepSnapshotRepository,
        sleepingRhrCalc: com.uruj.power.SleepingRhrCalculator,
        days: Int = 30,
    ): Int = withContext(Dispatchers.IO) {
        if (HcReadGuard.isPostRideQuietWindow()) {
            Log.d(TAG, "[v0.9.11] backfill skipped — post-ride quiet window")
            return@withContext 0
        }
        val sleeps = sleepSnapshots.listAll()
        if (sleeps.isEmpty()) {
            Log.d(TAG, "[v0.9.11] backfill: no sleep snapshots — nothing to do")
            return@withContext 0
        }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val hrPerm = androidx.health.connect.client.permission.HealthPermission.getReadPermission(
            androidx.health.connect.client.records.HeartRateRecord::class,
        )
        if (hrPerm !in granted) {
            Log.d(TAG, "[v0.9.11] backfill skipped — HR perm missing")
            return@withContext 0
        }
        HcReadGuard.recordRead("backfill.rhr")
        val now = java.time.Instant.now()
        val from = now.minus(java.time.Duration.ofDays(days.toLong()))
        val hrSamples = runCatching {
            client.readRecords(
                androidx.health.connect.client.request.ReadRecordsRequest(
                    recordType = androidx.health.connect.client.records.HeartRateRecord::class,
                    timeRangeFilter = androidx.health.connect.client.time.TimeRangeFilter.between(from, now),
                    ascendingOrder = false,
                    pageSize = 5_000,
                ),
            ).records.flatMap { it.samples }.map { it.time to it.beatsPerMinute.toInt() }
        }.rethrowCancellation()
            .onFailure { Log.w(TAG, "[v0.9.11] HR samples read failed", it) }
            .getOrDefault(emptyList())
        if (hrSamples.isEmpty()) {
            Log.d(TAG, "[v0.9.11] backfill: no HC HR samples in window")
            return@withContext 0
        }
        var written = 0
        for (sleep in sleeps) {
            val date = runCatching { LocalDate.parse(sleep.dateIsoLocal) }.getOrNull() ?: continue
            if (date == today) continue  // forward-save path owns today
            if (load(date) != null) continue  // already backfilled
            val sleepStart = java.time.Instant.ofEpochMilli(sleep.sessionStartMs)
            val sleepEnd = java.time.Instant.ofEpochMilli(sleep.sessionEndMs)
            val nightSamples = hrSamples.filter { (t, _) -> t in sleepStart..sleepEnd }
            if (nightSamples.size < 30) continue  // not enough data for a reliable median
            val sleepingResult = sleepingRhrCalc.compute(
                hcSamples = nightSamples,
                sleepWindows = listOf(sleepStart to sleepEnd),
                strapSamples = emptyList(),  // historical: no strap NDJSON for back-dates
            ) ?: continue
            val saved = save(
                RhrSnapshot(
                    dateIsoLocal = date.toString(),
                    medianBpm = sleepingResult.mostRecentNightBpm,
                    mostRecentNightBpm = sleepingResult.mostRecentNightBpm,
                    mostRecentNightEndMs = sleep.sessionEndMs,
                    mostRecentNightSource = SensorSource.BAND.name,  // historical = HC band
                    nightsContributing = 1,
                    methodologyVersion = "v0.9.11-backfill-single-night-from-hc",
                    computedAtMs = System.currentTimeMillis(),
                ),
                date = date,
            )
            if (saved) written++
        }
        Log.d(TAG, "[v0.9.11] backfill: $written new RHR snapshots from HC (${sleeps.size} sleep nights available)")
        written
    }

    companion object {
        private const val TAG = "URUJ-RhrSnap"

        /**
         * Current RHR methodology version. Persisted with every snapshot so a
         * future calculator change (e.g. switch from min-of-night to median-of-
         * lowest-quartile) can be tracked without retroactively rewriting history.
         */
        const val METHODOLOGY_VERSION = "v0.9.83-sleep-window-sustained-5min"
    }
}

/**
 * v0.9.1 — disk schema for one day's Athletic RHR reading.
 */
@Serializable
data class RhrSnapshot(
    /** ISO-8601 local date the BioLab compute ran (`YYYY-MM-DD`). */
    val dateIsoLocal: String,
    /** Median bpm across recent sleep nights (the value displayed on the
     *  BioLab HR card "ATHLETIC RHR" row). */
    val medianBpm: Int,
    /** Actual reading for the most recent sleep night feeding the median. */
    val mostRecentNightBpm: Int,
    /** When the most recent night ended (epoch ms). */
    val mostRecentNightEndMs: Long,
    /** Source for the most recent night's reading (STRAP / BAND / MIXED). */
    val mostRecentNightSource: String,
    /** Number of nights that contributed to today's median. */
    val nightsContributing: Int,
    /** v0.9.82 — which population the median was taken over: "STRAP" when the
     *  median came from strap nights alone (trend-safe), "MIXED" when band
     *  nights had to be included. The wrist band reads systematically higher
     *  than the strap (+7.5 bpm measured on this athlete), so a MIXED median
     *  moves with device choice, not physiology, and must not be trended or
     *  fed to VO2 without that caveat. Null = pre-v0.9.82 snapshot, provenance
     *  unknown — treat as MIXED. */
    val medianSource: String? = null,
    // v0.9.49.1 — default "legacy" per [[reference_lab_grade_architecture_rules]]
    // Rule 2 so pre-versioning snapshots deserialize gracefully + future
    // methodology bumps invalidate via mismatch instead of accidental match.
    val methodologyVersion: String = "legacy",
    val computedAtMs: Long,
    /** v0.9.83 — WHICH STATISTIC produced these numbers. "sustained-5min" from
     *  v0.9.83 on; null means a pre-v0.9.83 record, which held the single lowest
     *  BEAT of the night and reads 6-8 bpm LOW. **Two records with different
     *  statistics are different quantities — never plot them on one axis or
     *  pool them into one median without saying so.** */
    val statistic: String? = null,
    /** v0.9.83 — the OLD single-lowest-beat value for the most recent night, so
     *  the pre-v0.9.83 series can be reconciled against the new one instead of
     *  showing a phantom +6-8 bpm step on the day the fix shipped. */
    val singleMinBpm: Int? = null,
    /** v0.9.83 — fraction of the sleep window that actually had usable samples.
     *  Below ~0.5 the value is real but must not be trended: a low-coverage night
     *  samples a different part of the night, and early-night SWS runs several
     *  bpm below morning REM. */
    val coverage: Float? = null,
    /** v0.9.83 — nights rejected by the plausibility gate (median sleep HR above
     *  100 bpm = corrupt data, not a bad night). Non-zero means the athlete has
     *  corrupt sensor days and the sample behind this median silently shrank. */
    val rejectedNights: Int = 0,
) {
    val mostRecentNightSourceEnum: SensorSource
        get() = runCatching { SensorSource.valueOf(mostRecentNightSource) }
            .getOrDefault(SensorSource.UNKNOWN_LEGACY)
}
