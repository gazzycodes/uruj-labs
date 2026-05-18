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
 * overlap the requested window. NDJSON is human-friendly text — we don't
 * need a DB or indexing for a few MB/day. If volume grows beyond 90 days
 * × 5MB = 450MB, consider sqlite-FTS or parquet.
 *
 * Stable, simple API:
 *   - `samplesForWindow(start, end)` — all samples in time range
 *   - `rrIntervalsForWindow(start, end)` — flatMap of RR intervals (the
 *     thing HRV calc actually wants)
 *   - `overnightHrv(sleepStart, sleepEnd)` — compute RMSSD/SDNN/pNN50 from
 *     a known sleep window
 *   - `recent24hHrv()` — rolling 24h window as a fallback baseline
 *   - `dailyHrvHistory(days)` — for trend display (one HRV value per day)
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
     * Returns flat list of every RR interval in the window — what
     * HrvCalculator.compute() consumes directly.
     */
    fun rrIntervalsForWindow(start: Instant, end: Instant): List<Int> {
        return samplesForWindow(start, end).flatMap { it.rrIntervalsMs }
    }

    /**
     * Compute HRV across a specific known sleep window (e.g. from Samsung
     * Health SleepSessionRecord). Sleep windows give the cleanest HRV
     * because resting parasympathetic dominance is most pronounced.
     */
    fun computeHrvForWindow(start: Instant, end: Instant): HrvCalculator.TimeDomainHrv? {
        val rr = rrIntervalsForWindow(start, end)
        Log.d(TAG, "[hrv] window $start..$end → ${rr.size} RR intervals")
        return hrvCalc.compute(rr)
    }

    /**
     * Rolling 24-hour HRV as a fallback baseline when no sleep window is
     * available. Mixes sleep + daytime data — less clean than sleep-window
     * but still a valid signal (lower than pure-sleep HRV).
     */
    fun recent24hHrv(): HrvCalculator.TimeDomainHrv? {
        val now = Instant.now()
        return computeHrvForWindow(now.minus(Duration.ofHours(24)), now)
    }

    /**
     * Per-night HRV history. Reads each day's NDJSON and computes one
     * RMSSD per day (overnight window = last night's midnight to 8am
     * approximation). Used for 7d baseline + trend charts.
     */
    fun dailyOvernightHrvHistory(days: Int): List<DailyHrv> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val results = mutableListOf<DailyHrv>()
        for (offset in 0 until days) {
            val day = today.minusDays(offset.toLong())
            // Approximate overnight window: previous day's 22:00 to this day's 09:00
            val nightStart = day.minusDays(1).atTime(22, 0).atZone(zone).toInstant()
            val nightEnd = day.atTime(9, 0).atZone(zone).toInstant()
            val hrv = computeHrvForWindow(nightStart, nightEnd) ?: continue
            results += DailyHrv(date = day, hrv = hrv)
        }
        return results
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
