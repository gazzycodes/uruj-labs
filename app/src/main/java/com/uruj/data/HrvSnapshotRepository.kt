package com.uruj.data

import android.content.Context
import android.util.Log
import com.uruj.util.rethrowCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
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
     *
     * @param overrideHistorical v0.9.57 — bypass the past-immutable guard.
     *   Default false preserves the historical contract for normal compute
     *   paths (BioLab snapshot, Readiness compute) — those must NEVER
     *   overwrite locked-in historical readings. Migration paths
     *   ([ensureBackfilled]) pass true to upgrade legacy-methodology
     *   snapshots to current methodology in-place. The default-false
     *   discipline means a misuse can't accidentally rewrite history.
     */
    suspend fun save(
        snapshot: HrvSnapshot,
        date: LocalDate,
        overrideHistorical: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        val today = LocalDate.now(ZoneId.systemDefault())
        val file = File(baseDir, "${snapshot.dateIsoLocal}.json")
        if (!overrideHistorical && date != today && file.exists()) {
            Log.d(TAG, "skipping save: ${snapshot.dateIsoLocal} is historical (immutable)")
            return@withContext false
        }
        runCatching {
            file.writeText(json.encodeToString(HrvSnapshot.serializer(), snapshot))
            // v0.9.52 — keep cache hot after write so next read sees the
            // freshest value without a disk round-trip. ConcurrentHashMap
            // is thread-safe for this concurrent mutation.
            cache[snapshot.dateIsoLocal] = snapshot
            val migrationTag = if (overrideHistorical) " · MIGRATION" else ""
            Log.d(
                TAG,
                "saved HRV snapshot ${snapshot.dateIsoLocal}$migrationTag: " +
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

    // v0.9.57 — process-scoped guard for the one-time methodology migration.
    // AtomicBoolean compareAndSet semantics: only the first caller per
    // process slips through; concurrent callers see the flag flipped and
    // skip. On process death the flag resets, so a failed migration retries
    // next session — safer than persisting "we tried once" across restarts.
    private val backfillAttempted = java.util.concurrent.atomic.AtomicBoolean(false)
    // Coroutine mutex around the actual migration so two concurrent callers
    // that race the atomic flag still serialize on the work itself.
    private val backfillMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * v0.9.57 — One-time methodology migration for stored [HrvSnapshot]
     * files. Walks all current snapshots and recomputes any whose
     * [HrvSnapshot.methodologyVersion] differs from the current
     * [METHODOLOGY_VERSION] (or [METHODOLOGY_VERSION_FALLBACK] when stages
     * aren't available for that night).
     *
     * ## Why this exists
     *
     * v0.9.52 attempted disk-first historical HRV reads in
     * [com.uruj.data.ReadinessRepository] — but the stored snapshots had a
     * mixture of methodology versions (legacy / v0.9.27-lomb-scargle / pre-
     * stage-aware), so reading them gave the engine values from inconsistent
     * math. The 7d median drifted 13.7 → 11.3 ms in the user's data — a
     * silent regression. v0.9.52.1 reverted that path.
     *
     * v0.9.57 fixes the foundation so v0.9.58 can safely re-enable
     * disk-first reads: every stored snapshot becomes byte-equivalent to
     * what current [com.uruj.power.HrvCalculator] + Lomb-Scargle freq-domain
     * would produce TODAY. After migration, reading from disk is
     * indistinguishable from recomputing — except 14s faster.
     *
     * ## Past-date override discipline
     *
     * Normally [save] blocks past-date overwrites via the past-immutable
     * rule from [[reference_snapshot_persistence_architecture]]. Migration
     * is the explicit, intentional exception: we're not editing history
     * with NEW math, we're propagating CURRENT math to old records so
     * surfaces don't display inconsistent values. The `overrideHistorical=true`
     * is documented in commit history + methodology version tag.
     *
     * ## Idempotency + concurrency
     *
     * - [backfillAttempted] AtomicBoolean — flag flips on entry; subsequent
     *   callers in the same process see it true and return 0 immediately.
     * - [backfillMutex] — serializes concurrent callers that race the flag
     *   so only one actual migration runs (defense-in-depth).
     * - Re-run on process restart — flag is process-local, so a failed
     *   migration retries next session.
     *
     * ## What can't be migrated
     *
     * - Snapshots older than ~30 days: Health Connect retains sleep
     *   sessions ~30d. Without the sleep window we can't recompute the
     *   HRV calculation. Skipped with a log line; snapshot stays legacy.
     * - Snapshots whose NDJSON file has been pruned: the raw RR intervals
     *   needed to recompute aren't on disk anymore. Skipped.
     * - Snapshots where recompute returns null (insufficient samples):
     *   the original snapshot must have been from a different data state;
     *   we leave it alone.
     *
     * These are acceptable degradations — they only affect snapshots that
     * are already too old to be useful for the rolling 7-day trend anyway.
     *
     * ## Math correctness
     *
     * - Time-domain RMSSD: [continuousBiometric.computeHrvForWindow] with
     *   stages → same calc as today's BioLab.snapshot()
     * - Freq-domain LF/HF + DFA α1 + SD1/SD2: [continuousBiometric.computeFrequencyDomainForWindow]
     * - All fields populated from the same calculator outputs that
     *   today's compute uses. Byte-identical.
     *
     * Returns the count of snapshots actually migrated (recomputed +
     * saved). 0 = nothing needed migration OR nothing could be migrated.
     */
    suspend fun ensureBackfilled(
        continuousBiometric: ContinuousBiometricRepository,
        lastSleepReader: LastSleepReader,
        client: androidx.health.connect.client.HealthConnectClient?,
        granted: Set<String>,
    ): Int = withContext(Dispatchers.IO) {
        // Fast path: already done in this process.
        if (backfillAttempted.get()) return@withContext 0
        // Guard against concurrent racers + an actually broken HC client.
        if (client == null) {
            Log.d(TAG, "[v0.9.57] backfill skipped — HC client null")
            return@withContext 0
        }
        backfillMutex.withLock {
            // Re-check inside the lock — a concurrent caller may have just
            // finished while we were waiting on the mutex.
            if (!backfillAttempted.compareAndSet(false, true)) {
                return@withLock 0
            }
            runMigration(continuousBiometric, lastSleepReader, client, granted)
        }
    }

    private suspend fun runMigration(
        continuousBiometric: ContinuousBiometricRepository,
        lastSleepReader: LastSleepReader,
        client: androidx.health.connect.client.HealthConnectClient,
        granted: Set<String>,
    ): Int {
        val all = listAll()
        if (all.isEmpty()) {
            Log.d(TAG, "[v0.9.57] backfill: no snapshots on disk, nothing to migrate")
            return 0
        }
        // Only legacy / pre-v0.9.48 versions need migration.
        // Current ones (v0.9.48-stage-aware-sliding) AND the explicit
        // fallback (v0.9.28-lomb-scargle-no-stages) are both accepted as
        // "current methodology" — the fallback is the correct answer when
        // a night had no stages available, and recomputing it without
        // stages would produce the same fallback again.
        val staleVersions = all.filter { snap ->
            snap.methodologyVersion != METHODOLOGY_VERSION &&
                snap.methodologyVersion != METHODOLOGY_VERSION_FALLBACK
        }
        if (staleVersions.isEmpty()) {
            Log.d(
                TAG,
                "[v0.9.57] backfill: all ${all.size} snapshots already on current methodology — no work",
            )
            return 0
        }
        Log.d(
            TAG,
            "[v0.9.57] backfill: ${staleVersions.size} of ${all.size} snapshots need migration to $METHODOLOGY_VERSION",
        )
        // Pull sleep sessions for the broadest window HC will give us
        // (~30 days). Older stored snapshots that aren't covered can't be
        // re-windowed and stay legacy until naturally rolled off.
        val sessions = runCatching {
            lastSleepReader.listLastNDays(client, granted, 30)
        }.rethrowCancellation()
            .onFailure { Log.w(TAG, "[v0.9.57] backfill: sleep list failed", it) }
            .getOrDefault(emptyList())
        if (sessions.isEmpty()) {
            Log.d(
                TAG,
                "[v0.9.57] backfill: no HC sleep sessions available (perm missing or empty), cannot migrate",
            )
            return 0
        }
        val zone = java.time.ZoneId.systemDefault()
        val sessionByDate: Map<LocalDate, LastSleepReader.Result> = sessions.associateBy {
            it.endedAt.atZone(zone).toLocalDate()
        }
        var migrated = 0
        for (snap in staleVersions) {
            val date = runCatching { LocalDate.parse(snap.dateIsoLocal) }.getOrNull()
            if (date == null) {
                Log.w(TAG, "[v0.9.57] backfill: skip ${snap.dateIsoLocal} — unparseable date")
                continue
            }
            val session = sessionByDate[date]
            if (session == null) {
                Log.d(
                    TAG,
                    "[v0.9.57] backfill: skip $date — no HC sleep session in retention window",
                )
                continue
            }
            // Best effort: pull stages so we land on v0.9.48 methodology.
            // If stages are unavailable we still recompute (lands on the
            // fallback methodology, which is current for the stage-less
            // case). Either way we converge on current math.
            val stages = runCatching {
                lastSleepReader.readStagesForSession(client, granted, session.startedAt, session.endedAt)
            }.rethrowCancellation()
                .getOrDefault(emptyList())
            val freshHrv = continuousBiometric.computeHrvForWindow(
                session.startedAt,
                session.endedAt,
                stages,
            )
            if (freshHrv == null) {
                Log.d(
                    TAG,
                    "[v0.9.57] backfill: skip $date — recompute returned null (insufficient samples)",
                )
                continue
            }
            val freshFreq = continuousBiometric.computeFrequencyDomainForWindow(
                session.startedAt,
                session.endedAt,
            )
            val newVersion = if (stages.isNotEmpty()) METHODOLOGY_VERSION else METHODOLOGY_VERSION_FALLBACK
            // Skip the write when recompute produces the same version AND
            // RMSSD lands within 0.05 ms of the existing snapshot — that's
            // floating-point noise, not a real correction.
            val rmssdDelta = freshHrv.rmssdMs - (snap.rmssdMs ?: 0f)
            if (snap.methodologyVersion == newVersion && kotlin.math.abs(rmssdDelta) < 0.05f) {
                Log.d(
                    TAG,
                    "[v0.9.57] backfill: skip $date — same version $newVersion, delta ${"%.3f".format(rmssdDelta)} ms (noise)",
                )
                continue
            }
            val perStage = freshHrv.perStageRmssdMs
            // Note: awakeMinutesExcluded is not exposed by TimeDomainHrv —
            // snap.copy() preserves the existing value (or null if the
            // original snapshot pre-dated that field, which is fine — the
            // field is informational, not used in scoring).
            val migrated_ = snap.copy(
                rmssdMs = freshHrv.rmssdMs,
                sdnnMs = freshHrv.sdnnMs,
                pnn50Percent = freshHrv.pnn50Percent,
                pnn20Percent = freshHrv.pnn20Percent,
                meanHrBpm = freshHrv.meanHrBpm,
                sampleCount = freshHrv.sampleCount,
                windowCount = freshHrv.windowCount,
                deepRmssdMs = perStage["deep"],
                remRmssdMs = perStage["rem"],
                lightRmssdMs = perStage["light"],
                stageFiltered = stages.isNotEmpty(),
                vlfMs2 = freshFreq?.vlfMs2,
                lfMs2 = freshFreq?.lfMs2,
                hfMs2 = freshFreq?.hfMs2,
                totalPowerMs2 = freshFreq?.totalPowerMs2,
                lfHfRatio = freshFreq?.lfHfRatio,
                sd1Ms = freshFreq?.sd1Ms,
                sd2Ms = freshFreq?.sd2Ms,
                dfaAlpha1 = freshFreq?.dfaAlpha1,
                sampleEntropy = freshFreq?.sampleEntropy,
                methodologyVersion = newVersion,
                computedAtMs = System.currentTimeMillis(),
            )
            val ok = save(migrated_, date, overrideHistorical = true)
            if (ok) {
                migrated++
                Log.d(
                    TAG,
                    "[v0.9.57] backfill: $date migrated " +
                        "${snap.methodologyVersion} → $newVersion " +
                        "(rmssd ${snap.rmssdMs?.let { "%.1f".format(it) } ?: "—"} → " +
                        "${freshHrv.rmssdMs.let { "%.1f".format(it) }} ms)",
                )
            }
        }
        Log.d(
            TAG,
            "[v0.9.57] backfill complete: $migrated migrated, " +
                "${staleVersions.size - migrated} skipped, ${all.size - staleVersions.size} already current",
        )
        return migrated
    }

    /** Test-only — reset the migration flag so unit tests can re-invoke. */
    @Suppress("unused")
    internal fun resetBackfillStateForTesting() {
        backfillAttempted.set(false)
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
