package com.uruj.ui.summary

import android.app.Application
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uruj.data.NdjsonRideReader
import com.uruj.data.RideHistoryRepository
import com.uruj.data.RideHrSample
import com.uruj.data.RiderProfileStore
import com.uruj.data.StoredRideSummary
import com.uruj.domain.SensorSource
import com.uruj.power.TimeInZoneCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Drives the post-ride summary screen. After the ride ends, polls Health Connect for
 * up to 5 minutes looking for HR records that match the ride's time window — Samsung
 * Health typically writes HR to Health Connect within 30s of a workout ending on the
 * band, but can take longer. Once HR data lands, the summary file is enriched in
 * place and the UI updates automatically.
 *
 * For users WITHOUT a band, this just expires silently — no HR card ever appears,
 * no error, no spam. Tier-0 users get URUJ-only data, Tier-1+ users get the bonus.
 */
class RideSummaryViewModel(application: Application) : AndroidViewModel(application) {

    private val historyRepo = RideHistoryRepository(application)
    private val profileStore = RiderProfileStore(application)
    private val zoneCalc = TimeInZoneCalculator()
    // v0.8.2 — read source-tagged HR from ride NDJSON (strap-first when
    // strap was streaming during the ride) before falling back to HC.
    private val ndjsonReader = NdjsonRideReader(application)

    /**
     * v0.8.2 — per-ride HR source breakdown. Populated whenever the ride
     * NDJSON has usable HR data; null when we fell back to HC entirely.
     * Drives the source-label badge on TIZ + ride-stats cards.
     */
    private val _hrSourceBreakdown = MutableStateFlow<Map<SensorSource, Int>>(emptyMap())
    val hrSourceBreakdown: StateFlow<Map<SensorSource, Int>> = _hrSourceBreakdown.asStateFlow()

    private val _hrEnrichment = MutableStateFlow<HrEnrichmentState>(HrEnrichmentState.Idle)
    val hrEnrichment: StateFlow<HrEnrichmentState> = _hrEnrichment.asStateFlow()

    /**
     * Time-in-zone for the ride. Null until HC HR data is fetched (post-batch-sync
     * from Samsung typically 5-30 min after workout end). Same %max-HR thresholds
     * as route map polyline coloring so visualization + analysis agree.
     */
    private val _timeInZone = MutableStateFlow<TimeInZoneCalculator.Result?>(null)
    val timeInZone: StateFlow<TimeInZoneCalculator.Result?> = _timeInZone.asStateFlow()

    private var pollingJob: Job? = null

    /**
     * v0.8.6 — tracks the in-flight enrichment coroutine + the session it
     * was started for. Cancelled when a new `startHrEnrichment` is called
     * with a different session, preventing the prior session's slow HC
     * polling loop from writing state for a ride we're no longer viewing.
     *
     * Fixes the bug surfaced after v0.8.5 merge: short test ride →
     * fell back to HC polling (correct fallback for sub-30-sample NDJSON)
     * → user navigated to past 37.78km ride → NDJSON path correctly
     * loaded strap data → test ride's polling loop's next 15s tick
     * overwrote the past ride's Done state with stale Polling state for
     * the wrong sessionId.
     */
    private var enrichmentJob: Job? = null
    private var currentEnrichmentSessionId: String? = null

    fun startHrEnrichment(sessionId: String, startedAtMs: Long, endedAtMs: Long) {
        // v0.8.6 — cancel any prior in-flight enrichment. RideSummaryViewModel
        // is Activity-scoped (singleton), so without explicit cancellation
        // a polling loop from a prior ride summary survives navigation and
        // writes state for the wrong sessionId.
        if (currentEnrichmentSessionId != sessionId) {
            enrichmentJob?.cancel()
            pollingJob?.cancel()
            pollingJob = null
            // Reset displayed state so prior session's data doesn't bleed
            // through visually while the new session's enrichment runs.
            _hrEnrichment.value = HrEnrichmentState.Idle
            _hrSourceBreakdown.value = emptyMap()
            _timeInZone.value = null
        }
        currentEnrichmentSessionId = sessionId

        // v0.8.2 — first try the ride NDJSON. If the strap was streaming
        // during the ride, RideRecorderService wrote source-tagged hrBpm
        // per GPS tick. NDJSON-first means higher-precision strap data
        // wins; HC stays as fallback for pre-strap rides or strap-off
        // segments.
        enrichmentJob = viewModelScope.launch {
            tryEnrichFromNdjsonThenHc(sessionId, startedAtMs, endedAtMs)
        }
    }

    private suspend fun tryEnrichFromNdjsonThenHc(
        sessionId: String,
        startedAtMs: Long,
        endedAtMs: Long,
    ) {
        val ndjsonHr = withContext(Dispatchers.IO) { ndjsonReader.readHrSamples(sessionId) }
        if (ndjsonHr.size >= NdjsonRideReader.MIN_USEFUL_HR_SAMPLES) {
            // Strap (and/or merged BLE+HC during ride) covered this ride well.
            // Compute everything from NDJSON; skip HC polling entirely.
            applyHrEnrichment(sessionId, endedAtMs, ndjsonHr)
            Log.d(
                "URUJ-Summary",
                "Ride $sessionId enriched from NDJSON: ${ndjsonHr.size} HR samples, " +
                    "sources=${ndjsonHr.groupingBy { it.source }.eachCount()}",
            )
            return
        }

        // v0.8.6 — for VERY short rides (<60s), skip HC polling entirely.
        // Samsung's batch-sync window is 5-30 min and only fires for rides
        // it tracked as workouts; sub-60-second rides won't produce useful
        // HC data even after the 5-minute polling window. Polling for them
        // shows misleading "Syncing from HC..." UX (especially galling
        // when the rider HAS a strap paired + streaming live — they correctly
        // expect strap-precedence) AND creates the state-race window where
        // navigating away mid-poll causes a stale write to a different
        // session's summary. NotAvailable hides the HR card entirely; for
        // very short test rides that's the honest answer.
        val rideDurationMs = endedAtMs - startedAtMs
        if (rideDurationMs < SHORT_RIDE_HC_SKIP_MS) {
            Log.d(
                "URUJ-Summary",
                "Ride $sessionId — ${rideDurationMs}ms is too short for HR enrichment " +
                    "(${ndjsonHr.size} NDJSON samples). Skipping HC polling.",
            )
            _hrEnrichment.value = HrEnrichmentState.NotAvailable
            return
        }

        // v0.8.6 — for rides with SOME strap samples but below the analysis
        // threshold, surface what we have rather than hiding it under HC
        // polling. Honors strap-precedence: even partial strap data > HC
        // data for the same window (strap is per-second, HC is batched).
        // Only fall back to HC if we have ZERO usable strap samples (most
        // commonly: no strap was paired for this ride).
        val hasAnyStrapSamples = ndjsonHr.any { it.source == com.uruj.domain.SensorSource.STRAP }
        if (hasAnyStrapSamples) {
            Log.d(
                "URUJ-Summary",
                "Ride $sessionId — ${ndjsonHr.size} strap NDJSON samples (<min ${NdjsonRideReader.MIN_USEFUL_HR_SAMPLES}). " +
                    "Showing partial strap data instead of HC fallback.",
            )
            applyHrEnrichment(sessionId, endedAtMs, ndjsonHr)
            return
        }

        // NDJSON had no usable HR — fall back to existing HC path.
        Log.d(
            "URUJ-Summary",
            "Ride $sessionId — NDJSON had ${ndjsonHr.size} HR samples (none from strap), falling back to HC",
        )
        startHcEnrichmentFlow(sessionId, startedAtMs, endedAtMs)
    }

    companion object {
        /** v0.8.6 — rides shorter than 60s skip HC polling. Samsung won't
         *  have useful data and polling shows misleading UX. */
        private const val SHORT_RIDE_HC_SKIP_MS = 60_000L
    }

    /** v0.8.2 — compute HR stats + TIZ from NDJSON samples, persist + emit.
     *  v0.8.5 — AVG / MAX now compute over MOVING-TIME samples only (matches
     *  Strava / Garmin convention). Pre-v0.8.5 averaged all samples including
     *  traffic-light stops + auto-paused segments where HR dropped to rest,
     *  which dragged the displayed AVG below what the rider intuitively
     *  expected. TIZ continues to use the full sample set since its
     *  time-weighted math already handles per-sample contribution correctly. */
    private fun applyHrEnrichment(
        sessionId: String,
        endMs: Long,
        samples: List<RideHrSample>,
    ) {
        if (samples.isEmpty()) return
        // v0.8.6 — defensive session check. Caller is a coroutine that may
        // have been cancelled mid-flight by a newer startHrEnrichment for
        // a different session; in that case the cancellation usually
        // short-circuits us, but the synchronous StateFlow writes below
        // could still race in. Bail out if we no longer represent the
        // current session.
        if (currentEnrichmentSessionId != sessionId) {
            Log.d("URUJ-Summary", "applyHrEnrichment for $sessionId — session changed, skipping write")
            return
        }
        // Moving-time filter for the displayed AVG / MAX HR. Excludes
        // auto-paused samples + samples where the rider wasn't pedalling
        // (speed below MOVING_SPEED_THRESHOLD_MPS). Falls back to the full
        // sample set if no samples qualify as "moving" (e.g. stationary
        // indoor trainer ride that never breaks the speed threshold) — better
        // to show a number than to render the card empty.
        val movingSamples = samples.filter { it.isMoving }
        val statSamples = if (movingSamples.size >= 30) movingSamples else samples
        val avg = statSamples.map { it.bpm }.average().toInt()
        val max = statSamples.maxOf { it.bpm }
        // Sample count + source breakdown reflect ALL samples in the ride
        // (so the rider sees the full data coverage), not just moving-time.
        val count = samples.size
        val breakdown = samples.groupingBy { it.source }.eachCount()

        // Persist + emit summary stats. Source label captures the breakdown
        // for the displayed badge on next ride open.
        historyRepo.load(sessionId)?.let { existing ->
            historyRepo.save(
                existing.copy(
                    averageHrBpm = avg,
                    maxHrBpm = max,
                    hrSampleCount = count,
                    hrSourceLabel = formatSourceLabel(breakdown),
                ),
            )
        }
        _hrEnrichment.value = HrEnrichmentState.Done(
            avgHrBpm = avg,
            maxHrBpm = max,
            sampleCount = count,
            isRefreshing = false,
        )
        _hrSourceBreakdown.value = breakdown

        // TIZ from the same NDJSON samples (no duplicate work). Profile is
        // cheap to read; max HR drives the %max bands.
        viewModelScope.launch {
            runCatching {
                val profile = profileStore.current()
                val timed = samples.map { Instant.ofEpochMilli(it.timestampMs) to it.bpm }
                val tiz = zoneCalc.compute(
                    samples = timed,
                    maxHrBpm = profile.maxHrBpm,
                    rideEndMs = endMs,
                )
                _timeInZone.value = tiz
            }.onFailure { Log.w("URUJ-Summary", "NDJSON-sourced TIZ compute failed", it) }
        }
    }

    /** v0.8.2 — short readable badge label from a source breakdown. */
    private fun formatSourceLabel(breakdown: Map<SensorSource, Int>): String {
        if (breakdown.isEmpty()) return ""
        val total = breakdown.values.sum()
        // Sort by count descending so the dominant source appears first
        val parts = breakdown.entries.sortedByDescending { it.value }
        if (parts.size == 1) {
            return when (parts[0].key) {
                SensorSource.STRAP -> "from chest strap"
                SensorSource.BAND -> "from band (batched)"
                SensorSource.MIXED -> "mixed"
                SensorSource.UNKNOWN_LEGACY -> "from band (legacy)"
            }
        }
        // Mixed sources — show percentages
        return parts.joinToString(" + ") { e ->
            val pct = (e.value * 100 / total).coerceAtLeast(1)
            "$pct% ${e.key.displayShort()}"
        }
    }

    /**
     * v0.8.2 — original HC-polling flow extracted into a separate method
     * (called only when NDJSON has no HR data, e.g. pre-strap-pairing
     * rides or both BLE+HC were unavailable during the ride). Behavior
     * unchanged from pre-v0.8.2: polls every 15s for 5 min, stops when
     * HC sync lands.
     */
    private fun startHcEnrichmentFlow(
        sessionId: String,
        startedAtMs: Long,
        endedAtMs: Long,
    ) {
        val existing = historyRepo.load(sessionId)
        if (existing?.averageHrBpm != null) {
            // Show stored data immediately with isRefreshing=true — UI renders a
            // small spinner/badge so the rider knows a background HC re-pull is
            // in flight (catches Samsung's late batch sync, e.g. 156 → 173).
            _hrEnrichment.value = HrEnrichmentState.Done(
                avgHrBpm = existing.averageHrBpm,
                maxHrBpm = existing.maxHrBpm,
                sampleCount = existing.hrSampleCount,
                isRefreshing = true,
            )
            viewModelScope.launch { refreshHrFromHc(sessionId, startedAtMs, endedAtMs, existing) }
            return
        }

        pollingJob?.cancel()
        // v0.8.6 — capture the session this polling loop is for. Every
        // StateFlow write below first verifies we're still the active
        // session. Without this, a polling loop launched for ride A keeps
        // ticking after the user navigates to ride B, and its 15s updates
        // overwrite ride B's correctly-loaded state.
        val pollingForSessionId = sessionId
        pollingJob = viewModelScope.launch {
            if (currentEnrichmentSessionId != pollingForSessionId) return@launch
            _hrEnrichment.value = HrEnrichmentState.Polling(secondsElapsed = 0)
            val app = getApplication<Application>()
            val sdkOk = HealthConnectClient.getSdkStatus(app) == HealthConnectClient.SDK_AVAILABLE
            if (!sdkOk) {
                if (currentEnrichmentSessionId == pollingForSessionId) {
                    _hrEnrichment.value = HrEnrichmentState.NotAvailable
                }
                return@launch
            }
            val client = runCatching { HealthConnectClient.getOrCreate(app) }.getOrNull()
            if (client == null) {
                if (currentEnrichmentSessionId == pollingForSessionId) {
                    _hrEnrichment.value = HrEnrichmentState.NotAvailable
                }
                return@launch
            }
            // Health Connect permission must be granted — silent skip if not.
            val granted = runCatching { client.permissionController.getGrantedPermissions() }
                .getOrDefault(emptySet())
            if (HealthPermission.getReadPermission(HeartRateRecord::class) !in granted) {
                if (currentEnrichmentSessionId == pollingForSessionId) {
                    _hrEnrichment.value = HrEnrichmentState.NotAvailable
                }
                return@launch
            }

            // Poll every 15s for 5 min. Most syncs land in 30-90s.
            val startedAt = System.currentTimeMillis()
            val deadline = startedAt + 5 * 60 * 1_000L
            while (System.currentTimeMillis() < deadline) {
                // v0.8.6 — bail if the active session changed under us.
                if (currentEnrichmentSessionId != pollingForSessionId) {
                    Log.d("URUJ-Summary", "Polling loop for $pollingForSessionId — session changed, bailing")
                    return@launch
                }
                val (avg, max, count) = withContext(Dispatchers.IO) {
                    fetchHrStats(client, startedAtMs, endedAtMs)
                }
                if (avg != null && count > 5) {
                    historyRepo.load(sessionId)?.let { existing ->
                        historyRepo.save(
                            existing.copy(
                                averageHrBpm = avg,
                                maxHrBpm = max,
                                hrSampleCount = count,
                            ),
                        )
                    }
                    // v0.8.6 — defensive session check before writing terminal state
                    if (currentEnrichmentSessionId == pollingForSessionId) {
                        _hrEnrichment.value = HrEnrichmentState.Done(avg, max, count)
                    }
                    return@launch
                }
                val elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                // v0.8.6 — only emit Polling state if we're still the active session.
                if (currentEnrichmentSessionId == pollingForSessionId) {
                    _hrEnrichment.value = HrEnrichmentState.Polling(elapsedSec)
                }
                delay(15_000L)
            }
            // v0.8.6 — TimedOut also gated on session match.
            if (currentEnrichmentSessionId == pollingForSessionId) {
                _hrEnrichment.value = HrEnrichmentState.TimedOut
            }
        }
    }

    /**
     * Single-shot re-pull from HC for a ride that already has HR data. If HC now
     * has MORE samples than what's stored (Samsung post-workout batch sync
     * arrived after the initial enrichment window closed), update the stored
     * summary and emit fresh state. Silent no-op if HC has same/fewer samples.
     */
    private suspend fun refreshHrFromHc(
        sessionId: String,
        startMs: Long,
        endMs: Long,
        existing: StoredRideSummary,
    ) {
        val app = getApplication<Application>()
        val sdkOk = HealthConnectClient.getSdkStatus(app) == HealthConnectClient.SDK_AVAILABLE
        if (!sdkOk) return
        val client = runCatching { HealthConnectClient.getOrCreate(app) }.getOrNull() ?: return
        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
        if (HealthPermission.getReadPermission(HeartRateRecord::class) !in granted) return

        val (avg, max, count) = withContext(Dispatchers.IO) {
            fetchHrStats(client, startMs, endMs)
        }
        if (avg == null || count <= existing.hrSampleCount) {
            // No new data — clear the refreshing flag so UI stops spinning.
            _hrEnrichment.value = HrEnrichmentState.Done(
                avgHrBpm = existing.averageHrBpm,
                maxHrBpm = existing.maxHrBpm,
                sampleCount = existing.hrSampleCount,
                isRefreshing = false,
            )
            return
        }

        historyRepo.save(
            existing.copy(
                averageHrBpm = avg,
                maxHrBpm = max,
                hrSampleCount = count,
            ),
        )
        _hrEnrichment.value = HrEnrichmentState.Done(
            avgHrBpm = avg,
            maxHrBpm = max,
            sampleCount = count,
            isRefreshing = false,
        )
        Log.d(
            "URUJ-Summary",
            "Ride $sessionId HR refreshed: ${existing.hrSampleCount} → $count samples, max ${existing.maxHrBpm} → $max",
        )
    }

    private suspend fun fetchHrStats(
        client: HealthConnectClient,
        startMs: Long,
        endMs: Long,
    ): Triple<Int?, Int?, Int> {
        val timed = fetchHrTimedSamples(client, startMs, endMs)
        if (timed.isEmpty()) return Triple(null, null, 0)
        val bpms = timed.map { it.second }
        val avg = bpms.average().toInt()
        val max = bpms.max()
        // Side-effect: also compute time-in-zone from these timed samples and
        // emit. Same fetch covers both summary stats AND zone breakdown — no
        // duplicate HC query. Profile lookup is cheap (DataStore in-memory).
        runCatching {
            val profile = profileStore.current()
            val tiz = zoneCalc.compute(
                samples = timed.map { Instant.ofEpochMilli(it.first) to it.second },
                maxHrBpm = profile.maxHrBpm,
                rideEndMs = endMs,
            )
            _timeInZone.value = tiz
        }.onFailure { Log.w("URUJ-Summary", "time-in-zone compute failed", it) }
        return Triple(avg, max, timed.size)
    }

    /** Pull timestamped HR samples for the ride window. Returns (epoch-ms, bpm) pairs. */
    private suspend fun fetchHrTimedSamples(
        client: HealthConnectClient,
        startMs: Long,
        endMs: Long,
    ): List<Pair<Long, Int>> {
        return runCatching {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        Instant.ofEpochMilli(startMs),
                        Instant.ofEpochMilli(endMs),
                    ),
                ),
            )
            response.records
                .flatMap { it.samples }
                .map { it.time.toEpochMilli() to it.beatsPerMinute.toInt() }
                .sortedBy { it.first }
        }.onFailure { Log.w("URUJ-Summary", "HR timed-samples fetch failed", it) }
            .getOrDefault(emptyList())
    }
}

sealed class HrEnrichmentState {
    data object Idle : HrEnrichmentState()
    data class Polling(val secondsElapsed: Int) : HrEnrichmentState()
    /** HR is shown from the stored summary. [isRefreshing] is true while we have a
     *  background re-pull in flight; UI shows a small spinner/badge to signal the
     *  number may update if Samsung's batch sync has more samples than we stored. */
    data class Done(
        val avgHrBpm: Int?,
        val maxHrBpm: Int?,
        val sampleCount: Int,
        val isRefreshing: Boolean = false,
        val lastRefreshAtMs: Long = System.currentTimeMillis(),
    ) : HrEnrichmentState()
    data object TimedOut : HrEnrichmentState()
    /** Health Connect not installed / no permission — silent skip, no HR for this user. */
    data object NotAvailable : HrEnrichmentState()
}
