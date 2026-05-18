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
        if (strapTimestamps.isEmpty() && bandTimestamps.isEmpty()) {
            return null
        }
        val strapPaired = strapTimestamps.isNotEmpty() ||
            // Even if strap had ZERO samples in window, treat as "paired" if
            // there are any samples in the last 7 days (rider has paired the
            // strap but it might just be off RIGHT NOW). Drives UI label.
            continuousRepo.hrSamplesForWindow(
                end.minus(java.time.Duration.ofDays(7)),
                end,
            ).isNotEmpty()
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
            bandAvailable = bandTimestamps.isNotEmpty(),
        )
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
    /** True if HC band samples were available for the window. */
    val bandAvailable: Boolean,
)
