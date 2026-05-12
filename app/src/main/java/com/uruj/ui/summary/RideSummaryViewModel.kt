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
import com.uruj.data.StoredRideSummary
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

    private val _hrEnrichment = MutableStateFlow<HrEnrichmentState>(HrEnrichmentState.Idle)
    val hrEnrichment: StateFlow<HrEnrichmentState> = _hrEnrichment.asStateFlow()

    private var pollingJob: Job? = null

    fun startHrEnrichment(sessionId: String, startedAtMs: Long, endedAtMs: Long) {
        // Skip if the summary already has HR data from a prior enrichment run.
        val existing = historyRepo.load(sessionId)
        if (existing?.averageHrBpm != null) {
            _hrEnrichment.value = HrEnrichmentState.Done(
                avgHrBpm = existing.averageHrBpm,
                maxHrBpm = existing.maxHrBpm,
                sampleCount = existing.hrSampleCount,
            )
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

    private suspend fun fetchHrStats(
        client: HealthConnectClient,
        startMs: Long,
        endMs: Long,
    ): Triple<Int?, Int?, Int> {
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
            val samples = response.records.flatMap { it.samples }
            if (samples.isEmpty()) return Triple(null, null, 0)
            val avg = samples.map { it.beatsPerMinute }.average().toInt()
            val max = samples.maxOf { it.beatsPerMinute }.toInt()
            Triple(avg, max, samples.size)
        }.onFailure { Log.w("URUJ-Summary", "HR fetch failed", it) }
            .getOrDefault(Triple(null, null, 0))
    }
}

sealed class HrEnrichmentState {
    data object Idle : HrEnrichmentState()
    data class Polling(val secondsElapsed: Int) : HrEnrichmentState()
    data class Done(val avgHrBpm: Int?, val maxHrBpm: Int?, val sampleCount: Int) : HrEnrichmentState()
    data object TimedOut : HrEnrichmentState()
    /** Health Connect not installed / no permission — silent skip, no HR for this user. */
    data object NotAvailable : HrEnrichmentState()
}
