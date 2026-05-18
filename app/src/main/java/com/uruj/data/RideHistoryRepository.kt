package com.uruj.data

import android.content.Context
import android.util.Log
import com.uruj.domain.RideSample
import com.uruj.service.RideState
import com.uruj.util.haversineMeters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent ride summaries. When a ride ends, the service writes a sibling
 * `${sessionId}.summary.json` file to the rides directory. This repository reads them
 * back for the Rides history screen.
 *
 * We store the summary separately from the NDJSON sample stream so the history view
 * is fast (parse a tiny JSON file per ride, not megabytes of sample data). The full
 * sample file remains the canonical record for deep analysis / re-processing.
 */
@Serializable
data class StoredRideSummary(
    val sessionId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val totalDistanceMeters: Double,
    val movingTimeMs: Long,
    val totalElapsedMs: Long,
    val averageSpeedKph: Float,
    val maxSpeedKph: Float,
    val averagePowerWatts: Float,
    val maxPowerWatts: Float,
    val totalWorkKj: Float,
    val totalElevGainMeters: Float,
    val totalElevLossMeters: Float,
    val ftpWatts: Int,
    /** Post-ride HR enrichment from Health Connect (Samsung Health / Fit Band 3 etc.).
     *  Null until band sync completes within ~5 min after STOP RIDE. */
    val averageHrBpm: Int? = null,
    val maxHrBpm: Int? = null,
    val hrSampleCount: Int = 0,
    /** v0.8.2 — short readable label describing which sensor(s) produced
     *  the HR data: "from chest strap" / "from band (batched)" /
     *  "65% strap + 35% band". Null for legacy summaries computed pre-v0.8.2
     *  (UI shows "legacy" tag in that case). New rides populate this from
     *  ride NDJSON's per-tick `hrSource` field (BLE-first via the merger). */
    val hrSourceLabel: String? = null,
) {
    /** Rebuild a RideState skeleton from this summary so the summary screen can render
     *  historic rides with the same UI it uses for just-completed rides. */
    fun toRideState(): RideState = RideState(
        sessionId = sessionId,
        startedAtMs = startedAtMs,
        totalDistanceMeters = totalDistanceMeters,
        movingTimeMs = movingTimeMs,
        totalElapsedMs = totalElapsedMs,
        maxSpeedMs = maxSpeedKph / 3.6f,
        averagePowerWatts = averagePowerWatts,
        maxPowerWatts = maxPowerWatts,
        totalWorkKj = totalWorkKj,
        totalElevGainMeters = totalElevGainMeters,
        totalElevLossMeters = totalElevLossMeters,
        ftpWatts = ftpWatts,
    )

    companion object {
        fun from(state: RideState, endedAtMs: Long): StoredRideSummary = StoredRideSummary(
            sessionId = state.sessionId ?: "unknown",
            startedAtMs = state.startedAtMs ?: endedAtMs,
            endedAtMs = endedAtMs,
            totalDistanceMeters = state.totalDistanceMeters,
            movingTimeMs = state.movingTimeMs,
            totalElapsedMs = state.totalElapsedMs,
            averageSpeedKph = state.averageSpeedMovingKph,
            maxSpeedKph = state.maxSpeedMs * 3.6f,
            averagePowerWatts = state.averagePowerWatts,
            maxPowerWatts = state.maxPowerWatts,
            totalWorkKj = state.totalWorkKj,
            totalElevGainMeters = state.totalElevGainMeters,
            totalElevLossMeters = state.totalElevLossMeters,
            ftpWatts = state.ftpWatts,
        )
    }
}

class RideHistoryRepository(context: Context) {
    private val ridesDir = File(context.getExternalFilesDir(null), "rides").apply { mkdirs() }
    private val json = Json { encodeDefaults = false; prettyPrint = false; ignoreUnknownKeys = true }
    private val sampleJson = Json { ignoreUnknownKeys = true }

    fun save(summary: StoredRideSummary) {
        val file = File(ridesDir, "${summary.sessionId}.summary.json")
        file.writeText(json.encodeToString(StoredRideSummary.serializer(), summary))
    }

    fun load(sessionId: String): StoredRideSummary? {
        val file = File(ridesDir, "$sessionId.summary.json")
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(StoredRideSummary.serializer(), file.readText())
        }.getOrNull()
    }

    /**
     * Scans the rides directory for NDJSON files that have NO matching summary.json
     * (typically because the service was killed mid-ride before we could write one)
     * and rebuilds a summary by replaying the raw samples. The 2026-05-12 ride was
     * the trigger for adding this — 30 km of data sat orphaned on disk because
     * OxygenOS killed the foreground service after backgrounding.
     */
    /**
     * Rides whose `.active` marker still exists — process was killed mid-recording.
     * These are candidates for RESUME (v0.3.8) rather than silent finalization.
     * Returns sessionIds (caller can load the partial summary to display).
     *
     * Differs from recoverOrphanRides: this returns ACTIVE orphans (user intent
     * was still recording when killed), recoverOrphanRides handles any orphan
     * including ones from pre-v0.3.8 (no marker file).
     */
    fun findActiveOrphans(): List<StoredRideSummary> {
        val markers = ridesDir.listFiles { f -> f.name.endsWith(".active") } ?: return emptyList()
        return markers.mapNotNull { marker ->
            val sessionId = marker.nameWithoutExtension
            // The 30s checkpoint should have saved a summary by now. If there's
            // no summary at all (very short kill), the legacy orphan recovery
            // will rebuild from NDJSON when user picks End-and-save.
            load(sessionId)
        }
    }

    /** Discard an interrupted ride entirely — delete marker, NDJSON, summary. */
    fun discardActiveOrphan(sessionId: String) {
        runCatching {
            File(ridesDir, "$sessionId.active").delete()
            File(ridesDir, "$sessionId.ndjson").delete()
            File(ridesDir, "$sessionId.summary.json").delete()
        }
    }

    /** Finalize an interrupted ride — keep its data, just mark it ended.
     *  Deletes the .active marker so it's no longer offered for resume. */
    fun finalizeActiveOrphan(sessionId: String) {
        runCatching {
            File(ridesDir, "$sessionId.active").delete()
            // Rebuild summary from NDJSON to ensure totals are fresh
            val ndjson = File(ridesDir, "$sessionId.ndjson")
            if (ndjson.exists() && ndjson.length() >= 1024) {
                rebuildSummary(ndjson, sessionId)
            }
        }
    }

    fun recoverOrphanRides(): List<StoredRideSummary> {
        val recovered = mutableListOf<StoredRideSummary>()
        val ndjsonFiles = ridesDir.listFiles { f -> f.name.endsWith(".ndjson") } ?: return recovered
        ndjsonFiles.forEach { ndjson ->
            val sessionId = ndjson.nameWithoutExtension
            val summaryFile = File(ridesDir, "$sessionId.summary.json")
            if (summaryFile.exists()) return@forEach
            if (ndjson.length() < 1024) {
                // <1 KB — almost certainly an aborted test, not a real ride. Just delete.
                ndjson.delete()
                return@forEach
            }
            runCatching { rebuildSummary(ndjson, sessionId) }
                .onSuccess {
                    Log.d("URUJ-Recovery", "Recovered ride $sessionId: ${it.totalDistanceMeters/1000} km")
                    recovered += it
                }
                .onFailure { Log.w("URUJ-Recovery", "Failed to recover $sessionId", it) }
        }
        return recovered
    }

    private fun rebuildSummary(ndjson: File, sessionId: String): StoredRideSummary {
        var first: RideSample? = null
        var last: RideSample? = null
        var totalDistance = 0.0
        var movingTimeMs = 0L
        var totalElapsedMs = 0L
        var maxSpeedMs = 0f
        var totalElevGainM = 0.0
        var totalElevLossM = 0.0
        var prev: RideSample? = null
        var sampleCount = 0

        ndjson.bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val sample = runCatching {
                    sampleJson.decodeFromString(RideSample.serializer(), line)
                }.getOrNull() ?: return@forEach
                sampleCount++
                if (first == null) first = sample
                last = sample
                if (!sample.isPaused && sample.speedMetersPerSecond > maxSpeedMs) {
                    maxSpeedMs = sample.speedMetersPerSecond
                }
                val previous = prev
                if (previous != null) {
                    val dtMs = sample.timestampMs - previous.timestampMs
                    if (dtMs in 0..5_000) {
                        if (!sample.isPaused) movingTimeMs += dtMs
                    }
                    if (!sample.isPaused && sample.horizontalAccuracyMeters <= 25f) {
                        val d = haversineMeters(
                            previous.latitude, previous.longitude,
                            sample.latitude, sample.longitude,
                        )
                        if (d in 1.0..100.0) totalDistance += d
                    }
                }
                prev = sample
            }
        }

        val firstSnap = first ?: throw IllegalStateException("Empty NDJSON")
        val lastSnap = last ?: throw IllegalStateException("Empty NDJSON")
        totalElapsedMs = lastSnap.timestampMs - firstSnap.timestampMs

        val avgSpeedKph = if (movingTimeMs > 0) {
            (totalDistance / (movingTimeMs / 1000.0) * 3.6).toFloat()
        } else 0f

        val summary = StoredRideSummary(
            sessionId = sessionId,
            startedAtMs = firstSnap.timestampMs,
            endedAtMs = lastSnap.timestampMs,
            totalDistanceMeters = totalDistance,
            movingTimeMs = movingTimeMs,
            totalElapsedMs = totalElapsedMs,
            averageSpeedKph = avgSpeedKph,
            maxSpeedKph = maxSpeedMs * 3.6f,
            // Power/elev fields zeroed — we don't replay the power model here (would need
            // profile + DEM + barometer state). Display will show "—" for those fields,
            // marked as a recovered ride. Full re-processing is a separate offline tool.
            averagePowerWatts = 0f,
            maxPowerWatts = 0f,
            totalWorkKj = 0f,
            totalElevGainMeters = 0f,
            totalElevLossMeters = 0f,
            ftpWatts = 200,
        )
        save(summary)
        return summary
    }

    /** Returns past rides newest-first. Self-heals any "unknown" stub files left by a
     *  prior race-condition bug — silently deletes them so the history view stays clean. */
    fun listAll(): List<StoredRideSummary> {
        val files = ridesDir.listFiles { f -> f.name.endsWith(".summary.json") } ?: return emptyList()
        val summaries = mutableListOf<StoredRideSummary>()
        files.forEach { file ->
            val summary = runCatching {
                json.decodeFromString(StoredRideSummary.serializer(), file.readText())
            }.getOrNull()
            if (summary == null || summary.sessionId == "unknown") {
                runCatching { file.delete() }
                File(ridesDir, "unknown.ndjson").takeIf { it.exists() }
                    ?.let { runCatching { it.delete() } }
            } else {
                summaries += summary
            }
        }
        return summaries.sortedByDescending { it.startedAtMs }
    }

    fun delete(sessionId: String) {
        File(ridesDir, "$sessionId.summary.json").delete()
        File(ridesDir, "$sessionId.ndjson").delete()
    }
}
