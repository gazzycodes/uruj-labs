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
import com.uruj.power.HrRecoveryCalculator
import com.uruj.power.KarvonenZonesCalculator
import com.uruj.power.SleepingRhrCalculator
import com.uruj.power.VO2MaxCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
    private val rideHistory = RideHistoryRepository(context)
    private val hrAnalyzer = HrAnalyzer()
    private val vo2Calc = VO2MaxCalculator()
    private val cvAgeCalc = CardiovascularAgeCalculator()
    private val karvonenCalc = KarvonenZonesCalculator()
    private val hrrCalc = HrRecoveryCalculator()
    private val sleepingRhrCalc = SleepingRhrCalculator()

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
        // "Today" = calendar-day boundary (local-timezone midnight to now), to
        // match what Samsung Health shows when the user looks at "min today"
        // or "max today". v0.2.5 used a rolling 24h window which produced a
        // misleading "matches Samsung's max" copy at hours after midnight.
        val todayStart = LocalDate.now(ZoneId.systemDefault())
            .atStartOfDay(ZoneId.systemDefault()).toInstant()

        // Pull last 30d of HR samples WITH timestamps — both for max-HR detection
        // (needs broad range to catch hard efforts) and for the RHR/HRV proxy
        // windows. Timestamps also feed the sleeping-RHR filter below.
        val hrTimed30d = readHrSamplesTimestamped(client, granted, monthAgo, now)
        val hrTimedToday = readHrSamplesTimestamped(client, granted, todayStart, now)
        val hrSamples30d = hrTimed30d.map { it.second }
        val hrSamplesToday = hrTimedToday.map { it.second }
        val hrAnalysisToday = hrAnalyzer.analyze(hrSamplesToday)
        val hrAnalysis30d = hrAnalyzer.analyze(hrSamples30d)

        // Sleeping-RHR filter — the legitimate true RHR signal. Daytime HR
        // samples (sitting at a desk = 60-70 bpm) pull the bottom-percentile
        // proxy upward. Filtering to sleep-window samples only captures the
        // genuine deep-rest HR that elite-endurance athletes show.
        val sleepWindows30d = readSleepWindows(client, granted, monthAgo, now)
        val sleepingRhr = sleepingRhrCalc.compute(hrTimed30d, sleepWindows30d)

        // Today's absolute minimum HR — direct ground-truth match to Samsung
        // Health's "min today" display. Useful as a cross-check next to our
        // derived sleeping RHR (different definitions, both should be sane).
        val lowestHrToday = hrSamplesToday.filter { it >= 35 }.minOrNull()
        // Today's observed max + 30d max — shows the gap between Samsung's
        // observed max (sub-maximal effort) and our formula-based max ceiling.
        // 250 bpm filter rules out sensor-glitch spikes.
        val highestHrToday = hrSamplesToday.filter { it in 35..250 }.maxOrNull()
        val highestHr30d = hrSamples30d.filter { it in 35..250 }.maxOrNull()

        // Samsung's own daily RHR record — if the band writes one, that's the
        // authoritative platform number. Show it alongside ours for full
        // transparency; user shouldn't have to switch apps to compare.
        val samsungDirectRhr = readLatestRestingHr(client, granted, weekAgo, now)

        val sleepLastNightHours = readLastNightSleep(client, granted, now)
        val spo2LastValue = readLatestSpo2(client, granted, weekAgo, now)
        val stepsToday = readStepsCount(client, granted, todayStart, now)
        val distanceTodayMeters = readDistanceMeters(client, granted, todayStart, now)
        val totalCalsToday = readTotalCaloriesKcal(client, granted, todayStart, now)
        val activeCalsToday = readActiveCaloriesKcal(client, granted, todayStart, now)
        val exerciseToday = readExerciseSessionCount(client, granted, todayStart, now)
        val exerciseSessionEnds30d = readExerciseSessionEnds(client, granted, monthAgo, now)
        // URUJ-recorded ride end times — every ride we've logged contributes
        // to HRR1 regardless of whether Samsung also detected it as a workout.
        // This closes the gap where the user records with URUJ but doesn't
        // start a band workout, leaving Samsung blind to the session.
        val urujRideEnds30d = rideHistory.listAll()
            .map { Instant.ofEpochMilli(it.endedAtMs) }
            .filter { !it.isBefore(monthAgo) && !it.isAfter(now) }
        val combinedSessionEnds = (exerciseSessionEnds30d + urujRideEnds30d)
            // Dedupe within ±60s — Samsung often writes its own session for a
            // ride URUJ also recorded; double-counting would skew the median.
            .sortedBy { it.epochSecond }
            .fold(mutableListOf<Instant>()) { acc, instant ->
                val last = acc.lastOrNull()
                if (last == null || Duration.between(last, instant).abs() > Duration.ofSeconds(60)) {
                    acc += instant
                }
                acc
            }
        val samsungVo2Max = readLatestVo2Max(client, granted, monthAgo, now)
        val latestWeight = readLatestWeight(client, granted, monthAgo, now)

        val effectiveWeightKg = latestWeight ?: profile.riderWeightKg
        // Max-HR: profile value is the floor (user-declared belief). Auto-detect
        // only wins when the rider has actually exceeded their declared max in a
        // real effort — that's a legitimate signal the profile needs updating.
        // If auto-detect is *lower* (no all-out efforts yet) we trust the rider.
        val autoDetectedMaxHr = hrAnalysis30d.proxyMaxHrBpm?.takeIf { it > 100 } ?: 0
        val effectiveMaxHr = maxOf(profile.maxHrBpm, autoDetectedMaxHr)

        // Write-back: if a ride observed a higher max HR than the stored
        // profile value, persist the new max. Without this, the Profile screen
        // keeps showing the formula default (220-age) even after the rider
        // demonstrably hits a higher number in a real effort. Per the v0.2.4
        // sync policy: real observation wins where it exists.
        if (autoDetectedMaxHr > profile.maxHrBpm) {
            runCatching { profileStore.save(profile.copy(maxHrBpm = autoDetectedMaxHr)) }
                .onFailure { Log.w(TAG, "max HR write-back failed", it) }
        }

        // maxHrCameFromAutoDetect: true when the effective max HR is NOT
        // the bare 220-age formula default — i.e., either the user manually
        // set it OR a ride bumped it (which the write-back above ensures
        // is persisted next snapshot). Drives the "auto-detected vs formula"
        // copy on the Heart Rate card.
        val maxHrIsFormulaDefault = profile.maxHrBpm == (220 - profile.ageYears)
        val maxHrCameFromAutoDetect =
            autoDetectedMaxHr > profile.maxHrBpm || !maxHrIsFormulaDefault

        // RHR priority: sleeping RHR (true rest) > today's proxy > 30d proxy.
        // The label tells the UI which source is in play, so the rider knows
        // whether they're looking at a polished or a coarse number.
        val effectiveRestingHr = sleepingRhr?.medianBpm
            ?: hrAnalysisToday.proxyRestingHrBpm
            ?: hrAnalysis30d.proxyRestingHrBpm
        val restingHrSourceLabel = when {
            sleepingRhr != null -> {
                val nights = sleepingRhr.nightsCount
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

        // Derived: HR Recovery (HRR1) — the actual peer-reviewed cardiovascular
        // health metric we can compute from existing ride/workout data. Cole et
        // al. NEJM 1999 + many follow-ups: a stronger mortality predictor than
        // VO₂ max or max HR alone. Cardiology-grade signal from consumer data.
        val hrr = hrrCalc.compute(combinedSessionEnds, hrTimed30d)
        val hrr1AthleteContext = computeHrr1AthleteContext(hrr?.medianHrr1, vo2.classification)

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
            highestHrToday = highestHrToday,
            highestHr30d = highestHr30d,
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

            // Heart Rate Recovery — the real peer-reviewed cardio metric
            hrr1Median = hrr?.medianHrr1,
            hrr1Classification = hrr?.medianClassification,
            hrr1SampleCount = hrr?.samples?.size ?: 0,
            hrr1AthleteContext = hrr1AthleteContext,
            // Cap at 3 most recent so the card stays compact as ride history grows.
            // Median in the hero number still uses ALL qualifying rides — this list
            // is purely a variance preview, not the data backing the metric.
            hrr1RecentSamples = hrr?.samples
                ?.sortedByDescending { it.sessionEnd }
                ?.take(3)
                ?.map {
                    HrrSample(
                        endTimeMs = it.sessionEnd.toEpochMilli(),
                        hrr1Bpm = it.hrr1Bpm,
                        peakBpm = it.effortPeakBpm,
                    )
                } ?: emptyList(),

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

    /**
     * Athlete-aware interpretation of HRR1. Cole's classification thresholds
     * (≥18 excellent / 12-17 average / <12 elevated risk) were derived from a
     * clinical/general population. Trained athletes typically see substantially
     * higher recovery — a "Cole-Excellent" 18 bpm might actually flag concern
     * in an elite endurance rider. This layer maps the raw HRR1 against the
     * user's VO₂ fitness tier and produces a context line the UI can show
     * alongside the universal Cole classification.
     *
     * The fitness-stratified ranges below come from sports-medicine literature
     * on autonomic recovery in trained athletes (Borresen & Lambert 2008 and
     * subsequent reviews on trained-athlete HRR norms).
     */
    private fun computeHrr1AthleteContext(hrr1: Int?, vo2Classification: String?): String? {
        if (hrr1 == null || vo2Classification == null) return null
        return when {
            vo2Classification.startsWith("Elite") -> when {
                hrr1 >= 22 -> "In the expected range for elite tier (22-30+ bpm). " +
                    "Confirms strong autonomic conditioning — recovery is matching aerobic capacity."
                hrr1 >= 18 -> "Below typical for elite tier (22-30+ bpm) despite Cole-Excellent rating. " +
                    "Could indicate fatigue, overtraining, or insufficient recovery. Worth tracking trend."
                else -> "Significantly below elite tier (22-30+ bpm). " +
                    "Strong recovery deficit — sleep, hydration, training load worth reviewing."
            }
            vo2Classification.startsWith("Excellent") -> when {
                hrr1 >= 20 -> "In the expected range for excellent tier (20-25 bpm). " +
                    "Well-conditioned autonomic recovery for your fitness level."
                hrr1 >= 15 -> "Slightly below expected for excellent tier (20-25 bpm). " +
                    "Monitor trend — could be transient fatigue or insufficient recovery."
                else -> "Below expected for your fitness tier (20-25 bpm). Recovery deficit possible."
            }
            vo2Classification.startsWith("Above average") ->
                "For above-average fitness, HRR1 typically falls in 18-22 bpm. Cole's thresholds apply directly."
            else ->
                "Cole's general-population thresholds apply: ≥18 excellent, 12-17 average, <12 elevated CV risk."
        }
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

    /** End-time of every exercise session in the window — feeds the HRR1 calc. */
    private suspend fun readExerciseSessionEnds(
        client: HealthConnectClient,
        granted: Set<String>,
        start: Instant,
        end: Instant,
    ): List<Instant> {
        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) !in granted) return emptyList()
        return runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    ascendingOrder = false,
                ),
            ).records.map { it.endTime }
        }.onFailure { Log.w(TAG, "exercise ends read failed", it) }
            .getOrDefault(emptyList())
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
    /** Highest HR sample today — Samsung's "max today" equivalent. */
    val highestHrToday: Int? = null,
    /** Highest HR over the 30-day observation window — your hardest tracked effort. */
    val highestHr30d: Int? = null,
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
    /** Median HRR1 across qualifying exercise sessions (peak ≥130 bpm) in 30d. */
    val hrr1Median: Int? = null,
    /** Cole-et-al-based classification of the median HRR1. */
    val hrr1Classification: String? = null,
    /** Number of qualifying sessions feeding the HRR1 median. */
    val hrr1SampleCount: Int = 0,
    /** Fitness-tier-aware interpretation of the HRR1 number (athlete vs general population). */
    val hrr1AthleteContext: String? = null,
    /** Up to 5 most recent qualifying HRR1 readings (latest first). UI shows them so
     *  the rider sees individual ride variance, not just the smoothed median. */
    val hrr1RecentSamples: List<HrrSample> = emptyList(),

    // Activity (today)
    val stepsToday: Int? = null,
    val distanceTodayMeters: Float? = null,
    val totalCaloriesToday: Float? = null,
    val activeCaloriesToday: Float? = null,
    val exerciseSessionsToday: Int? = null,
    val ftpWatts: Int = 200,
)

/** A single qualifying HRR1 reading from one exercise session. */
data class HrrSample(
    val endTimeMs: Long,
    val hrr1Bpm: Int,
    val peakBpm: Int,
)
