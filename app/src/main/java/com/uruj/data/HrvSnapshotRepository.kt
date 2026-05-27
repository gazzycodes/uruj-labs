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
    // v0.9.49.1 — default "legacy" per [[reference_lab_grade_architecture_rules]]
    // Rule 2 so pre-versioning snapshots deserialize gracefully + future
    // methodology bumps invalidate via mismatch instead of accidental match.
    val methodologyVersion: String = "legacy",
)

class HrvSnapshotRepository(context: Context) {

    private val baseDir = File(context.applicationContext.filesDir, "snapshots/hrv")

    init {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    // v0.9.52 — In-memory snapshot cache. Populated on first read of any
    // listLastNDays() / load() call, invalidated on save(). Capped at last
    // 90 days (~180 KB total — trivial). Process-local — gone on process
    // restart, hits disk on first read after restart. Solves the
    // "every Bio Lab refresh re-parses 7 JSON files" subtree of perf cost.
    private val cache: java.util.concurrent.ConcurrentHashMap<String, HrvSnapshot> =
        java.util.concurrent.ConcurrentHashMap()
    @Volatile private var cacheWarmed: Boolean = false

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
            // v0.9.52 — keep cache hot after write so next read sees the
            // freshest value without a disk round-trip. ConcurrentHashMap
            // is thread-safe for this concurrent mutation.
            cache[snapshot.dateIsoLocal] = snapshot
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
        // v0.9.52 — cache check first
        cache[dateIsoLocal]?.let { return@withContext it }
        val file = File(baseDir, "$dateIsoLocal.json")
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString(HrvSnapshot.serializer(), file.readText()) }
            .rethrowCancellation()
            .getOrElse {
                Log.w(TAG, "load failed for $dateIsoLocal", it)
                null
            }
            ?.also { cache[dateIsoLocal] = it }  // populate cache on disk-read miss
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
            ?.also { snapshots ->
                // v0.9.52 — opportunistically warm the cache from listAll().
                // Future load(date) calls become free.
                snapshots.forEach { cache[it.dateIsoLocal] = it }
                cacheWarmed = true
            }
            ?: emptyList()
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        baseDir.listFiles { f -> f.extension == "json" }?.size ?: 0
    }

    /**
     * v0.9.52 — Return the snapshots dated within the last [days] calendar
     * days (counting back from today), newest first. **Reads disk only.**
     * Cache-aware: hits in-memory cache for already-loaded dates, falls
     * through to disk parse for any missing.
     *
     * This replaces the historical-recompute anti-pattern in
     * BioLabRepository + ReadinessRepository where they were calling
     * [com.uruj.data.ContinuousBiometricRepository.dailyOvernightHrvHistoryFromSessions]
     * just to count days / get baseline RMSSD values — that path re-ran
     * the full HrvCalculator pipeline on raw NDJSON for each of 7 nights
     * (~14s wasted per call). Now: ~10ms read from disk + cache.
     *
     * Returns snapshots ordered by `dateIsoLocal` DESCENDING (newest first),
     * matching the prior convention of [dailyOvernightHrvHistoryFromSessions].
     *
     * Fallback: if disk listing fails entirely, returns empty list. Caller
     * (Readiness / BioLab) gracefully degrades to "baseline building" UX.
     */
    suspend fun listLastNDays(days: Int): List<HrvSnapshot> = withContext(Dispatchers.IO) {
        if (!baseDir.exists()) return@withContext emptyList()
        val today = LocalDate.now(ZoneId.systemDefault())
        val cutoff = today.minusDays(days.toLong() - 1)  // last N days INCLUSIVE of today
        // Use cache-first path. If cacheWarmed, all snapshots are already in cache.
        // Otherwise, do a one-time scan of the directory to populate.
        if (!cacheWarmed) {
            runCatching {
                baseDir.listFiles { f -> f.extension == "json" }
                    ?.mapNotNull { file ->
                        runCatching {
                            json.decodeFromString(HrvSnapshot.serializer(), file.readText())
                        }.rethrowCancellation().getOrNull()
                    }
                    ?.forEach { cache[it.dateIsoLocal] = it }
                cacheWarmed = true
            }
        }
        // Filter from in-memory cache by date range, sorted newest-first.
        return@withContext cache.values
            .filter { snap ->
                runCatching {
                    val date = LocalDate.parse(snap.dateIsoLocal)
                    !date.isBefore(cutoff) && !date.isAfter(today)
                }.getOrDefault(false)
            }
            .sortedByDescending { it.dateIsoLocal }
    }

    /**
     * v0.9.52 — Lightweight count of snapshots dated within the last [days]
     * calendar days. Used for "Day N of 7 baseline" UX badges. Reads from
     * the in-memory cache (populated by [listLastNDays] / [listAll] / [load]),
     * falls back to disk listing if cache not yet warmed.
     */
    suspend fun countInLastNDays(days: Int): Int = withContext(Dispatchers.IO) {
        // Side-effect: ensures cache warm. The count is derived from the
        // filtered cache values to stay consistent with listLastNDays().
        listLastNDays(days).size
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
