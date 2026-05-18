package com.uruj.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * v0.9.1 — disk-persisted daily Training Stress Balance + the CTL/ATL pair
 * that produces it.
 *
 * ## Why
 *
 * TSB = CTL − ATL is the cornerstone training-readiness signal (Coggan
 * methodology). It tracks the rider's balance between accumulated fitness
 * (CTL = 42d EWMA of TSS) and recent fatigue (ATL = 7d EWMA of TSS).
 *
 * The signal lives over MONTHS, not days. A 6-month TSB curve reveals:
 *   - Successful taper into a race (TSB rises into the +5 to +20 zone)
 *   - Productive overload blocks (TSB dips below -20 then recovers)
 *   - Chronic over-training (TSB stuck below -20 for weeks)
 *
 * Without disk persistence, TSB can only display TODAY's value computed
 * from the last 42 days of data. The 6-month curve URUJ wants to show
 * doesn't exist anywhere — Whoop/Garmin/Polar all charge subscriptions
 * for it. v0.9.1 owns the daily values forever on URUJ's disk.
 *
 * Different ownership pattern from RHR/VO2: TSB is derived purely from
 * RIDE HISTORY (URUJ's local NDJSON-summary sidecars + Samsung exercise
 * sessions in HC). URUJ ride history is permanent; only the Samsung
 * portion is HC-limited. But by snapshotting the COMPUTED daily TSB, we
 * lock in the "as-known-on-this-date" value, including whatever Samsung
 * data was present at that compute time.
 *
 * ## File schema
 *
 * ```json
 * {
 *   "dateIsoLocal": "2026-05-19",
 *   "tsb": -36.4,                  // CTL - ATL (the headline)
 *   "ctl": 48.2,                   // 42d EWMA of TSS — fitness
 *   "atl": 84.6,                   // 7d EWMA of TSS — fatigue
 *   "totalLoad42d": 1842.5,        // sum of all TSS over 42d window
 *   "methodologyVersion": "v0.4.3-coggan-ewma-multisport-hrtss",
 *   "computedAtMs": 1779120000000
 * }
 * ```
 *
 * ## Architecture parity with HrrSnapshotRepository
 *
 * Same shape: `save()`, `load(date)`, `listAll()`, `count()`. Idempotent
 * saves preserve original methodology version per snapshot.
 *
 * @see [[reference_snapshot_persistence_architecture]]
 */
class TsbSnapshotRepository(context: Context) {

    private val appContext = context.applicationContext
    private val baseDir: File
        get() = File(appContext.getExternalFilesDir(null), "snapshots/tsb").apply {
            if (!exists()) mkdirs()
        }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun save(snapshot: TsbSnapshot, date: LocalDate = LocalDate.now()): Boolean =
        withContext(Dispatchers.IO) {
            val file = File(baseDir, "${date}.json")
            if (file.exists()) {
                Log.d(TAG, "skipping save: $date already on disk")
                return@withContext false
            }
            runCatching {
                file.writeText(json.encodeToString(TsbSnapshot.serializer(), snapshot))
                Log.d(
                    TAG,
                    "saved TSB snapshot $date: tsb=${"%.1f".format(snapshot.tsb)} " +
                        "ctl=${"%.1f".format(snapshot.ctl)} atl=${"%.1f".format(snapshot.atl)}",
                )
                true
            }.getOrElse { e ->
                Log.w(TAG, "failed to save TSB snapshot $date", e)
                false
            }
        }

    suspend fun load(date: LocalDate): TsbSnapshot? = withContext(Dispatchers.IO) {
        val file = File(baseDir, "$date.json")
        if (!file.exists()) return@withContext null
        runCatching {
            json.decodeFromString(TsbSnapshot.serializer(), file.readText())
        }.getOrElse { e ->
            Log.w(TAG, "corrupt TSB snapshot $date — skipping", e)
            null
        }
    }

    suspend fun listAll(): List<TsbSnapshot> = withContext(Dispatchers.IO) {
        val files = baseDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?: return@withContext emptyList()
        files.mapNotNull { file ->
            runCatching {
                json.decodeFromString(TsbSnapshot.serializer(), file.readText())
            }.getOrElse { e ->
                Log.w(TAG, "corrupt TSB snapshot ${file.name} — skipping", e)
                null
            }
        }.sortedByDescending { it.dateIsoLocal }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        baseDir.listFiles { f -> f.isFile && f.extension == "json" }?.size ?: 0
    }

    companion object {
        private const val TAG = "URUJ-TsbSnap"

        /** Bumped when TSB calc changes. v0.4.3 was when multi-sport hrTSS
         *  was added. Stays "v0.4.3" until methodology bumps again. */
        const val METHODOLOGY_VERSION = "v0.4.3-coggan-ewma-multisport-hrtss"
    }
}

@Serializable
data class TsbSnapshot(
    val dateIsoLocal: String,
    /** CTL − ATL. The headline number cyclists watch. */
    val tsb: Float,
    /** Chronic Training Load — 42d EWMA of TSS. Proxy for current fitness. */
    val ctl: Float,
    /** Acute Training Load — 7d EWMA of TSS. Proxy for recent fatigue. */
    val atl: Float,
    /** Sum of all TSS contributions across the 42-day window. */
    val totalLoad42d: Float,
    val methodologyVersion: String,
    val computedAtMs: Long,
)
