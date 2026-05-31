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
     *
     * v0.9.53 — per-day parsed-samples cache via [NdjsonDayCache]. Past
     * days are immutable on disk (the file stopped being appended at
     * midnight), so once parsed they stay cached for the process
     * lifetime (LRU-capped). Today's file grows as the strap streams new
     * samples; we detect growth via `file.lastModified()` and re-parse
     * on mtime advance. Net effect: a 30-day query parses each day at
     * most once per process session.
     *
     * Math + filter logic unchanged. Same per-line decode, same time-
     * window inclusion check. Identical output to pre-v0.9.53 path —
     * cache is a pure perf optimization, never affects values.
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
            val daySamples = NdjsonDayCache.get(day, file)
                ?: parseDayFile(day, file).also { NdjsonDayCache.put(day, file, it) }
            for (sample in daySamples) {
                val t = Instant.ofEpochMilli(sample.timestampMs)
                if (!t.isBefore(start) && !t.isAfter(end)) {
                    results += sample
                }
            }
        }
        return results
    }

    /**
     * v0.9.53 — parse a single NDJSON day file into the full sample list.
     * Same logic as the pre-v0.9.53 inline loop, extracted so the
     * cache-miss path stays tidy. Errors are logged + the day returns
     * empty (matching the prior runCatching+onFailure semantic).
     */
    private fun parseDayFile(day: LocalDate, file: File): List<ContinuousSample> {
        val samples = mutableListOf<ContinuousSample>()
        runCatching {
            file.useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val sample = runCatching {
                        json.decodeFromString(ContinuousSample.serializer(), line)
                    }.getOrNull() ?: continue
                    samples += sample
                }
            }
        }.onFailure { Log.w(TAG, "[24/7] read failed for $day", it) }
        return samples
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
     * v0.9.68 — MEMORY-SAFE bounded strap HR read.
     *
     * Returns `(timestamp, bpm)` pairs that fall inside ANY of the supplied
     * windows, sorted by timestamp, bpm > 0. This is the bounded replacement
     * for `hrSamplesForWindow(monthAgo, now)` when the consumer only ever
     * filters the result to specific windows anyway.
     *
     * WHY THIS EXISTS
     * ===============
     * `hrSamplesForWindow(30d)` materialized the ENTIRE 30-day per-second strap
     * stream — `samplesForWindow` builds a `List<ContinuousSample>` of ~2.6M
     * elements, then `.filter`/`.map`/`.sortedBy` build three more full-size
     * collections on top (~145 MB just for the returned `Pair` list, plus the
     * underlying parsed `ContinuousSample` objects). On the 384 MB heap ceiling
     * that is a guaranteed `OutOfMemoryError` once enough days accumulate.
     * Confirmed by Crashlytics on v0.9.67 (issues c37bfecc + 45de1480, 23
     * crashes, 0% crash-free) — the fatal allocation is the `sortedWith`
     * (`Arrays.copyOf`, 8.6 MB) / the `TuplesKt.to` map inside this exact call,
     * driven from `BioLabRepository.snapshot`. See
     * [[reference_perf_architecture_findings_2026_05_27]] + #175.
     *
     * MATH INVARIANT (identical output for the real consumers)
     * ========================================================
     * The two consumers of the 30d strap list both filter internally:
     *   • [com.uruj.power.SleepingRhrCalculator] → samples inside sleep windows
     *   • [com.uruj.power.HrRecoveryCalculator]  → samples inside
     *     `[sessionEnd - 30min, sessionEnd + 180s]`
     * Passing the UNION of those windows here returns a subset that, when the
     * consumer re-applies its own per-window filter, is byte-identical to
     * filtering the full-range result — every sample either consumer could
     * touch is present; samples outside every window were never read by anyone.
     * A sample landing in two overlapping windows is emitted exactly once (the
     * inner loop breaks on first match) so no per-window count is inflated.
     *
     * MEMORY PROFILE
     * ==============
     * Peak transient = one day's parsed `ContinuousSample` list (~110k objects,
     * cached or GC'd as we advance) + the accumulating output (only in-window
     * samples — a few sleep nights + per-ride recovery windows ≈ tens of MB at
     * most, vs ~250-400 MB for the full 30d). Same [NdjsonDayCache]-backed
     * per-day parse as [samplesForWindow]; values, filter logic, and provenance
     * are unchanged.
     */
    fun hrSamplesWithinWindows(windows: List<Pair<Instant, Instant>>): List<Pair<Instant, Int>> {
        if (windows.isEmpty() || !baseDir.exists()) return emptyList()
        val zone = ZoneId.systemDefault()
        val earliest = windows.minOf { it.first }
        val latest = windows.maxOf { it.second }
        val days = generateDayRange(
            earliest.atZone(zone).toLocalDate(),
            latest.atZone(zone).toLocalDate(),
        )
        // Pre-extract bounds as primitive epoch-ms so the per-sample membership
        // check avoids allocating an Instant for every one of the (potentially
        // millions of) samples we SCAN — we only allocate an Instant for the
        // ones we actually EMIT.
        val winStartMs = LongArray(windows.size) { windows[it].first.toEpochMilli() }
        val winEndMs = LongArray(windows.size) { windows[it].second.toEpochMilli() }
        val out = ArrayList<Pair<Instant, Int>>()
        for (day in days) {
            val file = File(baseDir, "$day.ndjson")
            if (!file.exists()) continue
            val daySamples = NdjsonDayCache.get(day, file)
                ?: parseDayFile(day, file).also { NdjsonDayCache.put(day, file, it) }
            for (sample in daySamples) {
                if (sample.bpm <= 0) continue
                val ts = sample.timestampMs
                var inWindow = false
                for (i in winStartMs.indices) {
                    if (ts >= winStartMs[i] && ts <= winEndMs[i]) {
                        inWindow = true
                        break
                    }
                }
                if (inWindow) out += Instant.ofEpochMilli(ts) to sample.bpm
            }
        }
        out.sortBy { it.first }
        return out
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
     *
     * v0.9.48 — stage-aware + sliding-window upgrade. When [stages] is
     * non-empty, AWAKE periods within the sleep window are EXCLUDED from
     * computation (the biggest pre-v0.9.48 bias — awake transitions
     * within the sleep window pulled RMSSD down). 50% window overlap +
     * lowered min-diffs threshold sweeps ~80% of night vs the previous
     * ~10% sampling. Per-stage RMSSD breakdown returned for transparency.
     *
     * Falls back gracefully to pre-v0.9.48 method when stages list is
     * empty (HC version without detailed staging, permission missing,
     * historical compute pre-stages persistence). Snapshot methodology
     * version field tagged accordingly so the audit trail is honest.
     */
    fun computeHrvForWindow(
        start: Instant,
        end: Instant,
        stages: List<com.uruj.data.SleepStageSegment> = emptyList(),
    ): HrvCalculator.TimeDomainHrv? {
        val samples = samplesForWindow(start, end)
        val beats = samplesToBeats(samples)
        val stageFilter = if (stages.isNotEmpty()) HrvCalculator.StageFilter(stages) else null
        // v0.9.48 lab-grade defaults: sliding 50% overlap, min 25 diffs/window
        // (down from 30 — sliding mode has more windows so median tolerates
        // looser per-window threshold), per-stage breakdown enabled when
        // stage filter present.
        val result = if (stageFilter != null) {
            hrvCalc.computeWindowed(
                beats = beats,
                minDiffsPerWindow = 25,
                slidingOverlap = 0.5f,
                stageFilter = stageFilter,
                perStageBreakdown = true,
            )
        } else {
            // Pre-v0.9.48 method when no stages available
            hrvCalc.computeWindowed(beats)
        }
        if (result == null) {
            // Fallback: try a more lenient flat-list compute. Better to surface
            // a less-precise number than to show "no data" when we have 40k+
            // beats sitting on disk. Logged so we can tell which path fired.
            val flat = hrvCalc.compute(beats)
            Log.d(
                TAG,
                "[hrv] window $start..$end → ${samples.size} samples, ${beats.size} beats — " +
                    "windowed=null, flatFallback=${flat?.rmssdMs?.let { "%.1f".format(it) } ?: "null"}, " +
                    "stages=${stages.size}",
            )
            return flat
        }
        val stageInfo = if (result.perStageRmssdMs.isNotEmpty()) {
            " · per-stage=" + result.perStageRmssdMs.entries.joinToString(",") {
                "${it.key}=${"%.1f".format(it.value)}ms"
            }
        } else ""
        Log.d(
            TAG,
            "[hrv v0.9.48] window $start..$end → ${samples.size} samples, ${beats.size} beats, " +
                "stages=${stages.size}, windows=${result.windowCount}, " +
                "rmssd=${"%.1f".format(result.rmssdMs)} ms$stageInfo",
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

/**
 * v0.9.53 — process-scoped per-day NDJSON sample cache.
 *
 * Why this exists
 * ===============
 * Pre-v0.9.53, every BioLab refresh + every Readiness compute called
 * [ContinuousBiometricRepository.samplesForWindow] which iterated the
 * relevant daily `.ndjson` files and re-parsed every line. For 30-day
 * queries that's 30 × ~8MB = ~240MB of disk I/O + JSON deserialization
 * on every single refresh. Verified bottleneck in the 2026-05-27 perf
 * audit ([[reference_perf_architecture_findings_2026_05_27]]).
 *
 * Cache design
 * ============
 * - **Keyed by [LocalDate]**: matches the file naming convention
 *   (`YYYY-MM-DD.ndjson`). Each entry holds the FULL parsed sample list
 *   for that day plus the file's `lastModified` snapshot at parse time.
 *
 * - **Past days are immutable**: the strap appends samples to the
 *   "today" file; at midnight a new file is opened. So any file whose
 *   date is strictly before `LocalDate.now()` is read-only forever from
 *   the cache's perspective. We skip the mtime check for these and
 *   return cached samples directly.
 *
 * - **Today's file uses mtime invalidation**: as the strap streams new
 *   samples, the file's `lastModified` advances. The cache compares the
 *   stored mtime against the file's current mtime; on advance, the
 *   entry is treated as stale and the caller re-parses + re-caches.
 *   This guarantees today's NDJSON-derived computes always see freshly-
 *   appended samples without forcing past-day re-parses.
 *
 * - **LRU eviction cap**: [MAX_DAYS] entries. When putting a new key
 *   into a full cache, the oldest [LocalDate] is evicted. 10 days at
 *   ~8MB each = ~80MB max in process memory. Worst-case 30d query
 *   parses 30 files cold, caches the last 10, then subsequent 30d
 *   queries fetch 10 from cache + parse 20 from disk. Still a major
 *   win over the previous "always parse 30".
 *
 * - **Thread safety**: backed by [ConcurrentHashMap]. Two concurrent
 *   coroutines that miss for the same date will both parse and one
 *   will win the `put`; the worker that loses its parse is wasted but
 *   the result is identical (same bytes on disk).
 *
 * Math correctness
 * ================
 * The cache holds raw [ContinuousSample] objects — exactly the
 * deserialized form of NDJSON lines. Every downstream consumer
 * (`hrSamplesForWindow`, `samplesToBeats`, `computeHrvForWindow`,
 * `computeFrequencyDomainForWindow`, `dailyOvernightHrvHistoryFromSessions`)
 * receives identical data to the pre-cache path. **Lab-grade rule
 * preserved**: cache is a pure perf optimization; values, methodology,
 * and provenance are never altered.
 *
 * Lifecycle
 * =========
 * Statics live for the process lifetime. On process death (Android
 * memory pressure, force-stop, reboot) the cache is gone and the next
 * read re-parses from disk. NDJSON files themselves are the durable
 * source of truth; cache is purely an in-memory accelerator.
 */
private object NdjsonDayCache {

    // v0.9.59 — reduced from 10 → 5. The first Crashlytics-captured OOM
    // (2026-05-28 15:51:14) was at the 384 MB heap ceiling. 10 cached days
    // at ~8 MB each = up to ~80 MB heap residency. 5 days = ~40 MB ceiling.
    // Last 5 days still cover the typical tab-switch + Readiness 7-night
    // read path (today + 4 past days from cache, plus today's fresh
    // computation) — anything older falls through to disk parse, same
    // wallclock cost as before. Net: 40 MB heap headroom restored.
    private const val MAX_DAYS = 5

    private data class CachedDay(
        val samples: List<com.uruj.data.ContinuousSample>,
        val fileLastModified: Long,
    )

    private val data = java.util.concurrent.ConcurrentHashMap<LocalDate, CachedDay>()

    fun get(date: LocalDate, file: java.io.File): List<com.uruj.data.ContinuousSample>? {
        val entry = data[date] ?: return null
        // Past-day files are immutable. The strap stopped writing to
        // them at the midnight boundary; no possible drift.
        if (date.isBefore(LocalDate.now(java.time.ZoneId.systemDefault()))) {
            return entry.samples
        }
        // Today's file grows as samples arrive. Compare mtime — if the
        // file has advanced since we cached, the entry is stale.
        return if (file.lastModified() == entry.fileLastModified) entry.samples else null
    }

    fun put(date: LocalDate, file: java.io.File, samples: List<com.uruj.data.ContinuousSample>) {
        // LRU evict the oldest entry when at capacity AND we're inserting
        // a new key (re-writing an existing key is free — same slot).
        if (data.size >= MAX_DAYS && !data.containsKey(date)) {
            data.keys.minOrNull()?.let { data.remove(it) }
        }
        data[date] = CachedDay(samples, file.lastModified())
    }

    /** Test-only — clear the cache. */
    @Suppress("unused")
    internal fun resetForTesting() {
        data.clear()
    }
}
