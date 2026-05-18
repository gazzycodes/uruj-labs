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

    fun startHrEnrichment(sessionId: String, startedAtMs: Long, endedAtMs: Long) {
        // v0.8.2 — first try the ride NDJSON. If the strap was streaming
        // during the ride, RideRecorderService wrote source-tagged hrBpm
        // per GPS tick. NDJSON-first means higher-precision strap data
        // wins; HC stays as fallback for pre-strap rides or strap-off
        // segments.
        viewModelScope.launch { tryEnrichFromNdjsonThenHc(sessionId, startedAtMs, endedAtMs) }
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
        // NDJSON didn't have enough HR samples — fall back to existing HC path.
        Log.d(
            "URUJ-Summary",
            "Ride $sessionId — NDJSON had ${ndjsonHr.size} HR samples (<min), falling back to HC",
        )
        startHcEnrichmentFlow(sessionId, startedAtMs, endedAtMs)
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
        pollingJob = viewModelScope.launch {
            _hrEnrichment.value = HrEnrichmentState.Polling(secondsElapsed = 0)
            val app = getApplication<Application>()
            val sdkOk = HealthConnectClient.getSdkStatus(app) == HealthConnectClient.SDK_AVAILABLE
            if (!sdkOk) {
                _hrEnrichment.value = HrEnrichmentState.NotAvailable
                return@launch
            }
            val client = runCatching { HealthConnectClient.getOrCreate(app) }.getOrNull()
            if (client == null) {
                _hrEnrichment.value = HrEnrichmentState.NotAvailable
                return@launch
            }
            // Health Connect permission must be granted — silent skip if not.
            val granted = runCatching { client.permissionController.getGrantedPermissions() }
                .getOrDefault(emptySet())
            if (HealthPermission.getReadPermission(HeartRateRecord::class) !in granted) {
                _hrEnrichment.value = HrEnrichmentState.NotAvailable
                return@launch
            }

            // Poll every 15s for 5 min. Most syncs land in 30-90s.
            val startedAt = System.currentTimeMillis()
            val deadline = startedAt + 5 * 60 * 1_000L
            while (System.currentTimeMillis() < deadline) {
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
                    _hrEnrichment.value = HrEnrichmentState.Done(avg, max, count)
                    return@launch
                }
                val elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                _hrEnrichment.value = HrEnrichmentState.Polling(elapsedSec)
                delay(15_000L)
            }
            _hrEnrichment.value = HrEnrichmentState.TimedOut
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
