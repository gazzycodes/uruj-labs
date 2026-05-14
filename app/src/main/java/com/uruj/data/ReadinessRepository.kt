package com.uruj.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.uruj.domain.ReadinessInputs
import com.uruj.power.ReadinessCalculator
import com.uruj.power.SleepingRhrCalculator
import com.uruj.domain.ReadinessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * One snapshot of a readiness computation — includes the user-facing result AND
 * the raw record counts that produced it. The UI uses the counts to tell the user
 * exactly what Health Connect is returning, so they're never blind about whether
 * the pipeline is working.
 */
data class ReadinessSnapshot(
    val result: ReadinessResult,
    val diagnostics: ReadinessDiagnostics,
    val computedAtMs: Long,
)

data class ReadinessDiagnostics(
    val healthConnectInstalled: Boolean,
    val permissionsGranted: Int,
    val permissionsExpected: Int,
    val sleepRecords7d: Int,
    val hrvRecords7d: Int,
    val rhrRecords7d: Int,
    val hrRecords7d: Int,
    val rideSummariesAll: Int,
    /**
     * Short label telling the UI where the RHR input came from. "direct" =
     * RestingHeartRateRecord existed; "sleep" = derived via SleepingRhrCalculator;
     * "proxy" = HR-sample percentile fallback (no sleep data); null = no RHR data
     * available at all. Prevents the misleading "0 RHR" diagnostics line when
     * the readiness score actually used a derived value.
     */
    val rhrSourceLabel: String? = null,
    /**
     * Same idea for HRV. "direct" = HeartRateVariabilityRmssdRecord existed
     * (rare on Fit Band 3 — needs RR intervals from chest strap); "proxy" =
     * std-dev of HR samples (Garmin/Fitbit pre-strap pattern); null = no HRV
     * input available. Real RMSSD HRV unlocks with BLE chest strap (v1.5).
     */
    val hrvSourceLabel: String? = null,
)

/**
 * Reads from Health Connect (and our own ride history for training load) to build
 * the ReadinessInputs object, then runs the calculator to produce today's score.
 *
 * Every read is null-safe — missing data = missing component = calculator copes
 * gracefully. No exception ever bubbles to UI.
 */
class ReadinessRepository(context: Context) {

    private val appContext = context.applicationContext
    private val calculator = ReadinessCalculator()
    private val historyRepo = RideHistoryRepository(appContext)
    private val sleepingRhrCalc = SleepingRhrCalculator()
    private val lastSleepReader = LastSleepReader()

    suspend fun compute(): ReadinessResult = withContext(Dispatchers.IO) {
        val inputs = gatherInputs()
        calculator.compute(inputs)
    }

    /**
     * Returns the result PLUS raw record counts. UI uses this to show users exactly
     * what Health Connect has — no more guessing whether sync worked.
     */
    suspend fun computeWithDiagnostics(): ReadinessSnapshot = withContext(Dispatchers.IO) {
        val diagnostics = collectDiagnostics()
        val (inputs, rhrSource, hrvSource) = gatherInputsWithSource()
        val result = calculator.compute(inputs)
        ReadinessSnapshot(
            result = result,
            diagnostics = diagnostics.copy(
                rhrSourceLabel = rhrSource,
                hrvSourceLabel = hrvSource,
            ),
            computedAtMs = System.currentTimeMillis(),
        )
    }

    private suspend fun collectDiagnostics(): ReadinessDiagnostics {
        val sdkStatus = HealthConnectClient.getSdkStatus(appContext)
        val installed = sdkStatus == HealthConnectClient.SDK_AVAILABLE
        val rideCount = historyRepo.listAll().size
        if (!installed) {
            return ReadinessDiagnostics(
                healthConnectInstalled = false,
                permissionsGranted = 0,
                permissionsExpected = 4,
                sleepRecords7d = 0,
                hrvRecords7d = 0,
                rhrRecords7d = 0,
                hrRecords7d = 0,
                rideSummariesAll = rideCount,
            )
        }
        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }.getOrNull()
            ?: return ReadinessDiagnostics(installed, 0, 4, 0, 0, 0, 0, rideCount)
        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
        val weekAgo = Instant.now().minus(Duration.ofDays(7))
        val now = Instant.now()
        val range = TimeRangeFilter.between(weekAgo, now)

        val sleepCount = countRecords(client, granted, SleepSessionRecord::class, range)
        val hrvCount = countRecords(client, granted, HeartRateVariabilityRmssdRecord::class, range)
        val rhrCount = countRecords(client, granted, RestingHeartRateRecord::class, range)
        val hrCount = countRecords(client, granted, HeartRateRecord::class, range)

        val expected = 4
        val grantedCount = listOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
        ).count { it in granted }

        return ReadinessDiagnostics(
            healthConnectInstalled = installed,
            permissionsGranted = grantedCount,
            permissionsExpected = expected,
            sleepRecords7d = sleepCount,
            hrvRecords7d = hrvCount,
            rhrRecords7d = rhrCount,
            hrRecords7d = hrCount,
            rideSummariesAll = rideCount,
        )
    }

    private suspend fun <T : androidx.health.connect.client.records.Record> countRecords(
        client: HealthConnectClient,
        granted: Set<String>,
        recordType: kotlin.reflect.KClass<T>,
        range: TimeRangeFilter,
    ): Int {
        if (HealthPermission.getReadPermission(recordType) !in granted) return 0
        return runCatching {
            client.readRecords(
                ReadRecordsRequest(recordType, range, ascendingOrder = false, pageSize = 100),
            ).records.size
        }.onFailure { Log.w("URUJ-Readiness", "count failed for ${recordType.simpleName}", it) }
            .getOrDefault(0)
    }

    private suspend fun gatherInputs(): ReadinessInputs = gatherInputsWithSource().first

    /**
     * Returns the inputs PLUS a short label naming where RHR came from:
     *   "direct" — Samsung wrote RestingHeartRateRecord directly
     *   "sleep"  — derived via SleepingRhrCalculator from sleep + HR samples
     *   "proxy"  — HR-sample percentile fallback (last resort)
     *   null     — no RHR data at all
     * The UI uses this to replace the misleading "0 RHR" diagnostics line with
     * "RHR(sleep)" when the readiness score actually had a derived value.
     */
    private suspend fun gatherInputsWithSource(): Triple<ReadinessInputs, String?, String?> {
        // If Health Connect isn't available, fall back to training-load-only.
        val sdkOk = HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE
        if (!sdkOk) {
            return Triple(ReadinessInputs(trainingStressBalance = computeTsb()), null, null)
        }
        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }.getOrNull()
            ?: return Triple(ReadinessInputs(trainingStressBalance = computeTsb()), null, null)

        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())

        val now = Instant.now()

        // Unified LastSleepReader — same source of truth as Bio Lab. Fixes the
        // v0.3.6 mismatch where Readiness showed 5.3h while Bio Lab showed 9.2h
        // on the same user's data because the two summed different windows.
        val sleep = lastSleepReader.read(client, granted)?.hours

        // Try direct record first. If Samsung Fit Band 3 doesn't write HRV (varies by
        // firmware) we fall back to a proxy computed from the HR samples it DOES write —
        // see HrAnalyzer for the methodology.
        var hrvToday: Float? = null
        var hrvBaseline: Float? = null
        var rhrToday: Int? = null
        var rhrBaseline: Int? = null
        var rhrSource: String? = null
        var hrvSource: String? = null

        if (HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class) in granted) {
            val (today, baseline) = readHrvTodayAndBaseline(client, now)
            hrvToday = today
            hrvBaseline = baseline
            if (today != null) hrvSource = "direct"
        }
        if (HealthPermission.getReadPermission(RestingHeartRateRecord::class) in granted) {
            val (today, baseline) = readRhrTodayAndBaseline(client, now)
            rhrToday = today
            rhrBaseline = baseline
            if (today != null) rhrSource = "direct"
        }

        // Sleeping-RHR via shared calculator — preferred over Samsung's direct
        // record when sleep data + HR samples align. Same logic as Bio Lab so
        // RHR matches across both screens. v0.4.0 dropped the proxy fallback
        // (activity-confounded last-resort 5th-percentile estimate) — if we
        // don't have sleep + HR samples, we don't fake RHR for readiness.
        val hrPermGranted = HealthPermission.getReadPermission(HeartRateRecord::class) in granted
        val sleepPermGranted = HealthPermission.getReadPermission(SleepSessionRecord::class) in granted

        if (rhrToday == null && hrPermGranted && sleepPermGranted) {
            val sleepingHrData = readSleepingHrInputs(client, now)
            if (sleepingHrData != null) {
                val sleepingResult = sleepingRhrCalc.compute(sleepingHrData.first, sleepingHrData.second)
                if (sleepingResult != null) {
                    rhrToday = sleepingResult.mostRecentNightBpm
                    rhrBaseline = sleepingResult.medianBpm
                    rhrSource = "sleep"
                }
            }
        }

        // v0.4.0: HRV proxy fallback REMOVED. SleepingHrvProxyCalculator was
        // std-dev of HR samples in sleep windows — directionally correlated
        // with RMSSD but NOT real HRV. Per [[feedback_no_samsung_proxy]] +
        // [[reference_lab_level_uruj]] rule #4 (no fake numbers). HRV column
        // only populates when Samsung writes HeartRateVariabilityRmssdRecord
        // (rare on Fit Band 3, real when present). Real RMSSD HRV unlocks
        // with v1.5 BLE chest strap.

        return Triple(
            ReadinessInputs(
                sleepLastNightHours = sleep,
                hrvTodayRmssd = hrvToday,
                hrvBaseline7d = hrvBaseline,
                restingHrToday = rhrToday,
                restingHrBaseline7d = rhrBaseline,
                trainingStressBalance = computeTsb(),
            ),
            rhrSource,
            hrvSource,
        )
    }

    /**
     * Pull 7d of timestamped HR samples + sleep windows. Feeds the sleeping-RHR
     * calculator only (v0.4.0 dropped the sleeping-HRV-proxy consumer). Returns
     * null on any failure — caller treats RHR as unavailable.
     */
    private suspend fun readSleepingHrInputs(
        client: HealthConnectClient,
        now: Instant,
    ): Pair<List<Pair<Instant, Int>>, List<Pair<Instant, Instant>>>? {
        return runCatching {
            val weekAgo = now.minus(Duration.ofDays(7))

            val hrSamples = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(weekAgo, now),
                    ascendingOrder = false,
                    pageSize = 5_000,
                ),
            ).records
                .flatMap { it.samples }
                .map { it.time to it.beatsPerMinute.toInt() }

            val sleepWindows = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(weekAgo, now),
                    ascendingOrder = false,
                ),
            ).records.map { it.startTime to it.endTime }

            hrSamples to sleepWindows
        }.onFailure { Log.w("URUJ-Readiness", "sleeping HR inputs read failed", it) }
            .getOrNull()
    }

    // readLastNightSleepHours removed in v0.3.7 — replaced by LastSleepReader
    // (single source of truth used by both ReadinessRepository and BioLabRepository).

    private suspend fun readHrvTodayAndBaseline(
        client: HealthConnectClient,
        now: Instant,
    ): Pair<Float?, Float?> {
        return runCatching {
            val weekAgo = now.minus(Duration.ofDays(7))
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateVariabilityRmssdRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(weekAgo, now),
                    ascendingOrder = false,
                    pageSize = 200,
                ),
            )
            if (response.records.isEmpty()) return@runCatching null to null
            // Most-recent reading is "today's" HRV. Median of the rest is baseline.
            val today = response.records.first().heartRateVariabilityMillis.toFloat()
            val all = response.records.map { it.heartRateVariabilityMillis }.sorted()
            val baseline = all[all.size / 2].toFloat()
            today to baseline
        }.onFailure { Log.w("URUJ-Readiness", "HRV read failed", it) }.getOrDefault(null to null)
    }

    private suspend fun readRhrTodayAndBaseline(
        client: HealthConnectClient,
        now: Instant,
    ): Pair<Int?, Int?> {
        return runCatching {
            val weekAgo = now.minus(Duration.ofDays(7))
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = RestingHeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(weekAgo, now),
                    ascendingOrder = false,
                    pageSize = 14,
                ),
            )
            if (response.records.isEmpty()) return@runCatching null to null
            val today = response.records.first().beatsPerMinute.toInt()
            val all = response.records.map { it.beatsPerMinute }.sorted()
            val baseline = all[all.size / 2].toInt()
            today to baseline
        }.onFailure { Log.w("URUJ-Readiness", "RHR read failed", it) }.getOrDefault(null to null)
    }

    /**
     * Training Stress Balance from URUJ's own ride history. Coggan-style EWMA:
     *   ATL = exponentially weighted moving avg of last 7 days of TSS  (α = 1/7)
     *   CTL = exponentially weighted moving avg of last 42 days of TSS (α = 1/42)
     *   TSB = CTL - ATL  (positive = fresh, negative = fatigued)
     *
     * TSS per ride is approximated as IF² × hours × 100 = (avgPower/FTP)² × hours × 100.
     */
    private fun computeTsb(): Float? {
        val rides = historyRepo.listAll()
        if (rides.size < 2) return null

        val ftp = rides.last().ftpWatts.coerceAtLeast(1)
        val zone = ZoneId.systemDefault()
        val todayDate = LocalDate.now(zone)

        // Per-CALENDAR-DAY TSS bucketing — was previously ms-diff/86_400_000
        // which truncated to 24-hour chunks rather than calendar days. Bug
        // surfaced 2026-05-14: ride at 2026-05-13 04:00 + check at 2026-05-14
        // 00:32 = 20.5h elapsed = daysAgo 0 = ride bucketed as "today's TSS"
        // → no decay applied → TSB stuck at -14 for ~24h. Calendar-day bucket
        // puts the ride in dailyTss[1] immediately after the calendar rolls
        // over, applying one decay step → TSB starts improving at midnight.
        val dailyTss = LongArray(43) // index 0 = today, 42 = 42 calendar days ago
        for (ride in rides) {
            val rideDate = Instant.ofEpochMilli(ride.startedAtMs)
                .atZone(zone)
                .toLocalDate()
            val daysAgo = ChronoUnit.DAYS.between(rideDate, todayDate).toInt()
            if (daysAgo !in 0..42) continue
            val hours = ride.movingTimeMs / 3_600_000f
            val intensityFactor = if (ride.averagePowerWatts > 0f) {
                ride.averagePowerWatts / ftp
            } else 0f
            val tss = (intensityFactor * intensityFactor * hours * 100f)
            dailyTss[daysAgo] = (dailyTss[daysAgo] + tss).toLong()
        }

        var atl = 0f
        var ctl = 0f
        for (day in 42 downTo 0) {
            val tss = dailyTss[day].toFloat()
            atl = atl * (1f - 1f / 7f) + tss * (1f / 7f)
            ctl = ctl * (1f - 1f / 42f) + tss * (1f / 42f)
        }
        return ctl - atl
    }
}
