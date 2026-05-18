package com.uruj.data

import android.content.Context
import android.util.Log
import com.uruj.power.HrvCalculator
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * v0.7.0 — Reads the daily-rotated NDJSON files written by
 * ContinuousBiometricRecorder and exposes time-windowed HRV computations
 * on top.
 *
 * Read pattern: lazy / on-demand. Each call walks only the files that
 * overlap the requested window.
 *
 * v0.7.0 follow-up fix: reconstructs ACTUAL beat timestamps from each
 * ContinuousSample (`timestampMs` + `rrIntervalsMs` array) instead of
 * flat-mapping RR values. This lets HrvCalculator verify consecutiveness
 * via the timestamp-aware filter — critical for correct RMSSD over long
 * windows where BLE gaps would otherwise corrupt the diff signal.
 *
 * Standard window: 5 minutes (Task Force 1996 short-term HRV convention).
 */
class ContinuousBiometricRepository(context: Context) {

    private val baseDir = File(
        context.applicationContext.getExternalFilesDir(null),
        "continuous",
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val hrvCalc = HrvCalculator()

    /**
     * Returns all continuous samples that fall within the time window.
     * Walks only the daily files that overlap; skips others.
     */
    fun samplesForWindow(start: Instant, end: Instant): List<ContinuousSample> {
        if (!baseDir.exists()) return emptyList()
        val zone = ZoneId.systemDefault()
        val firstDay = start.atZone(zone).toLocalDate()
        val lastDay = end.atZone(zone).toLocalDate()
        val days = generateDayRange(firstDay, lastDay)
        val results = mutableListOf<ContinuousSample>()
        for (day in days) {
            val file = File(baseDir, "$day.ndjson")
            if (!file.exists()) continue
            runCatching {
                file.useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        val sample = runCatching {
                            json.decodeFromString(ContinuousSample.serializer(), line)
                        }.getOrNull() ?: continue
                        val t = Instant.ofEpochMilli(sample.timestampMs)
                        if (!t.isBefore(start) && !t.isAfter(end)) {
                            results += sample
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "[24/7] read failed for $day", it) }
        }
        return results
    }

    /**
     * Reconstructs the actual beat timestamps from a list of ContinuousSamples.
     * Per Bluetooth HRP spec, RR intervals in each notification are ordered
     * oldest-to-newest. The latest beat is at sample.timestampMs. Walking the
     * RR array in reverse and subtracting RR values gives us the timestamp of
     * each earlier beat.
     *
     * Beats are returned sorted by timestamp.
     *
     * Why this matters: HrvCalculator needs timestamps to verify that two RR
     * values in its consecutive-pair diff are TRULY consecutive heartbeats.
     * Without timestamps, BLE gaps + sleep-stage transitions look like
     * tiny-delta consecutive pairs and corrupt RMSSD.
     */
    fun samplesToBeats(samples: List<ContinuousSample>): List<HrvCalculator.Beat> {
        val beats = mutableListOf<HrvCalculator.Beat>()
        for (sample in samples) {
            var t = sample.timestampMs
            // RR ordered oldest-to-newest. Walk reverse to assign timestamps
            // working backwards from the sample's arrival time.
            for (i in sample.rrIntervalsMs.indices.reversed()) {
                val rr = sample.rrIntervalsMs[i]
                if (rr <= 0) continue
                beats.add(HrvCalculator.Beat(timestampMs = t, rrMs = rr))
                t -= rr
            }
        }
        return beats.sortedBy { it.timestampMs }
    }

    /**
     * Compute HRV across a specific known sleep window (e.g. from Samsung
     * Health SleepSessionRecord). Sleep windows give the cleanest HRV
     * because resting parasympathetic dominance is most pronounced.
     *
     * v0.7.0 follow-up: now uses WINDOWED 5-min RMSSD with median aggregation
     * (research standard). Returns null if fewer than 3 valid windows.
     */
    fun computeHrvForWindow(start: Instant, end: Instant): HrvCalculator.TimeDomainHrv? {
        val samples = samplesForWindow(start, end)
        val beats = samplesToBeats(samples)
        Log.d(TAG, "[hrv] window $start..$end → ${samples.size} samples, ${beats.size} beats")
        return hrvCalc.computeWindowed(beats)
    }

    /**
     * Rolling 24-hour HRV as a fallback baseline when no sleep window is
     * available. Still uses windowed analysis — mixes sleep + daytime data
     * but each 5-min window is computed independently.
     */
    fun recent24hHrv(): HrvCalculator.TimeDomainHrv? {
        val now = Instant.now()
        return computeHrvForWindow(now.minus(Duration.ofHours(24)), now)
    }

    /**
     * Per-night HRV history. Reads each day's NDJSON and computes one
     * RMSSD per overnight sleep window. Used for 7d baseline + trend charts.
     *
     * Approximate sleep window: previous day's 22:00 to this day's 09:00.
     * This is a heuristic — when v0.7.x adds Samsung sleep-window lookup,
     * this becomes more precise.
     */
    fun dailyOvernightHrvHistory(days: Int): List<DailyHrv> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val results = mutableListOf<DailyHrv>()
        for (offset in 0 until days) {
            val day = today.minusDays(offset.toLong())
            val nightStart = day.minusDays(1).atTime(22, 0).atZone(zone).toInstant()
            val nightEnd = day.atTime(9, 0).atZone(zone).toInstant()
            val hrv = computeHrvForWindow(nightStart, nightEnd) ?: continue
            results += DailyHrv(date = day, hrv = hrv)
        }
        return results
    }

    /**
     * Counts how many of the last `days` days have a valid (non-null)
     * overnight HRV reading. Used by Readiness to switch between absolute-
     * tier scoring (days 1-6) and ratio-based scoring (day 7+).
     */
    fun daysWithOvernightHrvIn(days: Int): Int {
        return dailyOvernightHrvHistory(days).size
    }

    /** Returns the dates that have continuous-monitoring data. Newest first. */
    fun availableDays(): List<LocalDate> {
        if (!baseDir.exists()) return emptyList()
        val files = baseDir.listFiles { f -> f.name.endsWith(".ndjson") } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching { LocalDate.parse(f.nameWithoutExtension) }.getOrNull()
        }.sortedDescending()
    }

    private fun generateDayRange(first: LocalDate, last: LocalDate): List<LocalDate> {
        val (a, b) = if (first.isBefore(last)) first to last else last to first
        val days = mutableListOf<LocalDate>()
        var cursor = a
        while (!cursor.isAfter(b)) {
            days += cursor
            cursor = cursor.plusDays(1)
        }
        return days
    }

    data class DailyHrv(
        val date: LocalDate,
        val hrv: HrvCalculator.TimeDomainHrv,
    )

    companion object {
        private const val TAG = "URUJ-Continuous-Repo"
    }
}
