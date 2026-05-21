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
    private val freqCalc = com.uruj.power.FrequencyDomainCalculator()

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
     * v0.7.7 — extract `(timestamp, bpm)` pairs from the 24/7 NDJSON for the
     * requested window. Drop samples with bpm ≤ 0 (BLE handshake noise).
     *
     * Used by `HrRecoveryCalculator` + `SleepingRhrCalculator` as the
     * higher-resolution alternative to HC HeartRateRecord batches. Per-second
     * cadence (vs HC's ~30 sec batches) gives noticeably tighter HRR1 numbers
     * because the post-effort window has 30-60 samples instead of 2-3.
     */
    fun hrSamplesForWindow(start: Instant, end: Instant): List<Pair<Instant, Int>> {
        return samplesForWindow(start, end)
            .filter { it.bpm > 0 }
            .map { Instant.ofEpochMilli(it.timestampMs) to it.bpm }
            .sortedBy { it.first }
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
        val result = hrvCalc.computeWindowed(beats)
        if (result == null) {
            // Fallback: try a more lenient flat-list compute. Better to surface
            // a less-precise number than to show "no data" when we have 40k+
            // beats sitting on disk. Logged so we can tell which path fired.
            val flat = hrvCalc.compute(beats)
            Log.d(
                TAG,
                "[hrv] window $start..$end → ${samples.size} samples, ${beats.size} beats — " +
                    "windowed=null, flatFallback=${flat?.rmssdMs?.let { "%.1f".format(it) } ?: "null"}",
            )
            return flat
        }
        Log.d(
            TAG,
            "[hrv] window $start..$end → ${samples.size} samples, ${beats.size} beats, " +
                "windows=${result.windowCount}, rmssd=${"%.1f".format(result.rmssdMs)} ms",
        )
        return result
    }

    /**
     * v0.9.25 → v0.9.26 — frequency-domain + non-linear HRV via 5-min
     * windowing + median aggregation (Task Force 1996 standard, matches
     * existing [computeHrvForWindow] pattern). Same NDJSON read.
     *
     * v0.9.25 had a methodology bug: computed on full overnight as one
     * block → physiologically implausible numbers (LF/HF=22 in field
     * test) + O(N²) sample entropy on 59k beats = 5 min compute. v0.9.26
     * windowing fixes both math correctness AND performance simultaneously
     * (per-window N ≈ 300 beats → ~200ms total).
     */
    fun computeFrequencyDomainForWindow(
        start: Instant,
        end: Instant,
    ): com.uruj.power.FrequencyDomainCalculator.FrequencyDomainHrv? {
        val samples = samplesForWindow(start, end)
        val beats = samplesToBeats(samples)
        val result = freqCalc.computeWindowed(beats)
        Log.d(
            TAG,
            "[freq-domain] window $start..$end → ${samples.size} samples, ${beats.size} beats — " +
                "result=${if (result == null) "null (insufficient)" else "windows=${result.windowCount} lf/hf=${"%.2f".format(result.lfHfRatio ?: -1f)} dfa=${"%.2f".format(result.dfaAlpha1 ?: -1f)} sd1=${"%.1f".format(result.sd1Ms ?: -1f)}"}",
        )
        return result
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
     * Per-night HRV history sliced by ACTUAL Samsung sleep windows. Caller
     * supplies the sessions list (typically via `LastSleepReader.listLastNDays`)
     * which gives us the real wake-to-sleep boundaries instead of the
     * hardcoded 22:00-09:00 heuristic.
     *
     * Why this matters: the heuristic window was always wider than real
     * sleep, so it included pre-sleep awake time + post-wake awake time,
     * both of which have lower HRV than deep sleep. Result was a per-night
     * RMSSD lower than the Bio Lab Autonomic card (which always used the
     * Samsung window). v0.7.4 follow-up fix.
     *
     * Returns one DailyHrv per sleep session that produced a valid HRV
     * computation (≥2 valid 5-min windows or fallback flat compute).
     * Sessions are bucketed to the LocalDate of their `endedAt` — that's
     * the calendar date the rider woke up, which is how URUJ talks about
     * "tonight's sleep" elsewhere.
     */
    fun dailyOvernightHrvHistoryFromSessions(
        sessions: List<com.uruj.data.LastSleepReader.Result>,
    ): List<DailyHrv> {
        val zone = ZoneId.systemDefault()
        val results = mutableListOf<DailyHrv>()
        for (session in sessions) {
            val hrv = computeHrvForWindow(session.startedAt, session.endedAt) ?: continue
            val day = session.endedAt.atZone(zone).toLocalDate()
            results += DailyHrv(date = day, hrv = hrv)
        }
        return results.sortedByDescending { it.date }
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
