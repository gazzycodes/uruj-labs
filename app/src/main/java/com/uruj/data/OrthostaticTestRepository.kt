package com.uruj.data

import android.content.Context
import android.util.Log
import com.uruj.domain.OrthostaticTestResult
import com.uruj.power.OrthostaticTestCalculator
import com.uruj.util.rethrowCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * v0.7.1 — persists OrthostaticTestResult to per-test JSON files in
 * `/Android/data/com.uruj/files/tests/orthostatic/`. One file per test,
 * timestamped filename, so listing the directory gives chronological history.
 *
 * Also exposes `samplesForWindow` on top of the existing 24/7 NDJSON for
 * slicing test windows after capture — no separate BLE subscriber needed.
 *
 * v0.9.49.1 — gains the full versioning + backfill pattern from
 * [[reference_lab_grade_architecture_rules]] Rule 2:
 *   - [OrthostaticTestResult.methodologyVersion] field gates cache freshness
 *   - [ensureBackfilled] migrates legacy snapshots when math changes
 *   - Idempotent + mutex-guarded + atomic per-file rewrite, fail-safe
 */
class OrthostaticTestRepository(context: Context) {

    private val appContext = context.applicationContext
    private val baseDir: File = File(
        appContext.getExternalFilesDir(null),
        "tests/orthostatic",
    ).apply { mkdirs() }
    private val json = Json { encodeDefaults = false; prettyPrint = true }
    private val continuousRepo = ContinuousBiometricRepository(appContext)
    private val calculator = OrthostaticTestCalculator()
    @Volatile private var backfillCompleted = false
    private val backfillMutex = Mutex()

    /** Slice the 24/7 NDJSON for the requested window. */
    fun samplesForWindow(startMs: Long, endMs: Long): List<ContinuousSample> {
        val start = Instant.ofEpochMilli(startMs)
        val end = Instant.ofEpochMilli(endMs)
        return continuousRepo.samplesForWindow(start, end)
    }

    /**
     * Pre-flight: is the 24/7 service actually CAPTURING right now? The
     * toggle being on doesn't guarantee BLE is streaming — strap could be
     * disconnected, off-skin, out of range, or the service could have been
     * killed by Android Doze and is awaiting auto-restart.
     *
     * Returns the count of samples received in the last [withinMs] ms.
     * 0 = nothing flowing right now. Non-zero = pipeline is alive.
     */
    fun recentSampleCount(withinMs: Long = 60_000L): Int {
        val now = Instant.now()
        val start = now.minusMillis(withinMs)
        return continuousRepo.samplesForWindow(start, now).size
    }

    /** Persist a finished test result to disk. */
    fun save(result: OrthostaticTestResult): Boolean {
        val ts = Instant.ofEpochMilli(result.startedAtMs)
            .atZone(ZoneId.systemDefault())
            .format(FILENAME_FORMATTER)
        val file = File(baseDir, "$ts.json")
        return runCatching {
            file.writeText(json.encodeToString(OrthostaticTestResult.serializer(), result))
            true
        }.onFailure { Log.w(TAG, "[ortho] save failed", it) }.getOrDefault(false)
    }

    /** Return the most-recent saved test, or null when no history exists. */
    fun latest(): OrthostaticTestResult? = listAll().firstOrNull()

    /** Return every saved test, newest first. */
    fun listAll(): List<OrthostaticTestResult> {
        if (!baseDir.exists()) return emptyList()
        val files = baseDir.listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching {
                json.decodeFromString(OrthostaticTestResult.serializer(), f.readText())
            }.getOrNull()
        }.sortedByDescending { it.startedAtMs }
    }

    /**
     * v0.9.49.1 — Idempotent one-shot migration of legacy orthostatic
     * snapshots to the current methodology. Same shape as
     * [CarRepository.ensureBackfilled]. Per-file failures (missing NDJSON,
     * corrupt JSON, write error) leave the original file UNTOUCHED.
     *
     * Run once per process. Triggers automatically from
     * [OrthostaticTrendScreen.LaunchedEffect] so the trend chart never
     * silently shows mixed methodology readings.
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
                        "${result.skipped} skipped, ${result.failed} failed",
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "[backfill] aborted by exception", t)
            } finally {
                backfillCompleted = true
            }
        }
    }

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
                json.decodeFromString(OrthostaticTestResult.serializer(), file.readText())
            }.getOrNull()
            if (cached == null) {
                Log.w(TAG, "[backfill] ${file.name} corrupt or unreadable — leaving untouched")
                failed++
                continue
            }
            if (cached.methodologyVersion == OrthostaticTestCalculator.CURRENT_METHODOLOGY) {
                alreadyRigorous++
                continue
            }

            // Re-slice NDJSON for the same seated + standing windows.
            val seated = runCatching {
                samplesForWindow(cached.seatedStartMs, cached.seatedEndMs)
            }.rethrowCancellation().getOrDefault(emptyList())
            val standing = runCatching {
                samplesForWindow(cached.standingStartMs, cached.standingEndMs)
            }.rethrowCancellation().getOrDefault(emptyList())

            if (seated.size < MIN_SAMPLES_FOR_BACKFILL ||
                standing.size < MIN_SAMPLES_FOR_BACKFILL
            ) {
                Log.w(
                    TAG,
                    "[backfill] ${file.name} insufficient NDJSON " +
                        "(seated=${seated.size}, standing=${standing.size}) — leaving legacy untouched",
                )
                skipped++
                continue
            }

            val fresh = calculator.compute(
                sessionId = cached.sessionId,
                startedAtMs = cached.startedAtMs,
                seatedSamples = seated,
                seatedStartMs = cached.seatedStartMs,
                seatedEndMs = cached.seatedEndMs,
                standingSamples = standing,
                standingStartMs = cached.standingStartMs,
                standingEndMs = cached.standingEndMs,
            )
            if (fresh == null) {
                Log.w(TAG, "[backfill] ${file.name} compute returned null — leaving legacy untouched")
                skipped++
                continue
            }

            val writeOk = runCatching {
                file.writeText(json.encodeToString(OrthostaticTestResult.serializer(), fresh))
            }.isSuccess
            if (!writeOk) {
                Log.w(TAG, "[backfill] ${file.name} write failed — leaving legacy untouched")
                failed++
                continue
            }

            Log.d(
                TAG,
                "[backfill] ${file.name}: legacy → rigorous (hrDelta " +
                    "${"%.1f".format(cached.hrDeltaBpm)} → ${"%.1f".format(fresh.hrDeltaBpm)} bpm, " +
                    "rmssdRatio ${"%.2f".format(cached.rmssdRatio)} → ${"%.2f".format(fresh.rmssdRatio)})",
            )
            recomputed++
        }
        BackfillResult(recomputed, alreadyRigorous, skipped, failed)
    }

    companion object {
        private const val TAG = "URUJ-Ortho-Repo"
        private const val MIN_SAMPLES_FOR_BACKFILL = 30
        private val FILENAME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss")
    }
}
