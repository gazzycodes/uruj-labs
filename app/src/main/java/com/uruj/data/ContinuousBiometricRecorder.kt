package com.uruj.data

import android.content.Context
import android.util.Log
import com.uruj.sensor.HrSample
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileWriter
import java.time.LocalDate
import java.time.ZoneId

/**
 * v0.6.0 — append-only NDJSON writer for 24/7 continuous biometric capture
 * via the BLE chest strap. Daily-rotated files: every sample lands in
 * `/Android/data/com.uruj/files/continuous/YYYY-MM-DD.ndjson`.
 *
 * One line per BLE notification. Schema is intentionally minimal — we store
 * exactly what's needed for offline HRV / autonomic analysis, nothing else:
 *   - timestampMs: when URUJ received the notification
 *   - bpm: HR value from the notification
 *   - rrIntervalsMs: list of RR intervals in this notification (1-3 typical)
 *   - contactDetected: nullable, electrodes-on-skin status
 *   - batteryPct: nullable, strap battery for diagnostics
 *
 * Why daily files: easier to delete old data, easier to ingest one day at a
 * time for sleep/CAR/postprandial analysis, smaller per-file size keeps
 * resume-after-crash recovery fast.
 *
 * Storage math: ~1Hz × ~50 bytes/line × 86,400 sec/day = ~5 MB/day raw.
 * Compressed-on-read for analysis. 90-day rolling retention planned for
 * v0.6.x; tonight stores indefinitely (user can manually clean).
 *
 * Crash-safe: open the writer once per service start, FileWriter has its own
 * buffering, but we flush after each line so a force-kill loses ≤1 sample.
 */
@Serializable
data class ContinuousSample(
    val timestampMs: Long,
    val bpm: Int,
    val rrIntervalsMs: List<Int> = emptyList(),
    val contactDetected: Boolean? = null,
    val batteryPct: Int? = null,
)

class ContinuousBiometricRecorder(context: Context) {

    private val baseDir: File = File(
        context.getExternalFilesDir(null),
        "continuous",
    ).apply { mkdirs() }

    private val json = Json { encodeDefaults = false; prettyPrint = false }

    private var currentDay: LocalDate? = null
    private var currentWriter: FileWriter? = null
    private var linesWritten: Long = 0L

    /**
     * Append one sample to today's NDJSON file. Rotates to a new file at
     * local-midnight automatically. Called from BiometricService on every
     * BLE notification.
     */
    @Synchronized
    fun append(sample: HrSample, batteryPct: Int?) {
        val today = LocalDate.now(ZoneId.systemDefault())
        if (today != currentDay) {
            rotate(today)
        }
        val writer = currentWriter ?: return
        val line = json.encodeToString(
            ContinuousSample.serializer(),
            ContinuousSample(
                timestampMs = sample.receivedAtMs,
                bpm = sample.bpm,
                rrIntervalsMs = sample.rrIntervalsMs,
                contactDetected = sample.contactDetected,
                batteryPct = batteryPct,
            ),
        )
        runCatching {
            writer.write(line)
            writer.write("\n")
            writer.flush()  // crash-safe — every sample on disk before next
            linesWritten += 1
            if (linesWritten % 1000 == 0L) {
                Log.d(TAG, "[24/7] $linesWritten samples written to ${currentDay}.ndjson")
            }
        }.onFailure { Log.w(TAG, "[24/7] write failed", it) }
    }

    @Synchronized
    fun close() {
        runCatching {
            currentWriter?.flush()
            currentWriter?.close()
        }
        currentWriter = null
        currentDay = null
        Log.d(TAG, "[24/7] writer closed, total lines: $linesWritten")
    }

    private fun rotate(newDay: LocalDate) {
        runCatching {
            currentWriter?.flush()
            currentWriter?.close()
        }
        val file = File(baseDir, "${newDay}.ndjson")
        val isNew = !file.exists()
        currentWriter = FileWriter(file, /* append = */ true)
        currentDay = newDay
        Log.d(TAG, "[24/7] rotated to ${file.absolutePath} (new=$isNew)")
    }

    /** For UI / diagnostics: how many samples in today's file (line count). */
    fun samplesTodayCount(): Long {
        val today = LocalDate.now(ZoneId.systemDefault())
        val file = File(baseDir, "$today.ndjson")
        if (!file.exists()) return 0L
        return runCatching {
            file.useLines { it.count().toLong() }
        }.getOrDefault(0L)
    }

    /** Lists available daily files, newest first. Used by future history UI. */
    fun availableDays(): List<LocalDate> {
        val files = baseDir.listFiles { f -> f.name.endsWith(".ndjson") } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching { LocalDate.parse(f.nameWithoutExtension) }.getOrNull()
        }.sortedDescending()
    }

    companion object {
        private const val TAG = "URUJ-Continuous"
    }
}
