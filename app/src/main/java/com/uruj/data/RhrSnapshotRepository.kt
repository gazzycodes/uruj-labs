package com.uruj.data

import android.content.Context
import android.util.Log
import com.uruj.domain.SensorSource
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

    companion object {
        private const val TAG = "URUJ-RhrSnap"

        /**
         * Current RHR methodology version. Persisted with every snapshot so a
         * future calculator change (e.g. switch from min-of-night to median-of-
         * lowest-quartile) can be tracked without retroactively rewriting history.
         */
        const val METHODOLOGY_VERSION = "v0.7.7-sleep-window-min-with-glitch-filter"
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
    val methodologyVersion: String,
    val computedAtMs: Long,
) {
    val mostRecentNightSourceEnum: SensorSource
        get() = runCatching { SensorSource.valueOf(mostRecentNightSource) }
            .getOrDefault(SensorSource.UNKNOWN_LEGACY)
}
