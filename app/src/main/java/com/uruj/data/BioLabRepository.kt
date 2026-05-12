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
import androidx.health.connect.client.records.RestingHeartRateRecord
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

        // Pull last 30d of HR samples WITH timestamps — both for max-HR detection
        // (needs broad range to catch hard efforts) and for the RHR/HRV proxy
        // windows. Timestamps also feed the sleeping-RHR filter below.
        val hrTimed30d = readHrSamplesTimestamped(client, granted, monthAgo, now)
        val hrTimedToday = readHrSamplesTimestamped(client, granted, dayAgo, now)
        val hrSamples30d = hrTimed30d.map { it.second }
        val hrSamplesToday = hrTimedToday.map { it.second }
        val hrAnalysisToday = hrAnalyzer.analyze(hrSamplesToday)
        val hrAnalysis30d = hrAnalyzer.analyze(hrSamples30d)

        // Sleeping-RHR filter — the legitimate true RHR signal. Daytime HR
        // samples (sitting at a desk = 60-70 bpm) pull the bottom-percentile
        // proxy upward. Filtering to sleep-window samples only captures the
        // genuine deep-rest HR that elite-endurance athletes show.
        val sleepWindows30d = readSleepWindows(client, granted, monthAgo, now)
        val sleepingRhr = computeSleepingRhr(hrTimed30d, sleepWindows30d)

        // Today's absolute minimum HR — direct ground-truth match to Samsung
        // Health's "min today" display. Useful as a cross-check next to our
        // derived sleeping RHR (different definitions, both should be sane).
        val lowestHrToday = hrSamplesToday.filter { it >= 35 }.minOrNull()

        // Samsung's own daily RHR record — if the band writes one, that's the
        // authoritative platform number. Show it alongside ours for full
        // transparency; user shouldn't have to switch apps to compare.
        val samsungDirectRhr = readLatestRestingHr(client, granted, weekAgo, now)

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
        // Max-HR: profile value is the floor (user-declared belief). Auto-detect
        // only wins when the rider has actually exceeded their declared max in a
        // real effort — that's a legitimate signal the profile needs updating.
        // If auto-detect is *lower* (no all-out efforts yet) we trust the rider.
        val autoDetectedMaxHr = hrAnalysis30d.proxyMaxHrBpm?.takeIf { it > 100 } ?: 0
        val effectiveMaxHr = maxOf(profile.maxHrBpm, autoDetectedMaxHr)
        val maxHrCameFromAutoDetect = autoDetectedMaxHr > profile.maxHrBpm

        // RHR priority: sleeping RHR (true rest) > today's proxy > 30d proxy.
        // The label tells the UI which source is in play, so the rider knows
        // whether they're looking at a polished or a coarse number.
        val effectiveRestingHr = sleepingRhr?.bpm
            ?: hrAnalysisToday.proxyRestingHrBpm
            ?: hrAnalysis30d.proxyRestingHrBpm
        val restingHrSourceLabel = when {
            sleepingRhr != null -> {
                val nights = sleepingRhr.nights
                val plural = if (nights == 1) "night" else "nights"
                "athletic RHR — median of $nights sleep $plural"
            }
            hrAnalysisToday.proxyRestingHrBpm != null ->
                "proxy from ${hrSamplesToday.size} HR samples (today)"
            hrAnalysis30d.proxyRestingHrBpm != null ->
                "proxy from ${hrSamples30d.size} HR samples (30d)"
            else -> "no data yet"
        }

        // FTP heuristic: 200W is the placeholder default. If the user has never
        // opened Profile to set their own FTP (or set it to something other than
        // 200), we assume FTP is untested and skip the power-based VO2 formula —
        // a fabricated FTP would pollute the consensus by 5-15 mL/kg/min and
        // mislead more than it informs. Honesty floor beats fictional ceiling.
        val ftpIsLikelyUntested = profile.ftpWatts == 200
        val ftpForVo2: Int? = if (ftpIsLikelyUntested) null else profile.ftpWatts

        // Derived: VO2 max (both formulas, cross-validated). Power-based is
        // suppressed when FTP is at default — consensus falls back to HR-only.
        val vo2 = vo2Calc.compute(
            hrMaxBpm = effectiveMaxHr,
            hrRestBpm = effectiveRestingHr,
            ftpWatts = ftpForVo2,
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
            restingHrSourceLabel = restingHrSourceLabel,
            restingHrFromSleep = sleepingRhr != null,
            lowestHrToday = lowestHrToday,
            samsungDirectRhrBpm = samsungDirectRhr,
            maxHrBpm = effectiveMaxHr,
            maxHrAutoDetected = maxHrCameFromAutoDetect,
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
            ftpIsLikelyUntested = ftpIsLikelyUntested,

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

    private suspend fun readHrSamplesTimestamped(
        client: HealthConnectClient,
        granted: Set<String>,
        start: Instant,
        end: Instant,
    ): List<Pair<Instant, Int>> {
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
                .map { it.time to it.beatsPerMinute.toInt() }
        }.getOrDefault(emptyList())
    }

    /** Pulls every sleep session in the window (start/end times only). */
    private suspend fun readSleepWindows(
        client: HealthConnectClient,
        granted: Set<String>,
        start: Instant,
        end: Instant,
    ): List<Pair<Instant, Instant>> {
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted) return emptyList()
        return runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    ascendingOrder = false,
                ),
            ).records.map { it.startTime to it.endTime }
        }.onFailure { Log.w(TAG, "sleep windows read failed", it) }
            .getOrDefault(emptyList())
    }

    /**
     * Athletic sleeping RHR — for each detected sleep night, find the minimum
     * HR sample within that night (with a < 35 bpm glitch filter), then return
     * the median of those nightly mins. This matches Garmin / Whoop's definition
     * of resting HR — the lowest sustained HR during deep sleep, smoothed over
     * the observation window.
     *
     * v0.2.2 used a percentile-of-all-sleep-samples approach (p10) which was
     * over-conservative for sparse spot-check data like Fit Band 3. For a rider
     * whose Samsung daily-min is ~50 and historic mins run in the 40s, p10
     * landed at 55 — visibly higher than the band's own captured minima.
     * Per-night min + cross-night median fixes both problems: it tracks the
     * band's actual lows, and the median makes a single-night sensor weirdness
     * a non-event.
     */
    private fun computeSleepingRhr(
        timedSamples: List<Pair<Instant, Int>>,
        sleepWindows: List<Pair<Instant, Instant>>,
    ): SleepingRhrResult? {
        if (sleepWindows.isEmpty() || timedSamples.isEmpty()) return null

        val nightlyMins = sleepWindows.mapNotNull { (start, end) ->
            val nightSamples = timedSamples
                .filter { (t, bpm) -> !t.isBefore(start) && !t.isAfter(end) && bpm >= 35 }
                .map { it.second }
            // ≥5 samples per night to trust the min — under that, a stray early-
            // morning waking sample could masquerade as a deep-sleep low.
            if (nightSamples.size < 5) null else nightSamples.min()
        }
        if (nightlyMins.isEmpty()) return null

        val sortedMins = nightlyMins.sorted()
        val median = if (sortedMins.size % 2 == 1) {
            sortedMins[sortedMins.size / 2]
        } else {
            (sortedMins[sortedMins.size / 2 - 1] + sortedMins[sortedMins.size / 2]) / 2
        }
        return SleepingRhrResult(bpm = median, nights = nightlyMins.size)
    }

    private data class SleepingRhrResult(val bpm: Int, val nights: Int)

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

    private suspend fun readLatestRestingHr(
        client: HealthConnectClient,
        granted: Set<String>,
        start: Instant,
        end: Instant,
    ): Int? {
        if (HealthPermission.getReadPermission(RestingHeartRateRecord::class) !in granted) return null
        return runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = RestingHeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    ascendingOrder = false,
                    pageSize = 1,
                ),
            ).records.firstOrNull()?.beatsPerMinute?.toInt()
        }.onFailure { Log.w(TAG, "Samsung RHR read failed", it) }.getOrNull()
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
    /** Human-readable source label for RHR — "athletic RHR from N sleep nights" or "proxy from N HR samples". */
    val restingHrSourceLabel: String = "",
    /** True when restingHrBpm came from the sleeping-RHR filter (high-confidence). */
    val restingHrFromSleep: Boolean = false,
    /** Lowest HR sample recorded today — matches Samsung's "min today" display. */
    val lowestHrToday: Int? = null,
    /** Samsung Health's own daily RestingHeartRateRecord, if it writes one. */
    val samsungDirectRhrBpm: Int? = null,
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
    /** True when FTP is at the placeholder default 200W — power-based VO2 is suppressed. */
    val ftpIsLikelyUntested: Boolean = true,

    // Activity (today)
    val stepsToday: Int? = null,
    val distanceTodayMeters: Float? = null,
    val totalCaloriesToday: Float? = null,
    val activeCaloriesToday: Float? = null,
    val exerciseSessionsToday: Int? = null,
    val ftpWatts: Int = 200,
)
