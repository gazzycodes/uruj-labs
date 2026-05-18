package com.uruj.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.uruj.power.WearTimeCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * v0.7.8 — orchestrates wear-time computation for both sensors over a
 * requested window. Pulls strap timestamps from 24/7 NDJSON + band
 * timestamps from HC HeartRateRecord, hands to `WearTimeCalculator`.
 *
 * Why a separate repo: the calculator is pure (no IO, easily testable),
 * the repo handles the device-specific IO + permission checking + null
 * fallbacks. Same pattern as `CarRepository` for CAR + `OrthostaticTestRepository`
 * for orthostatic.
 *
 * Edge cases handled:
 *   - HC permission missing → bandSampleTimestampsMs = emptyList, all stats
 *     show "strap-only" since band data unavailable
 *   - 24/7 NDJSON empty for window → strapSampleTimestampsMs = emptyList,
 *     stats show "band-only"
 *   - Both empty → returns null (no sensors paired / both off whole window)
 *   - Window before today's midnight (historical day) → uses full 24h window
 *   - Today (partial day) → uses [today's midnight, now] so percent is
 *     against time-elapsed-today, not the full 24h
 */
class WearTimeRepository(context: Context) {

    private val appContext = context.applicationContext
    private val continuousRepo = ContinuousBiometricRepository(appContext)
    private val calc = WearTimeCalculator()

    /** Compute wear time for today (midnight → now, local time zone). */
    suspend fun today(): WearTimeSnapshot? = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val nowInstant = Instant.now()
        val midnight = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        compute(midnight, nowInstant)
    }

    /** Compute wear time for an arbitrary window. */
    suspend fun forWindow(start: Instant, end: Instant): WearTimeSnapshot? =
        withContext(Dispatchers.IO) {
            compute(start, end)
        }

    private suspend fun compute(start: Instant, end: Instant): WearTimeSnapshot? {
        val strapTimestamps = continuousRepo.hrSamplesForWindow(start, end)
            .map { it.first.toEpochMilli() }
        val bandTimestamps = readBandSampleTimestamps(start, end)
        // v0.7.9 — also read recent band samples (last 24h beyond the window
        // start if needed) to find "most recent band HR sample anywhere" for
        // the sync-lag indicator. If today's window has NO band samples,
        // user still wants to see "last band sync was 4h ago".
        val mostRecentBandSampleMs = readMostRecentBandSampleTimestamp(
            lookbackStart = start.minus(java.time.Duration.ofDays(2)),
            lookbackEnd = end,
        )
        // v0.7.9 — same for strap (last NDJSON sample anywhere)
        val mostRecentStrapSampleMs = (strapTimestamps.maxOrNull() ?: 0L)
            .takeIf { it > 0L } ?: run {
                continuousRepo.hrSamplesForWindow(
                    start.minus(java.time.Duration.ofDays(2)),
                    end,
                ).maxOfOrNull { it.first.toEpochMilli() } ?: 0L
            }
        if (strapTimestamps.isEmpty() && bandTimestamps.isEmpty() &&
            mostRecentStrapSampleMs == 0L && mostRecentBandSampleMs == 0L) {
            return null
        }
        val strapPaired = strapTimestamps.isNotEmpty() || mostRecentStrapSampleMs > 0L
        val result = calc.compute(
            strapSampleTimestampsMs = strapTimestamps,
            bandSampleTimestampsMs = bandTimestamps,
            windowStartMs = start.toEpochMilli(),
            windowEndMs = end.toEpochMilli(),
        )
        return WearTimeSnapshot(
            windowStartMs = start.toEpochMilli(),
            windowEndMs = end.toEpochMilli(),
            result = result,
            strapEverPaired = strapPaired,
            bandAvailable = bandTimestamps.isNotEmpty() || mostRecentBandSampleMs > 0L,
            mostRecentStrapSampleMs = mostRecentStrapSampleMs.takeIf { it > 0L },
            mostRecentBandSampleMs = mostRecentBandSampleMs.takeIf { it > 0L },
        )
    }

    /**
     * v0.7.9 — find the most recent band HR sample anywhere in the lookback
     * window. Used for the sync-lag indicator on the wear-time card.
     */
    private suspend fun readMostRecentBandSampleTimestamp(
        lookbackStart: Instant,
        lookbackEnd: Instant,
    ): Long {
        return readBandSampleTimestamps(lookbackStart, lookbackEnd).maxOrNull() ?: 0L
    }

    private suspend fun readBandSampleTimestamps(
        start: Instant,
        end: Instant,
    ): List<Long> {
        val sdkOk = HealthConnectClient.getSdkStatus(appContext) ==
            HealthConnectClient.SDK_AVAILABLE
        if (!sdkOk) return emptyList()
        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }
            .getOrNull() ?: return emptyList()
        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
        if (HealthPermission.getReadPermission(HeartRateRecord::class) !in granted) {
            return emptyList()
        }
        // v0.8.5 — observability so HC pressure is visible in logcat
        // (filter URUJ-HC). WearTime polls every 30s while Pipeline tab is
        // visible — 120 reads/hr in the worst case, small but cumulative.
        HcReadGuard.recordRead("weartime.hr-samples")
        return runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    ascendingOrder = true,
                    pageSize = 5_000,
                ),
            ).records.flatMap { rec ->
                rec.samples.map { it.time.toEpochMilli() }
            }
        }.onFailure { Log.w(TAG, "[wear] band sample read failed", it) }
            .getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "URUJ-WearTime"
    }
}

/**
 * One wear-time computation result with the metadata UI needs to render
 * the right state ("strap-only" when band unavailable, etc.)
 */
data class WearTimeSnapshot(
    val windowStartMs: Long,
    val windowEndMs: Long,
    val result: WearTimeCalculator.Result,
    /** True if the strap is paired (regardless of whether it captured in
     *  the window — drives "strap charging?" UX hint). */
    val strapEverPaired: Boolean,
    /** True if HC band samples were available for the window OR in the
     *  2-day lookback (band sync may lag — having recent samples means
     *  it's actively pairing). */
    val bandAvailable: Boolean,
    /** v0.7.9 — epoch ms of the most recent strap sample seen anywhere in
     *  the 2-day lookback. Null if strap never produced a sample. UI shows
     *  "strap synced X min ago" so user can verify it's alive. */
    val mostRecentStrapSampleMs: Long? = null,
    /** v0.7.9 — epoch ms of the most recent HC band HR sample. Null if no
     *  band sample in the 2-day lookback. UI shows "band synced X min ago"
     *  to make HC sync lag visible. */
    val mostRecentBandSampleMs: Long? = null,
)
