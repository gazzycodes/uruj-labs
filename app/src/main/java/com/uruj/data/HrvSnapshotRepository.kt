package com.uruj.data

import android.content.Context
import android.util.Log
import com.uruj.util.rethrowCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * v0.9.27 — disk-persisted daily HRV snapshots (time-domain + frequency-
 * domain + non-linear, all in one record per overnight window).
 *
 * Mirrors [RhrSnapshotRepository] pattern + per
 * [[reference_snapshot_persistence_architecture]] BULLETPROOF RULE: every
 * trend metric persists to disk at compute time. Trend charts read disk
 * only. HC's 30-day retention can never cap the deep-view history.
 *
 * v0.9.0 family parity:
 *   - HRR1 → [HrrSnapshotRepository]
 *   - RHR → [RhrSnapshotRepository]
 *   - VO2 → [Vo2SnapshotRepository]
 *   - TSB → [TsbSnapshotRepository]
 *   - Sleep → [SleepSnapshotRepository]
 *   - **HRV (NEW)** → this file
 *
 * File location: `/files/snapshots/hrv/YYYY-MM-DD.json` — one per night.
 *
 * Today-mutable / past-immutable rule: today's snapshot can be
 * overwritten as the rider keeps the strap on through morning hours and
 * the overnight window extends. Past dates are immutable — once a night
 * is locked in, never re-compute (preserves methodology version tagging
 * for forward calc-change traceability).
 */
@Serializable
data class HrvSnapshot(
    /** ISO local date of the night (e.g. wake-up date). */
    val dateIsoLocal: String,
    // ── Time-domain (v0.7.0 layer)
    val rmssdMs: Float?,
    val sdnnMs: Float?,
    val pnn50Percent: Float?,
    val pnn20Percent: Float?,
    val meanHrBpm: Float?,
    val sampleCount: Int,
    val windowCount: Int,
    /** "strap" / "band" / "mixed" / "legacy" / "hc-direct" — provenance. */
    val source: String,
    // ── Frequency-domain (v0.9.25+, properly windowed v0.9.27+)
    val vlfMs2: Float? = null,
    val lfMs2: Float? = null,
    val hfMs2: Float? = null,
    val totalPowerMs2: Float? = null,
    val lfHfRatio: Float? = null,
    // ── Non-linear (v0.9.25+)
    val sd1Ms: Float? = null,
    val sd2Ms: Float? = null,
    val dfaAlpha1: Float? = null,
    val sampleEntropy: Float? = null,
    // ── Per-stage breakdown (v0.9.48+)
    /** RMSSD computed only on windows dominated by deep sleep (SWS). Null
     *  when <15 min of deep sleep available (Plews convention). */
    val deepRmssdMs: Float? = null,
    /** RMSSD on REM-dominant windows. Null when insufficient. */
    val remRmssdMs: Float? = null,
    /** RMSSD on light-sleep-dominant windows. Null when insufficient. */
    val lightRmssdMs: Float? = null,
    /** Was AWAKE-period filtering applied? Tags whether this is a Tier 1
     *  lab-grade reading (true) or pre-v0.9.48 whole-window fallback. */
    val stageFiltered: Boolean = false,
    /** Total awake minutes excluded from HRV math. Helps the user see
     *  why the new number differs from the pre-v0.9.48 value. */
    val awakeMinutesExcluded: Int? = null,
    // ── Provenance
    val computedAtMs: Long,
    val methodologyVersion: String,
)

class HrvSnapshotRepository(context: Context) {

    private val baseDir = File(context.applicationContext.filesDir, "snapshots/hrv")

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * Save a snapshot for the given date. Today-mutable, past-immutable.
     * Returns true if written, false if a past-date overwrite was blocked.
     */
    suspend fun save(snapshot: HrvSnapshot, date: LocalDate): Boolean = withContext(Dispatchers.IO) {
        val today = LocalDate.now(ZoneId.systemDefault())
        val file = File(baseDir, "${snapshot.dateIsoLocal}.json")
        if (date != today && file.exists()) {
            Log.d(TAG, "skipping save: ${snapshot.dateIsoLocal} is historical (immutable)")
            return@withContext false
        }
        runCatching {
            file.writeText(json.encodeToString(HrvSnapshot.serializer(), snapshot))
            Log.d(
                TAG,
                "saved HRV snapshot ${snapshot.dateIsoLocal}: " +
                    "rmssd=${snapshot.rmssdMs?.let { "%.1f".format(it) } ?: "—"} ms · " +
                    "lf/hf=${snapshot.lfHfRatio?.let { "%.2f".format(it) } ?: "—"} · " +
                    "dfa=${snapshot.dfaAlpha1?.let { "%.2f".format(it) } ?: "—"} · " +
                    "sd1=${snapshot.sd1Ms?.let { "%.1f".format(it) } ?: "—"} ms · " +
                    "windows=${snapshot.windowCount}",
            )
            true
        }.rethrowCancellation()
            .getOrElse {
                Log.w(TAG, "save failed for ${snapshot.dateIsoLocal}", it)
                false
            }
    }

    suspend fun load(dateIsoLocal: String): HrvSnapshot? = withContext(Dispatchers.IO) {
        val file = File(baseDir, "$dateIsoLocal.json")
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString(HrvSnapshot.serializer(), file.readText()) }
            .rethrowCancellation()
            .getOrElse {
                Log.w(TAG, "load failed for $dateIsoLocal", it)
                null
            }
    }

    suspend fun listAll(): List<HrvSnapshot> = withContext(Dispatchers.IO) {
        if (!baseDir.exists()) return@withContext emptyList()
        baseDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching { json.decodeFromString(HrvSnapshot.serializer(), file.readText()) }
                    .rethrowCancellation()
                    .getOrNull()
            }
            ?.sortedByDescending { it.dateIsoLocal }
            ?: emptyList()
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        baseDir.listFiles { f -> f.extension == "json" }?.size ?: 0
    }

    companion object {
        private const val TAG = "URUJ-HrvSnap"
        /**
         * v0.9.48 — stage-aware + sliding-window methodology.
         * - Lomb-Scargle for freq-domain (v0.9.28 baseline)
         * - Stage filter excludes AWAKE periods (Task Force 1996 standard)
         * - 5-min windows with 50% sliding overlap (Plews et al. 2013)
         * - Per-stage RMSSD when ≥3 windows per stage
         * - Methodology version tagged on every snapshot for audit trail
         */
        const val METHODOLOGY_VERSION = "v0.9.48-stage-aware-sliding"
        /** Pre-v0.9.48 fallback when stages aren't available. */
        const val METHODOLOGY_VERSION_FALLBACK = "v0.9.28-lomb-scargle-no-stages"
    }
}
