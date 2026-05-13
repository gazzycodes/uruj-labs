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
import com.uruj.data.RideHistoryRepository
import com.uruj.data.RiderProfileStore
import com.uruj.data.StoredRideSummary
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
        val existing = historyRepo.load(sessionId)
        // If we already have HR data, show it instantly AND kick off a single
        // background re-pull. The original 5-min polling window stops the
        // first time it sees ANY HR data — but Samsung sometimes pushes the
        // full workout batch later (the 2026-05-13 ride was a textbook case:
        // initial enrichment captured max 156 from partial sync, real peak
        // 173 arrived ~15 min after ride end). Re-pulling on summary view
        // lets the displayed max catch up to reality.
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
