package com.uruj.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import com.uruj.domain.CarResult
import com.uruj.power.CarDetector
import com.uruj.util.rethrowCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * v0.7.2 — Cortisol Awakening Response repository.
 *
 * Computes CAR for the most-recent overnight sleep window from 24/7 NDJSON
 * data, caches per-day result on disk so the morning Bio Lab open doesn't
 * recompute every refresh.
 *
 * Storage: `/Android/data/com.uruj/files/tests/car/YYYY-MM-DD.json`.
 * Keyed by date (one CAR per night). Re-running on the same day overwrites
 * (e.g. after Samsung's post-wake sleep-window correction lands).
 *
 * Compute is on-demand from BioLabRepository.snapshot(). If the most recent
 * SleepSessionRecord ended <45 min ago, we don't have a full post-wake
 * window yet — return null and try again on next refresh.
 */
class CarRepository(context: Context) {

    private val appContext = context.applicationContext
    private val baseDir: File = File(
        appContext.getExternalFilesDir(null),
        "tests/car",
    ).apply { mkdirs() }
    private val json = Json { encodeDefaults = false; prettyPrint = true }
    private val continuousRepo = ContinuousBiometricRepository(appContext)
    private val lastSleepReader = LastSleepReader()
    private val detector = CarDetector()
    // v0.9.48.8 — guard for ensureBackfilled() so we run the historical
    // migration at most once per process. Per-file failures (missing NDJSON)
    // simply leave the legacy file untouched — next process restart will
    // retry. Concurrent callers (BioLab + CarTrend opening simultaneously)
    // serialize on backfillMutex so we don't double-write the same files.
    @Volatile private var backfillCompleted = false
    private val backfillMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Compute CAR for the most-recent completed sleep window, OR return the
     * cached result if already computed today.
     *
     * Returns null when:
     *   - No Samsung SleepSessionRecord available
     *   - Last sleep ended <45 min ago (window incomplete)
     *   - Not enough 24/7 samples in baseline or post-wake window
     */
    suspend fun computeForLastWake(): CarResult? = withContext(Dispatchers.IO) {
        // v0.9.48.8 — opportunistically backfill historical legacy snapshots
        // the first time we run after a methodology bump. Idempotent + cheap
        // after the first pass.
        ensureBackfilled()
        val sdkOk = HealthConnectClient.getSdkStatus(appContext) ==
            HealthConnectClient.SDK_AVAILABLE
        if (!sdkOk) return@withContext null
        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }
            .rethrowCancellation()
            .getOrNull() ?: return@withContext null
        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .rethrowCancellation()
            .getOrDefault(emptySet())
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted) {
            return@withContext null
        }

        val sleep = lastSleepReader.read(client, granted) ?: return@withContext null
        val sleepEnd = sleep.endedAt
        val now = Instant.now()
        // Need full 45-min post-wake window to compute reliably.
        if (Duration.between(sleepEnd, now).toMinutes() < 45) return@withContext null

        // Check cache first — same day same wake = same result.
        // v0.9.48.8: invalidate cache when methodology version doesn't match
        // the current detector. Old caches without the quiet-window field
        // (methodologyVersion < v0.9.48.8) are recomputed once per day.
        val dateKey = sleepEnd.atZone(ZoneId.systemDefault())
            .format(FILE_DATE_FORMAT)
        val cacheFile = File(baseDir, "$dateKey.json")
        if (cacheFile.exists()) {
            val cached = runCatching {
                json.decodeFromString(CarResult.serializer(), cacheFile.readText())
            }.getOrNull()
            if (cached != null &&
                kotlin.math.abs(cached.sleepEndMs - sleepEnd.toEpochMilli()) < 60_000L &&
                cached.methodologyVersion == CURRENT_METHODOLOGY
            ) {
                return@withContext cached
            }
        }

        // Slice samples for both windows.
        val preWakeStart = sleepEnd.minus(Duration.ofMinutes(10))
        val postWakeEnd = sleepEnd.plus(Duration.ofMinutes(45))
        val preWake = continuousRepo.samplesForWindow(preWakeStart, sleepEnd)
        val postWake = continuousRepo.samplesForWindow(sleepEnd, postWakeEnd)
        Log.d(
            TAG,
            "[car] wake=$sleepEnd preWake=${preWake.size} postWake=${postWake.size}",
        )

        val result = detector.compute(
            sleepEndMs = sleepEnd.toEpochMilli(),
            preWakeSamples = preWake,
            postWakeSamples = postWake,
        ) ?: return@withContext null

        // Cache.
        runCatching {
            cacheFile.writeText(json.encodeToString(CarResult.serializer(), result))
        }.onFailure { Log.w(TAG, "[car] cache write failed", it) }
        result
    }

    /**
     * v0.9.48.8 — One-shot historical migration of legacy CAR snapshots to
     * the rigorous quiet-window methodology (Pruessner/Clow/Stalder).
     *
     * For each cached CAR file whose [CarResult.methodologyVersion] is NOT
     * the current methodology:
     *   1. Slice the same pre-wake (10 min) + post-wake (45 min) windows
     *      from 24/7 NDJSON via [ContinuousBiometricRepository].
     *   2. Re-run [CarDetector.compute] with the new logic (5-min bin
     *      means in 0-30 min post-wake → quiet-window amplitude).
     *   3. Preserve the ORIGINAL sessionId + computedAtMs for traceability
     *      (the recompute is migrating math, not creating a new event).
     *   4. Overwrite the cache file with the migrated result.
     *
     * Failure handling:
     *   - Corrupted JSON file → skip, log warn, leave untouched.
     *   - NDJSON missing / too few samples → skip, log warn. Next process
     *     restart will retry once. NDJSON has been purged from disk for
     *     days >10 days old, so old snapshots can be permanently legacy.
     *   - Compute returns null → skip, leave untouched.
     *   - File-write failure → counted as failed, leave previous file
     *     intact (no half-written state because [File.writeText] is atomic
     *     within Android's app-private storage at this size).
     *
     * Idempotency: a snapshot already at [CURRENT_METHODOLOGY] is skipped
     * cheaply. The [backfillCompleted] flag ensures we attempt the full
     * pass at most once per process — subsequent calls return immediately.
     *
     * Concurrency: guarded by [backfillMutex]. If BioLab.refresh and
     * CarTrendScreen.LaunchedEffect both hit this on app open, the second
     * call awaits the first and then short-circuits via the completion flag.
     */
    suspend fun ensureBackfilled() {
        if (backfillCompleted) return
        backfillMutex.withLock {
            if (backfillCompleted) return@withLock
            try {
                val result = backfillHistorical()
                Log.d(
                    TAG,
                    "[backfill] complete: ${result.recomputed} recomputed, " +
                        "${result.alreadyRigorous} already-rigorous, " +
                        "${result.skipped} skipped (no NDJSON or insufficient samples), " +
                        "${result.failed} failed",
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "[backfill] aborted by exception", t)
            } finally {
                backfillCompleted = true
            }
        }
    }

    /** Result counters for the backfill pass (exposed for tests + logging). */
    data class BackfillResult(
        val recomputed: Int,
        val alreadyRigorous: Int,
        val skipped: Int,
        val failed: Int,
    )

    private suspend fun backfillHistorical(): BackfillResult = withContext(Dispatchers.IO) {
        val files = baseDir.listFiles { f -> f.name.endsWith(".json") }
            ?: return@withContext BackfillResult(0, 0, 0, 0)
        var recomputed = 0
        var alreadyRigorous = 0
        var skipped = 0
        var failed = 0

        for (file in files) {
            val cached = runCatching {
                json.decodeFromString(CarResult.serializer(), file.readText())
            }.getOrNull()
            if (cached == null) {
                Log.w(TAG, "[backfill] ${file.name} corrupt or unreadable — leaving untouched")
                failed++
                continue
            }
            if (cached.methodologyVersion == CURRENT_METHODOLOGY) {
                alreadyRigorous++
                continue
            }

            // Re-slice the same windows from NDJSON.
            val sleepEnd = Instant.ofEpochMilli(cached.sleepEndMs)
            val preWakeStart = sleepEnd.minus(Duration.ofMinutes(10))
            val postWakeEnd = sleepEnd.plus(Duration.ofMinutes(45))

            val preWake = runCatching { continuousRepo.samplesForWindow(preWakeStart, sleepEnd) }
                .rethrowCancellation()
                .getOrDefault(emptyList())
            val postWake = runCatching { continuousRepo.samplesForWindow(sleepEnd, postWakeEnd) }
                .rethrowCancellation()
                .getOrDefault(emptyList())

            if (preWake.size < MIN_SAMPLES_FOR_BACKFILL ||
                postWake.size < MIN_SAMPLES_FOR_BACKFILL
            ) {
                Log.w(
                    TAG,
                    "[backfill] ${file.name} insufficient NDJSON " +
                        "(pre=${preWake.size}, post=${postWake.size}) — leaving legacy untouched",
                )
                skipped++
                continue
            }

            val fresh = detector.compute(
                sleepEndMs = cached.sleepEndMs,
                preWakeSamples = preWake,
                postWakeSamples = postWake,
            )
            if (fresh == null) {
                Log.w(TAG, "[backfill] ${file.name} compute returned null — leaving legacy untouched")
                skipped++
                continue
            }

            // Preserve original session identifiers — this is a math migration
            // of an existing reading, not a new event.
            val migrated = fresh.copy(
                sessionId = cached.sessionId,
                computedAtMs = cached.computedAtMs,
            )

            val writeOk = runCatching {
                file.writeText(json.encodeToString(CarResult.serializer(), migrated))
            }.isSuccess
            if (!writeOk) {
                Log.w(TAG, "[backfill] ${file.name} write failed — leaving legacy untouched")
                failed++
                continue
            }

            val oldAmp = "%.1f".format(cached.amplitudeBpm)
            val newAmp = migrated.quietWindowAmplitudeBpm?.let { "%.1f".format(it) } ?: "n/a"
            Log.d(
                TAG,
                "[backfill] ${file.name}: legacy wide-window=$oldAmp bpm → " +
                    "rigorous quiet-window=$newAmp bpm",
            )
            recomputed++
        }
        BackfillResult(recomputed, alreadyRigorous, skipped, failed)
    }

    /** Latest cached result on disk (for fast Bio Lab render before refresh). */
    fun cachedLatest(): CarResult? {
        if (!baseDir.exists()) return null
        val files = baseDir.listFiles { f -> f.name.endsWith(".json") }
            ?: return null
        return files
            .mapNotNull { f ->
                runCatching {
                    json.decodeFromString(CarResult.serializer(), f.readText())
                }.getOrNull()
            }
            .maxByOrNull { it.sleepEndMs }
    }

    /** All cached results, newest first (for v0.7.3 trend chart). */
    fun listAll(): List<CarResult> {
        if (!baseDir.exists()) return emptyList()
        val files = baseDir.listFiles { f -> f.name.endsWith(".json") }
            ?: return emptyList()
        return files
            .mapNotNull { f ->
                runCatching {
                    json.decodeFromString(CarResult.serializer(), f.readText())
                }.getOrNull()
            }
            .sortedByDescending { it.sleepEndMs }
    }

    companion object {
        private const val TAG = "URUJ-CAR-Repo"
        // v0.9.48.8 — must match CarResult that fresh computes write.
        // Bump when CarDetector math changes to invalidate stale caches
        // and trigger backfill on next ensureBackfilled() call.
        private const val CURRENT_METHODOLOGY = "v0.9.48.8"
        // Sanity floor — same as CarDetector.minSamplesPerWindow. Backfill
        // skips windows under this threshold so we don't write low-quality
        // results that could mislead the trend chart.
        private const val MIN_SAMPLES_FOR_BACKFILL = 60
        private val FILE_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
