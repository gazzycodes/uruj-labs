package com.uruj.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.uruj.power.HrAnalyzer
import com.uruj.power.HrRecoveryCalculator
import com.uruj.power.KarvonenZonesCalculator
import com.uruj.power.SleepingRhrCalculator
import com.uruj.power.VO2MaxCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * Aggregates the cycling-training metrics Bio Lab needs into one snapshot.
 *
 * Scope after v0.4.0 identity reset: Bio Lab is the cycling-training brain,
 * not a wellness dashboard. We only surface metrics that are:
 *   (a) cycling-training-relevant AND
 *   (b) not better-shown by Samsung Health.
 *
 * Wellness metrics Samsung owns (sleep staging, daily activity, body comp,
 * stress, today's HR min/max) are NOT pulled here — the UI deep-links to
 * Samsung Health for those. See [[reference_cut_features_v0_4]] for the
 * full audit + which future hardware unlocks re-adding cut features.
 *
 * Every input is optional, every metric reports null instead of a wrong
 * number when data isn't available.
 */
class BioLabRepository(context: Context) {

    private val appContext = context.applicationContext
    private val profileStore = RiderProfileStore(context)
    private val rideHistory = RideHistoryRepository(context)
    private val hrAnalyzer = HrAnalyzer()
    private val vo2Calc = VO2MaxCalculator()
    private val karvonenCalc = KarvonenZonesCalculator()
    private val hrrCalc = HrRecoveryCalculator()
    private val sleepingRhrCalc = SleepingRhrCalculator()
    // v0.7.0 — read RMSSD HRV from continuous BLE NDJSON
    private val continuousBiometric = ContinuousBiometricRepository(appContext)
    private val lastSleepReader = LastSleepReader()

    suspend fun snapshot(): BioLabSnapshot = withContext(Dispatchers.IO) {
        val profile = profileStore.current()
        val sdkOk = HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE
        if (!sdkOk) {
            return@withContext BioLabSnapshot(
                computedAtMs = System.currentTimeMillis(),
                healthConnectAvailable = false,
                chronologicalAge = profile.ageYears,
                bodyWeightKg = profile.riderWeightKg,
            )
        }
        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }.getOrNull()
            ?: return@withContext BioLabSnapshot(
                computedAtMs = System.currentTimeMillis(),
                healthConnectAvailable = false,
                chronologicalAge = profile.ageYears,
                bodyWeightKg = profile.riderWeightKg,
            )
        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())

        val now = Instant.now()
        val monthAgo = now.minus(Duration.ofDays(30))

        // 30d HR samples power: (a) max HR auto-detect from hardest tracked effort,
        // (b) sleeping-RHR computation, (c) HRR1 recovery readings.
        val hrTimed30d = readHrSamplesTimestamped(client, granted, monthAgo, now)
        val hrSamples30d = hrTimed30d.map { it.second }
        val hrAnalysis30d = hrAnalyzer.analyze(hrSamples30d)

        // Athletic RHR — sleep-window median. The legitimate true RHR signal.
        // Powers Karvonen zones + VO2 max formula even if not displayed in
        // its own card.
        val sleepWindows30d = readSleepWindows(client, granted, monthAgo, now)
        val sleepingRhr = sleepingRhrCalc.compute(hrTimed30d, sleepWindows30d)

        // Athletic max — the 30d peak across all tracked effort. Cycling-relevant
        // (the ceiling number lets riders see how close training has pushed them).
        val highestHr30d = hrSamples30d.filter { it in 35..250 }.maxOrNull()

        // Samsung's own VO2 max if HC has it — shown side-by-side with ours in
        // the reframed VO2 card per the v0.4.0 transparency moat.
        val samsungVo2Max = readLatestVo2Max(client, granted, monthAgo, now)
        val latestWeight = readLatestWeight(client, granted, monthAgo, now)

        // Exercise session end times for HRR1. URUJ-recorded rides also feed in
        // — closes the gap where the rider records with URUJ but doesn't start
        // a band workout (Samsung blind to the session). Dedupe within ±60s.
        val exerciseSessionEnds30d = readExerciseSessionEnds(client, granted, monthAgo, now)
        val urujRideEnds30d = rideHistory.listAll()
            .map { Instant.ofEpochMilli(it.endedAtMs) }
            .filter { !it.isBefore(monthAgo) && !it.isAfter(now) }
        val combinedSessionEnds = (exerciseSessionEnds30d + urujRideEnds30d)
            .sortedBy { it.epochSecond }
            .fold(mutableListOf<Instant>()) { acc, instant ->
                val last = acc.lastOrNull()
                if (last == null || Duration.between(last, instant).abs() > Duration.ofSeconds(60)) {
                    acc += instant
                }
                acc
            }

        val effectiveWeightKg = latestWeight ?: profile.riderWeightKg
        // Max-HR effective: profile is the floor (user-declared); auto-detect
        // wins when a real effort exceeded it. Lower auto-detect never shrinks.
        val autoDetectedMaxHr = hrAnalysis30d.proxyMaxHrBpm?.takeIf { it > 100 } ?: 0
        val effectiveMaxHr = maxOf(profile.maxHrBpm, autoDetectedMaxHr)

        if (autoDetectedMaxHr > profile.maxHrBpm) {
            runCatching { profileStore.save(profile.copy(maxHrBpm = autoDetectedMaxHr)) }
                .onFailure { Log.w(TAG, "max HR write-back failed", it) }
        }

        val maxHrIsFormulaDefault = profile.maxHrBpm == (220 - profile.ageYears)
        val maxHrCameFromAutoDetect =
            autoDetectedMaxHr > profile.maxHrBpm || !maxHrIsFormulaDefault

        val effectiveRestingHr = sleepingRhr?.medianBpm
        val restingHrSourceLabel = sleepingRhr?.let {
            val nights = it.nightsCount
            val plural = if (nights == 1) "night" else "nights"
            "athletic RHR — median of $nights sleep $plural"
        } ?: "no sleep data — wear band overnight"

        val ftpIsLikelyUntested = profile.ftpWatts == 200
        val ftpForVo2: Int? = if (ftpIsLikelyUntested) null else profile.ftpWatts

        val vo2 = vo2Calc.compute(
            hrMaxBpm = effectiveMaxHr,
            hrRestBpm = effectiveRestingHr,
            ftpWatts = ftpForVo2,
            bodyWeightKg = effectiveWeightKg,
        )
        // URUJ's number is shown alongside Samsung's in the VO2 card — both
        // visible, neither hidden. Transparency is the moat.
        val displayVo2 = vo2.consensus

        val karvonenZones = if (effectiveRestingHr != null && effectiveMaxHr > effectiveRestingHr) {
            karvonenCalc.compute(effectiveMaxHr, effectiveRestingHr)
        } else null

        // HR Recovery (HRR1) — Cole NEJM 1999. Stronger CV mortality predictor
        // than VO2 max alone. Samsung doesn't expose this number.
        val hrr = hrrCalc.compute(combinedSessionEnds, hrTimed30d)
        val hrr1AthleteContext = computeHrr1AthleteContext(hrr?.medianHrr1, vo2.classification)

        // v0.7.0 — Autonomic HRV from 24/7 BLE continuous capture. Compute over
        // last sleep window for the cleanest signal (parasympathetic dominance);
        // fall back to last 24h if no sleep data. Null when 24/7 monitoring
        // hasn't run yet (Continuous NDJSON empty for this window).
        val sleepWindow = lastSleepReader.read(client, granted)
        val hrvWindowStart: Instant
        val hrvWindowEnd: Instant
        if (sleepWindow != null) {
            hrvWindowStart = sleepWindow.startedAt
            hrvWindowEnd = sleepWindow.endedAt
        } else {
            hrvWindowEnd = now
            hrvWindowStart = now.minus(Duration.ofHours(24))
        }
        val autonomicHrv = continuousBiometric.computeHrvForWindow(hrvWindowStart, hrvWindowEnd)
        val autonomicSampleCount = autonomicHrv?.sampleCount ?: 0
        val autonomicWindowCount = autonomicHrv?.windowCount ?: 0
        val autonomicWindowLabel = if (sleepWindow != null) "last sleep" else "last 24h"
        // Count days of overnight HRV captured — drives "baseline building" UX
        val autonomicDaysOfData = continuousBiometric.daysWithOvernightHrvIn(7)

        BioLabSnapshot(
            computedAtMs = System.currentTimeMillis(),
            healthConnectAvailable = true,
            chronologicalAge = profile.ageYears,
            bodyWeightKg = effectiveWeightKg,

            // Heart Rate (cycling-relevant only)
            restingHrBpm = effectiveRestingHr,
            restingHrSourceLabel = restingHrSourceLabel,
            highestHr30d = highestHr30d,
            maxHrBpm = effectiveMaxHr,
            maxHrAutoDetected = maxHrCameFromAutoDetect,

            // Derived (cycling-relevant)
            vo2MaxConsensus = displayVo2,
            vo2MaxHrBased = vo2.hrBased,
            vo2MaxPowerBased = vo2.powerBased,
            vo2MaxFromSamsung = samsungVo2Max,
            vo2MaxClassification = vo2.classification,
            karvonenZones = karvonenZones,
            ftpIsLikelyUntested = ftpIsLikelyUntested,
            ftpWatts = profile.ftpWatts,

            // HRR1 — peer-reviewed cardio metric
            hrr1Median = hrr?.medianHrr1,
            hrr1Classification = hrr?.medianClassification,
            hrr1SampleCount = hrr?.samples?.size ?: 0,
            hrr1AthleteContext = hrr1AthleteContext,
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

            // v0.7.0 — Autonomic HRV from 24/7 BLE continuous capture
            autonomicRmssdMs = autonomicHrv?.rmssdMs,
            autonomicSdnnMs = autonomicHrv?.sdnnMs,
            autonomicPnn50Pct = autonomicHrv?.pnn50Percent,
            autonomicMeanHrBpm = autonomicHrv?.meanHrBpm,
            autonomicSampleCount = autonomicSampleCount,
            autonomicWindowLabel = autonomicWindowLabel,
            autonomicWindowCount = autonomicWindowCount,
            autonomicDaysOfData = autonomicDaysOfData,
        )
    }

    /**
     * Athlete-aware interpretation of HRR1. Cole's thresholds (≥18 / 12-17 / <12)
     * came from a clinical/general population. Trained athletes typically see
     * substantially higher recovery — a "Cole-Excellent" 18 bpm might actually
     * flag concern in an elite endurance rider. Maps raw HRR1 against the user's
     * VO₂ fitness tier and produces a context line shown alongside the universal
     * Cole classification. Ranges from Borresen & Lambert 2008 + subsequent
     * trained-athlete HRR norms.
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

/**
 * Bio Lab cycling-training snapshot (v0.4.0 slim).
 *
 * What's here: max HR, athletic RHR, HR Reserve inputs, 30d peak, VO2 (ours +
 * Samsung's), Karvonen zones, HRR1 with athlete context, FTP status.
 *
 * What was cut in v0.4.0 (see [[reference_cut_features_v0_4]] for why + what
 * hardware un-cuts them): stress load, sleep/SpO2 card data, body composition,
 * today's activity totals, fitness age, today HR min/max, HRV proxy fields.
 */
data class BioLabSnapshot(
    val computedAtMs: Long,
    val healthConnectAvailable: Boolean,
    val chronologicalAge: Int,
    val bodyWeightKg: Float,

    // Heart Rate (cycling-relevant only)
    /** Athletic RHR — sleep-window median, kept for HR Reserve display + Karvonen + VO2. */
    val restingHrBpm: Int? = null,
    val restingHrSourceLabel: String = "",
    /** Hardest tracked effort in 30d — the athletic ceiling. */
    val highestHr30d: Int? = null,
    val maxHrBpm: Int = 190,
    val maxHrAutoDetected: Boolean = false,

    // Derived
    val vo2MaxConsensus: Float? = null,
    val vo2MaxHrBased: Float? = null,
    val vo2MaxPowerBased: Float? = null,
    /** Samsung's own VO2 max if HC has it — displayed side-by-side with URUJ's. */
    val vo2MaxFromSamsung: Float? = null,
    val vo2MaxClassification: String = "—",
    val karvonenZones: KarvonenZonesCalculator.Result? = null,
    val ftpIsLikelyUntested: Boolean = true,
    val ftpWatts: Int = 200,

    // HRR1 — peer-reviewed cardio metric Samsung doesn't expose
    val hrr1Median: Int? = null,
    val hrr1Classification: String? = null,
    val hrr1SampleCount: Int = 0,
    val hrr1AthleteContext: String? = null,
    val hrr1RecentSamples: List<HrrSample> = emptyList(),

    // v0.7.0 — Autonomic HRV from 24/7 BLE chest strap RR data
    /** Real RMSSD HRV from BLE chest strap RR intervals (ms). Higher = better
     *  parasympathetic recovery. Null when 24/7 monitoring hasn't captured data
     *  for the relevant window yet. */
    val autonomicRmssdMs: Float? = null,
    /** SDNN (overall HRV) in ms. */
    val autonomicSdnnMs: Float? = null,
    /** pNN50 percentage. */
    val autonomicPnn50Pct: Float? = null,
    /** Mean HR over the HRV window (sleep / last 24h). */
    val autonomicMeanHrBpm: Float? = null,
    /** Number of clean RR intervals used in the HRV calc. */
    val autonomicSampleCount: Int = 0,
    /** Human label of the window the HRV was computed over: "last sleep" or "last 24h". */
    val autonomicWindowLabel: String = "",
    /** Number of 5-min sub-windows the windowed RMSSD was aggregated over.
     *  ≥3 required for a valid result (HrvCalculator.MIN_WINDOWS). Surfaces
     *  on UI as "median of N windows" so the rider sees the methodology. */
    val autonomicWindowCount: Int = 0,
    /** Days of overnight HRV captured in last 7 days. Drives baseline-building
     *  UX: <7 days → show "baseline building" notice on the Autonomic card. */
    val autonomicDaysOfData: Int = 0,
)

/** A single qualifying HRR1 reading from one exercise session. */
data class HrrSample(
    val endTimeMs: Long,
    val hrr1Bpm: Int,
    val peakBpm: Int,
)
