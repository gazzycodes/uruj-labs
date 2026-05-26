package com.uruj.data

import android.content.Context
import android.util.Log
import com.uruj.domain.SensorSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * v0.9.0 — disk-persisted HRR1 readings. The first concrete implementation of
 * the snapshot-persistence architecture (see memory file
 * `reference_snapshot_persistence_architecture.md`).
 *
 * ## Why
 *
 * Health Connect retains ~30 days of HR + ExerciseSession records. URUJ's
 * pre-v0.9.0 HRR1 trend recomputed from HC each open → samples older than
 * 30d were lost forever. A rider doing weekly tempo rides loses their
 * trend data exactly when the trend is most interesting to see.
 *
 * ## How
 *
 * At BioLabRepository's `snapshot()` time, after `HrRecoveryCalculator`
 * produces qualifying readings, each newly-computed reading is persisted
 * here as JSON in `/snapshots/hrr1/<RIDE-ID>.json`. Idempotent: if a file
 * already exists for that sessionId, skip (the existing snapshot wins —
 * methodology might have changed since the original write but we don't
 * retroactively rewrite history).
 *
 * The HRR1 trend screen reads from THIS repository (disk, unlimited
 * history) combined with the current 30d HC-derived readings (live, may
 * include samples not yet snapshotted). Result: full historical depth
 * regardless of HC's retention window.
 *
 * ## File layout
 *
 * ```
 * /files/snapshots/hrr1/
 *   <RIDE-ID>.json              # URUJ ride session
 *   samsung-<EPOCH-MS>.json     # Samsung-tracked exercise session (no URUJ ride ID)
 * ```
 *
 * ## Methodology version
 *
 * Every snapshot carries `methodologyVersion` so a future calc improvement
 * doesn't silently invalidate the prior values. Old snapshots stay tagged
 * with the version that produced them; new computations get the current
 * version. Trend UI could show a small indicator when versions span (e.g.
 * "12 readings (v0.7.7 methodology), 3 (v0.9.5 methodology)") — deferred
 * for now since we only have one HRR1 methodology version in production.
 *
 * @see [[reference_snapshot_persistence_architecture]]
 */
class HrrSnapshotRepository(context: Context) {

    private val appContext = context.applicationContext
    private val baseDir: File
        get() = File(appContext.getExternalFilesDir(null), "snapshots/hrr1").apply {
            if (!exists()) mkdirs()
        }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Persist a single HRR1 reading. Idempotent — silently skips when a
     * snapshot already exists for `sessionId`. Returns true when a new
     * file was written; false when a snapshot already existed (so callers
     * can log new vs. existing counts during batch saves).
     */
    suspend fun save(snapshot: HrrSnapshot): Boolean = withContext(Dispatchers.IO) {
        val file = File(baseDir, "${snapshot.sessionId}.json")
        if (file.exists()) {
            Log.d(TAG, "skipping save: ${snapshot.sessionId} already on disk")
            return@withContext false
        }
        runCatching {
            file.writeText(json.encodeToString(HrrSnapshot.serializer(), snapshot))
            Log.d(TAG, "saved HRR1 snapshot ${snapshot.sessionId}: ${snapshot.hrr1Bpm}bpm (${snapshot.source})")
            true
        }.getOrElse { e ->
            Log.w(TAG, "failed to save HRR1 snapshot ${snapshot.sessionId}", e)
            false
        }
    }

    /** Read a specific snapshot by sessionId. Null when not on disk. */
    suspend fun load(sessionId: String): HrrSnapshot? = withContext(Dispatchers.IO) {
        val file = File(baseDir, "$sessionId.json")
        if (!file.exists()) return@withContext null
        runCatching {
            json.decodeFromString(HrrSnapshot.serializer(), file.readText())
        }.getOrElse { e ->
            Log.w(TAG, "corrupt HRR1 snapshot $sessionId — skipping", e)
            null
        }
    }

    /**
     * Read ALL snapshots in the directory, sorted by sessionEndMs descending
     * (most recent first). Malformed files are skipped with a warning, not
     * fatal. Caller decides how much history to show via TrendShell's range
     * filter (30d / 90d / 1y / All).
     */
    suspend fun listAll(): List<HrrSnapshot> = withContext(Dispatchers.IO) {
        val files = baseDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?: return@withContext emptyList()
        val snapshots = files.mapNotNull { file ->
            runCatching {
                json.decodeFromString(HrrSnapshot.serializer(), file.readText())
            }.getOrElse { e ->
                Log.w(TAG, "corrupt HRR1 snapshot ${file.name} — skipping", e)
                null
            }
        }
        snapshots.sortedByDescending { it.sessionEndMs }
    }

    /** Total snapshots on disk. Cheap (directory listing, no read). */
    suspend fun count(): Int = withContext(Dispatchers.IO) {
        baseDir.listFiles { f -> f.isFile && f.extension == "json" }?.size ?: 0
    }

    companion object {
        private const val TAG = "URUJ-HrrSnap"

        /**
         * Current HRR1 methodology version tag, persisted with every new
         * snapshot. Bump when the calc changes (e.g. v0.7.9's "closest-to-60s
         * sample selection" change vs v0.2.8's "min of 10-min window").
         * Lets future-Claude trace old vs new readings.
         */
        const val METHODOLOGY_VERSION = "v0.7.9-cole-closest-to-60s"
    }
}

/**
 * v0.9.0 — disk schema for one persisted HRR1 reading.
 *
 * Carries all fields the trend chart + BioLab card need to render +
 * classify the reading without re-querying HC. Source label is the same
 * SensorSource enum the rest of the app uses (STRAP / BAND / MIXED /
 * UNKNOWN_LEGACY), serialized as enum name for forward-compat.
 */
@Serializable
data class HrrSnapshot(
    /** URUJ ride sessionId, OR `samsung-<EPOCH-MS>` for Samsung-tracked sessions. */
    val sessionId: String,
    /** Session end time in epoch ms — what TrendChart sorts by. */
    val sessionEndMs: Long,
    /** Pre-recovery peak HR (used to filter low-effort sessions). */
    val peakBpm: Int,
    /** The HRR1 reading itself: peak − HR at 60s post-effort. */
    val hrr1Bpm: Int,
    /** Cole NEJM 1999 classification: "Excellent" / "Average" / "Elevated CV risk". */
    val classification: String,
    /** Which sensor produced this reading, persisted as SensorSource enum NAME. */
    val source: String,
    /**
     * Methodology version tag — see `HrrSnapshotRepository.METHODOLOGY_VERSION`.
     *
     * v0.9.49.1 — default "legacy" per
     * [[reference_lab_grade_architecture_rules]] Rule 2 so pre-v0.7.7
     * snapshots (which don't carry this field) deserialize gracefully
     * instead of failing as "corrupt." When HRR1 math changes in a future
     * PR, bump [HrrSnapshotRepository.METHODOLOGY_VERSION] + add an
     * `ensureBackfilled()` to migrate snapshots (NDJSON+HC permitting).
     */
    val methodologyVersion: String = "legacy",
    /** When this snapshot was written to disk. */
    val computedAtMs: Long,
) {
    /** Deserialize source string back to enum. Defaults to UNKNOWN_LEGACY for
     *  forward-compatibility if future versions add new enum values. */
    val sourceEnum: SensorSource
        get() = runCatching { SensorSource.valueOf(source) }
            .getOrDefault(SensorSource.UNKNOWN_LEGACY)
}
