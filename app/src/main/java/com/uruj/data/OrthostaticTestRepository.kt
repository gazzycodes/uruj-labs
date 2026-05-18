package com.uruj.data

import android.content.Context
import android.util.Log
import com.uruj.domain.OrthostaticTestResult
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
 */
class OrthostaticTestRepository(context: Context) {

    private val appContext = context.applicationContext
    private val baseDir: File = File(
        appContext.getExternalFilesDir(null),
        "tests/orthostatic",
    ).apply { mkdirs() }
    private val json = Json { encodeDefaults = false; prettyPrint = true }
    private val continuousRepo = ContinuousBiometricRepository(appContext)

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

    companion object {
        private const val TAG = "URUJ-Ortho-Repo"
        private val FILENAME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss")
    }
}
