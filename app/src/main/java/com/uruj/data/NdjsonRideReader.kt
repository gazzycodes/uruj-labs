package com.uruj.data

import android.content.Context
import android.util.Log
import com.uruj.domain.RideSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Streams a ride's NDJSON file back into a List<RideSample>. Counterpart to the
 * append-only writer in NdjsonRideRecorder. Used by RouteMapScreen + any future
 * post-ride analysis screen that needs second-by-second telemetry (the
 * StoredRideSummary aggregates aren't enough for visualization).
 *
 * Defensive parsing — skips malformed lines without failing the whole read.
 * Caps result at MAX_SAMPLES to bound memory; very long rides at 1Hz can hit
 * 30k+ samples, more than necessary for a route polyline. Caller can request
 * stride-based downsampling for rendering smoothness.
 */
class NdjsonRideReader(context: Context) {

    private val ridesDir = File(context.getExternalFilesDir(null), "rides")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Read all RideSample lines for the given session. Returns empty list when
     * the file doesn't exist (ride was lost, summary-only, or sessionId is wrong).
     *
     * @param stride keep every Nth sample for memory efficiency. stride=1 keeps
     *   all, stride=5 keeps every 5th. Map rendering doesn't need 1Hz precision.
     */
    suspend fun readSamples(sessionId: String, stride: Int = 1): List<RideSample> = withContext(Dispatchers.IO) {
        val file = File(ridesDir, "$sessionId.ndjson")
        if (!file.exists()) {
            Log.w(TAG, "NDJSON file not found: ${file.absolutePath}")
            return@withContext emptyList()
        }
        val samples = mutableListOf<RideSample>()
        var lineNumber = 0
        var skipped = 0
        runCatching {
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    lineNumber++
                    if ((lineNumber - 1) % stride != 0) continue
                    if (line.isBlank()) continue
                    val parsed = runCatching {
                        json.decodeFromString(RideSample.serializer(), line)
                    }.getOrNull()
                    if (parsed != null) samples += parsed
                    else skipped++
                    if (samples.size >= MAX_SAMPLES) break
                }
            }
        }.onFailure { Log.w(TAG, "NDJSON read failed for $sessionId", it) }
        if (skipped > 0) Log.w(TAG, "$sessionId: skipped $skipped malformed lines")
        Log.d(TAG, "$sessionId: loaded ${samples.size} samples (stride=$stride)")
        samples
    }

    companion object {
        private const val TAG = "URUJ-NdjsonReader"
        /** Hard ceiling on samples returned. At stride=1 corresponds to ~2.7 hours of 1Hz data. */
        private const val MAX_SAMPLES = 10_000
    }
}
