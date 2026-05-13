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
import com.uruj.power.HrAnalyzer
import com.uruj.power.ReadinessCalculator
import com.uruj.power.SleepingRhrCalculator
import com.uruj.domain.ReadinessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.Duration

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
    private val hrAnalyzer = HrAnalyzer()
    private val sleepingRhrCalc = SleepingRhrCalculator()

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

        val sleep = if (HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
            readLastNightSleepHours(client, now)
        } else null

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

        // Sleeping-RHR via shared calculator — preferred over proxy when sleep
        // data + HR samples align. Uses the same logic as Bio Lab so RHR matches
        // across both screens (was the v0.2.9 inconsistency: Bio Lab 50 vs
        // Readiness 55). Today = most recent sleep night's min; baseline =
        // cross-night median over 7d.
        val hrPermGranted = HealthPermission.getReadPermission(HeartRateRecord::class) in granted
        val sleepPermGranted = HealthPermission.getReadPermission(SleepSessionRecord::class) in granted
        if (rhrToday == null && hrPermGranted && sleepPermGranted) {
            val sleepingResult = readSleepingRhr(client, now)
            if (sleepingResult != null) {
                rhrToday = sleepingResult.mostRecentNightBpm
                rhrBaseline = sleepingResult.medianBpm
                rhrSource = "sleep"
            }
        }

        // Fallback: derive proxies from raw HeartRateRecord samples when dedicated
        // records aren't available AND sleeping-RHR couldn't be computed.
        if (hrPermGranted && (hrvToday == null || rhrToday == null)) {
            val proxies = readHrProxies(client, now)
            if (rhrToday == null && proxies.todayRhr != null) {
                rhrToday = proxies.todayRhr
                rhrBaseline = proxies.baselineRhr
                rhrSource = "proxy"
            }
            if (hrvToday == null && proxies.todayHrvSd != null) {
                hrvToday = proxies.todayHrvSd
                hrvBaseline = proxies.baselineHrvSd
                hrvSource = "proxy"
            }
        }

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

    private data class HrProxies(
        val todayRhr: Int?,
        val baselineRhr: Int?,
        val todayHrvSd: Float?,
        val baselineHrvSd: Float?,
    )

    /**
     * Pull 7d of timestamped HR samples + sleep windows and run them through
     * the shared SleepingRhrCalculator. Returns null on any failure — caller
     * falls back to proxy logic. Matches Bio Lab's RHR algorithm exactly so
     * both screens display the same number.
     */
    private suspend fun readSleepingRhr(
        client: HealthConnectClient,
        now: Instant,
    ): SleepingRhrCalculator.Result? {
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

            sleepingRhrCalc.compute(hrSamples, sleepWindows)
        }.onFailure { Log.w("URUJ-Readiness", "sleeping RHR compute failed", it) }
            .getOrNull()
    }

    private suspend fun readHrProxies(client: HealthConnectClient, now: Instant): HrProxies {
        return runCatching {
            val weekAgo = now.minus(Duration.ofDays(7))
            val dayAgo = now.minus(Duration.ofDays(1))

            // Pull today's HR samples (last 24h)
            val todayResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(dayAgo, now),
                    ascendingOrder = false,
                    pageSize = 1000,
                ),
            )
            val todaySamples = todayResponse.records
                .flatMap { it.samples }
                .map { it.beatsPerMinute.toInt() }

            // Pull last 7 days for baseline
            val weekResponse = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(weekAgo, now),
                    ascendingOrder = false,
                    pageSize = 5_000,
                ),
            )
            val weekSamples = weekResponse.records
                .flatMap { it.samples }
                .map { it.beatsPerMinute.toInt() }

            val todayAnalysis = hrAnalyzer.analyze(todaySamples)
            val weekAnalysis = hrAnalyzer.analyze(weekSamples)
            HrProxies(
                todayRhr = todayAnalysis.proxyRestingHrBpm,
                baselineRhr = weekAnalysis.proxyRestingHrBpm,
                todayHrvSd = todayAnalysis.proxyHrvSdMs,
                baselineHrvSd = weekAnalysis.proxyHrvSdMs,
            )
        }.onFailure { Log.w("URUJ-Readiness", "HR proxy compute failed", it) }
            .getOrDefault(HrProxies(null, null, null, null))
    }

    private suspend fun readLastNightSleepHours(client: HealthConnectClient, now: Instant): Float? {
        return runCatching {
            val start = now.minus(Duration.ofHours(20))
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, now),
                    ascendingOrder = false,
                ),
            )
            val totalMs = response.records.sumOf {
                Duration.between(it.startTime, it.endTime).toMillis()
            }
            if (totalMs <= 0) null else (totalMs / (3600.0 * 1000)).toFloat()
        }.onFailure { Log.w("URUJ-Readiness", "sleep read failed", it) }.getOrNull()
    }

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

        val nowMs = System.currentTimeMillis()
        val ftp = rides.last().ftpWatts.coerceAtLeast(1)

        // Per-day TSS over last 42 days.
        val dailyTss = LongArray(43) // index 0 = today, 42 = 42 days ago
        for (ride in rides) {
            val daysAgo = ((nowMs - ride.startedAtMs) / (24L * 3600_000)).toInt()
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
            val today = dailyTss[day].toFloat()
            atl = atl * (1f - 1f / 7f) + today * (1f / 7f)
            ctl = ctl * (1f - 1f / 42f) + today * (1f / 42f)
        }
        return ctl - atl
    }
}
