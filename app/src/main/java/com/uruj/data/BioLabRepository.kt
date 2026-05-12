package com.uruj.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.uruj.power.CardiovascularAgeCalculator
import com.uruj.power.HrAnalyzer
import com.uruj.power.KarvonenZonesCalculator
import com.uruj.power.VO2MaxCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Aggregates everything Health Connect + URUJ knows about the rider's body into a
 * single Bio Lab snapshot. Pulls raw data, runs derived calculations (VO2 max,
 * cardio age, Karvonen zones, etc.), and hands the UI a ready-to-render bundle.
 *
 * Built to degrade gracefully — every input is optional, every metric reports a
 * null instead of a wrong number when data isn't available.
 */
class BioLabRepository(context: Context) {

    private val appContext = context.applicationContext
    private val profileStore = RiderProfileStore(context)
    private val hrAnalyzer = HrAnalyzer()
    private val vo2Calc = VO2MaxCalculator()
    private val cvAgeCalc = CardiovascularAgeCalculator()
    private val karvonenCalc = KarvonenZonesCalculator()

    suspend fun snapshot(): BioLabSnapshot = withContext(Dispatchers.IO) {
        val profile = profileStore.current()
        val sdkOk = HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE
        if (!sdkOk) {
            return@withContext BioLabSnapshot(
                computedAtMs = System.currentTimeMillis(),
                healthConnectAvailable = false,
                chronologicalAge = profile.ageYears,
                bodyWeightKg = profile.riderWeightKg,
                heightCm = profile.heightCm,
                bmi = computeBmi(profile.riderWeightKg, profile.heightCm),
            )
        }
        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }.getOrNull()
            ?: return@withContext BioLabSnapshot(
                computedAtMs = System.currentTimeMillis(),
                healthConnectAvailable = false,
                chronologicalAge = profile.ageYears,
                bodyWeightKg = profile.riderWeightKg,
                heightCm = profile.heightCm,
                bmi = computeBmi(profile.riderWeightKg, profile.heightCm),
            )
        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())

        val now = Instant.now()
        val monthAgo = now.minus(Duration.ofDays(30))
        val weekAgo = now.minus(Duration.ofDays(7))
        val dayAgo = now.minus(Duration.ofDays(1))

        // Pull last 30d of HR samples — both for max-HR detection (needs broad range
        // to catch hard efforts) and for the RHR/HRV proxy windows.
        val hrSamples30d = readHrSamples(client, granted, monthAgo, now)
        val hrSamplesToday = readHrSamples(client, granted, dayAgo, now)
        val hrAnalysisToday = hrAnalyzer.analyze(hrSamplesToday)
        val hrAnalysis30d = hrAnalyzer.analyze(hrSamples30d)

        val sleepLastNightHours = readLastNightSleep(client, granted, now)
        val spo2LastValue = readLatestSpo2(client, granted, weekAgo, now)
        val stepsToday = readStepsCount(client, granted, dayAgo, now)
        val distanceTodayMeters = readDistanceMeters(client, granted, dayAgo, now)
        val totalCalsToday = readTotalCaloriesKcal(client, granted, dayAgo, now)
        val activeCalsToday = readActiveCaloriesKcal(client, granted, dayAgo, now)
        val exerciseToday = readExerciseSessionCount(client, granted, dayAgo, now)
        val samsungVo2Max = readLatestVo2Max(client, granted, monthAgo, now)
        val latestWeight = readLatestWeight(client, granted, monthAgo, now)

        val effectiveWeightKg = latestWeight ?: profile.riderWeightKg
        val effectiveMaxHr = hrAnalysis30d.proxyMaxHrBpm
            ?.takeIf { it > 100 }
            ?: profile.maxHrBpm
        val effectiveRestingHr = hrAnalysisToday.proxyRestingHrBpm
            ?: hrAnalysis30d.proxyRestingHrBpm

        // Derived: VO2 max (both formulas, cross-validated)
        val vo2 = vo2Calc.compute(
            hrMaxBpm = effectiveMaxHr,
            hrRestBpm = effectiveRestingHr,
            ftpWatts = profile.ftpWatts,
            bodyWeightKg = effectiveWeightKg,
        )
        // Prefer Samsung's VO2 if available (it sees a wider data picture)
        val displayVo2 = samsungVo2Max ?: vo2.consensus

        // Derived: cardiovascular biological age
        val cvAge = cvAgeCalc.compute(
            chronologicalAge = profile.ageYears,
            rhrBpm = effectiveRestingHr,
            vo2MaxMlKgMin = displayVo2,
        )

        // Derived: Karvonen HR zones (personalized to YOUR HRR, not just %max)
        val karvonenZones = if (effectiveRestingHr != null && effectiveMaxHr > effectiveRestingHr) {
            karvonenCalc.compute(effectiveMaxHr, effectiveRestingHr)
        } else null

        BioLabSnapshot(
            computedAtMs = System.currentTimeMillis(),
            healthConnectAvailable = true,
            chronologicalAge = profile.ageYears,
            bodyWeightKg = effectiveWeightKg,
            heightCm = profile.heightCm,
            bmi = computeBmi(effectiveWeightKg, profile.heightCm),

            // Cardiovascular
            currentHrBpm = hrSamplesToday.lastOrNull(),
            restingHrBpm = effectiveRestingHr,
            maxHrBpm = effectiveMaxHr,
            maxHrAutoDetected = hrAnalysis30d.proxyMaxHrBpm != null,
            hrvProxyMs = hrAnalysisToday.proxyHrvSdMs,
            hrRecords7d = hrSamples30d.size,

            // Recovery
            sleepLastNightHours = sleepLastNightHours,
            spo2Percent = spo2LastValue,

            // Derived
            vo2MaxConsensus = displayVo2,
            vo2MaxHrBased = vo2.hrBased,
            vo2MaxPowerBased = vo2.powerBased,
            vo2MaxFromSamsung = samsungVo2Max != null,
            vo2MaxClassification = vo2.classification,
            biologicalAge = cvAge.biologicalAge,
            biologicalAgeDelta = cvAge.deltaYears,
            biologicalAgeVerdict = cvAge.verdict,
            karvonenZones = karvonenZones,

            // Activity
            stepsToday = stepsToday,
            distanceTodayMeters = distanceTodayMeters,
            totalCaloriesToday = totalCalsToday,
            activeCaloriesToday = activeCalsToday,
            exerciseSessionsToday = exerciseToday,
            ftpWatts = profile.ftpWatts,
        )
    }

    private fun computeBmi(weightKg: Float, heightCm: Int): Float? {
        if (heightCm <= 0 || weightKg <= 0f) return null
        val heightM = heightCm / 100f
        return weightKg / (heightM * heightM)
    }

    // ---- Health Connect query helpers ----

    private suspend fun readHrSamples(
        client: HealthConnectClient,
        granted: Set<String>,
        start: Instant,
        end: Instant,
    ): List<Int> {
        if (HealthPermission.getReadPermission(HeartRateRecord::class) !in granted) return emptyList()
        return runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    ascendingOrder = false,
                    pageSize = 5_000,
                ),
            ).records
                .flatMap { it.samples }
                .map { it.beatsPerMinute.toInt() }
        }.getOrDefault(emptyList())
    }

    private suspend fun readLastNightSleep(
        client: HealthConnectClient,
        granted: Set<String>,
        now: Instant,
    ): Float? {
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted) return null
        return runCatching {
            val start = now.minus(Duration.ofHours(24))
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
            if (totalMs <= 0) null else (totalMs / 3_600_000f)
        }.onFailure { Log.w(TAG, "sleep read failed", it) }.getOrNull()
    }

    private suspend fun readLatestSpo2(
        client: HealthConnectClient,
        granted: Set<String>,
        start: Instant,
        end: Instant,
    ): Float? {
        if (HealthPermission.getReadPermission(OxygenSaturationRecord::class) !in granted) return null
        return runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = OxygenSaturationRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    ascendingOrder = false,
                    pageSize = 10,
                ),
            ).records.firstOrNull()?.percentage?.value?.toFloat()
        }.onFailure { Log.w(TAG, "SpO2 read failed", it) }.getOrNull()
    }

    private suspend fun readStepsCount(
        client: HealthConnectClient,
        granted: Set<String>,
        start: Instant,
        end: Instant,
    ): Int? {
        if (HealthPermission.getReadPermission(StepsRecord::class) !in granted) return null
        return runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    ascendingOrder = false,
                ),
            ).records.sumOf { it.count.toInt() }
        }.onFailure { Log.w(TAG, "steps read failed", it) }.getOrNull()
    }

    private suspend fun readDistanceMeters(
        client: HealthConnectClient,
        granted: Set<String>,
        start: Instant,
        end: Instant,
    ): Float? {
        if (HealthPermission.getReadPermission(DistanceRecord::class) !in granted) return null
        return runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = DistanceRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    ascendingOrder = false,
                ),
            ).records.sumOf { it.distance.inMeters }.toFloat()
        }.onFailure { Log.w(TAG, "distance read failed", it) }.getOrNull()
    }

    private suspend fun readTotalCaloriesKcal(
        client: HealthConnectClient,
        granted: Set<String>,
        start: Instant,
        end: Instant,
    ): Float? = readCalories(client, granted, start, end, TotalCaloriesBurnedRecord::class) {
        (it as TotalCaloriesBurnedRecord).energy.inKilocalories
    }

    private suspend fun readActiveCaloriesKcal(
        client: HealthConnectClient,
        granted: Set<String>,
        start: Instant,
        end: Instant,
    ): Float? = readCalories(client, granted, start, end, ActiveCaloriesBurnedRecord::class) {
        (it as ActiveCaloriesBurnedRecord).energy.inKilocalories
    }

    private suspend fun <T : androidx.health.connect.client.records.Record> readCalories(
        client: HealthConnectClient,
        granted: Set<String>,
        start: Instant,
        end: Instant,
        recordClass: KClass<T>,
        extractor: (Any) -> Double,
    ): Float? {
        if (HealthPermission.getReadPermission(recordClass) !in granted) return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val erased = recordClass as KClass<androidx.health.connect.client.records.Record>
            client.readRecords(
                ReadRecordsRequest(
                    recordType = erased,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    ascendingOrder = false,
                ),
            ).records.sumOf { extractor(it) }.toFloat()
        }.onFailure { Log.w(TAG, "calorie read failed", it) }.getOrNull()
    }

    private suspend fun readExerciseSessionCount(
        client: HealthConnectClient,
        granted: Set<String>,
        start: Instant,
        end: Instant,
    ): Int? {
        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) !in granted) return null
        return runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    ascendingOrder = false,
                ),
            ).records.size
        }.onFailure { Log.w(TAG, "exercise read failed", it) }.getOrNull()
    }

    private suspend fun readLatestVo2Max(
        client: HealthConnectClient,
        granted: Set<String>,
        start: Instant,
        end: Instant,
    ): Float? {
        if (HealthPermission.getReadPermission(Vo2MaxRecord::class) !in granted) return null
        return runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = Vo2MaxRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    ascendingOrder = false,
                    pageSize = 1,
                ),
            ).records.firstOrNull()?.vo2MillilitersPerMinuteKilogram?.toFloat()
        }.onFailure { Log.w(TAG, "VO2 max read failed", it) }.getOrNull()
    }

    private suspend fun readLatestWeight(
        client: HealthConnectClient,
        granted: Set<String>,
        start: Instant,
        end: Instant,
    ): Float? {
        if (HealthPermission.getReadPermission(WeightRecord::class) !in granted) return null
        return runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    ascendingOrder = false,
                    pageSize = 1,
                ),
            ).records.firstOrNull()?.weight?.inKilograms?.toFloat()
        }.onFailure { Log.w(TAG, "weight read failed", it) }.getOrNull()
    }

    companion object {
        private const val TAG = "URUJ-BioLab"
    }
}

data class BioLabSnapshot(
    val computedAtMs: Long,
    val healthConnectAvailable: Boolean,
    val chronologicalAge: Int,
    val bodyWeightKg: Float,
    val heightCm: Int,
    val bmi: Float?,

    // Cardiovascular
    val currentHrBpm: Int? = null,
    val restingHrBpm: Int? = null,
    val maxHrBpm: Int = 190,
    val maxHrAutoDetected: Boolean = false,
    val hrvProxyMs: Float? = null,
    val hrRecords7d: Int = 0,

    // Recovery
    val sleepLastNightHours: Float? = null,
    val spo2Percent: Float? = null,

    // Derived
    val vo2MaxConsensus: Float? = null,
    val vo2MaxHrBased: Float? = null,
    val vo2MaxPowerBased: Float? = null,
    val vo2MaxFromSamsung: Boolean = false,
    val vo2MaxClassification: String = "—",
    val biologicalAge: Int? = null,
    val biologicalAgeDelta: Int? = null,
    val biologicalAgeVerdict: String = "",
    val karvonenZones: KarvonenZonesCalculator.Result? = null,

    // Activity (today)
    val stepsToday: Int? = null,
    val distanceTodayMeters: Float? = null,
    val totalCaloriesToday: Float? = null,
    val activeCaloriesToday: Float? = null,
    val exerciseSessionsToday: Int? = null,
    val ftpWatts: Int = 200,
)
