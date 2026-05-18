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

    /**
     * v0.8.2 — read just the HR-bearing samples from a ride's NDJSON. Each
     * returned entry is `(timestamp, bpm, source)`. Skips samples with null
     * `hrBpm` (no HR recorded for that GPS tick — pre-strap rides or BLE/HC
     * both unavailable at that instant).
     *
     * `source` maps the persisted `hrSource` string back to a `SensorSource`:
     *   "BLE_CHEST_STRAP" → STRAP
     *   "HC_BATCHED" → BAND
     *   "UNKNOWN" or null → UNKNOWN_LEGACY (treated as BAND-equivalent for
     *     fallback purposes, but flagged so the breakdown can show it)
     *
     * Used by RideSummaryViewModel + future TIZ + zone-coloring paths to
     * read the higher-precision strap data when available rather than
     * re-querying HC. Falls back to HC when this returns empty (or
     * effectively empty — < MIN_USEFUL_SAMPLES).
     */
    suspend fun readHrSamples(sessionId: String): List<RideHrSample> =
        readSamples(sessionId, stride = 1)
            .mapNotNull { s ->
                val bpm = s.hrBpm ?: return@mapNotNull null
                if (bpm <= 0) return@mapNotNull null
                RideHrSample(
                    timestampMs = s.timestampMs,
                    bpm = bpm,
                    source = mapSourceString(s.hrSource),
                    // v0.8.5 — carry speed + pause state forward so post-ride
                    // analysis can filter to moving-time samples (matches Strava
                    // convention: AVG HR excludes traffic stops + auto-paused
                    // segments where HR drops to rest).
                    speedMps = s.speedMetersPerSecond,
                    isPaused = s.isPaused,
                )
            }

    private fun mapSourceString(raw: String?): com.uruj.domain.SensorSource = when (raw) {
        "BLE_CHEST_STRAP" -> com.uruj.domain.SensorSource.STRAP
        "HC_BATCHED" -> com.uruj.domain.SensorSource.BAND
        "UNKNOWN", null -> com.uruj.domain.SensorSource.UNKNOWN_LEGACY
        else -> com.uruj.domain.SensorSource.UNKNOWN_LEGACY
    }

    companion object {
        private const val TAG = "URUJ-NdjsonReader"
        /** Hard ceiling on samples returned. At stride=1 corresponds to ~2.7 hours of 1Hz data. */
        private const val MAX_SAMPLES = 10_000
        /** Below this many samples, fall back to HC instead of trusting NDJSON
         *  HR alone. Most real rides have ≥60 samples (1 min minimum). */
        const val MIN_USEFUL_HR_SAMPLES = 30
    }
}

/**
 * v0.8.2 — one HR reading from a ride's NDJSON, with provenance.
 * v0.8.5 — adds speedMps + isPaused so post-ride analysis can apply the
 * standard "moving-time only" filter for AVG/MAX HR (matches Strava /
 * Garmin convention — traffic-light stops at rest HR don't drag down
 * the displayed average).
 */
data class RideHrSample(
    val timestampMs: Long,
    val bpm: Int,
    val source: com.uruj.domain.SensorSource,
    val speedMps: Float = 0f,
    val isPaused: Boolean = false,
) {
    /**
     * v0.8.5 — moving-time filter. Sample counts as "moving" when the
     * recorder wasn't auto-paused AND speed exceeded a walking threshold.
     * Used to exclude rest-HR samples from AVG HR / MAX HR displays so
     * the numbers match what cyclists see in Strava / Garmin Connect.
     */
    val isMoving: Boolean
        get() = !isPaused && speedMps >= MOVING_SPEED_THRESHOLD_MPS

    companion object {
        /**
         * v0.8.5 — 1.8 kph. Just above the auto-pause floor; excludes
         * dismounts + traffic-light stops that hadn't yet triggered
         * auto-pause but are not effort time.
         */
        const val MOVING_SPEED_THRESHOLD_MPS = 0.5f
    }
}
