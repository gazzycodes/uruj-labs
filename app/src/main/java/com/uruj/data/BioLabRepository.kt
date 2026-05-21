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
import com.uruj.domain.CarInterpretation
import com.uruj.domain.CarResult
import com.uruj.power.CarDetector
import com.uruj.power.HrAnalyzer
import com.uruj.power.HrRecoveryCalculator
import com.uruj.power.KarvonenZonesCalculator
import com.uruj.power.SleepingRhrCalculator
import com.uruj.power.VO2MaxCalculator
import com.uruj.util.rethrowCancellation
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
    // v0.7.2 — Cortisol Awakening Response
    private val carRepo = CarRepository(appContext)
    private val carDetector = CarDetector()
    // v0.9.0 — disk-persisted HRR1 readings (snapshot architecture).
    // Bridges HC's 30-day retention so HRR1 history survives indefinitely.
    private val hrrSnapshots = HrrSnapshotRepository(appContext)
    // v0.9.1 — daily Athletic RHR snapshots. Same architecture as HRR1.
    private val rhrSnapshots = RhrSnapshotRepository(appContext)
    // v0.9.1 — daily VO2 max snapshots (URUJ + Samsung side-by-side).
    private val vo2Snapshots = Vo2SnapshotRepository(appContext)
    // v0.9.11 — Bio Lab dual-saves SleepSnapshot during snapshot() so a
    // LAB-tab-direct entry refreshes today's sleep data without waiting
    // for Readiness compute. Past dates stay immutable per v0.9.8 rule.
    private val sleepSnapshots = SleepSnapshotRepository(appContext)

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
        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }
            .rethrowCancellation()
            .getOrNull()
            ?: return@withContext BioLabSnapshot(
                computedAtMs = System.currentTimeMillis(),
                healthConnectAvailable = false,
                chronologicalAge = profile.ageYears,
                bodyWeightKg = profile.riderWeightKg,
            )
        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .rethrowCancellation()
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
        // v0.7.7 — pull strap NDJSON for the FULL 30d window. Calculator picks
        // strap vs HC per-night based on coverage. STRAP wins where 24/7
        // service was capturing; BAND fills in nights where it wasn't.
        val strapHrSamples30d = continuousBiometric.hrSamplesForWindow(monthAgo, now)
        val sleepingRhr = sleepingRhrCalc.compute(
            hcSamples = hrTimed30d,
            sleepWindows = sleepWindows30d,
            strapSamples = strapHrSamples30d,
        )

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
        // v0.8.0 — cache the computed athletic RHR back into RiderProfile so
        // the audio coach + ride start can build Karvonen zones without
        // re-running this entire BioLab pipeline. Best-effort; if save fails
        // the next BioLab refresh will retry.
        if (effectiveRestingHr != null && effectiveRestingHr in 30..120 &&
            effectiveRestingHr != profile.restingHrBpm
        ) {
            runCatching { profileStore.saveRestingHrBpm(effectiveRestingHr) }
                .onFailure { Log.w(TAG, "[v0.8.0] RHR cache write failed", it) }
        }

        // v0.9.1 — persist today's daily RHR snapshot. Idempotent: if today
        // already has a file on disk, the write is skipped (preserves the
        // original methodology version for historical analysis). Stores the
        // ROLLING MEDIAN (what BioLab displays) plus the most-recent-night's
        // actual reading + source — enough to drive future RHR trend charts
        // beyond HC's 30-day retention.
        if (sleepingRhr != null) {
            val today = java.time.LocalDate.now()
            rhrSnapshots.save(
                RhrSnapshot(
                    dateIsoLocal = today.toString(),
                    medianBpm = sleepingRhr.medianBpm,
                    mostRecentNightBpm = sleepingRhr.mostRecentNightBpm,
                    mostRecentNightEndMs = sleepingRhr.mostRecentNightEndTime.toEpochMilli(),
                    mostRecentNightSource = sleepingRhr.mostRecentNightSource.name,
                    nightsContributing = sleepingRhr.nightsCount,
                    methodologyVersion = RhrSnapshotRepository.METHODOLOGY_VERSION,
                    computedAtMs = System.currentTimeMillis(),
                ),
                date = today,
            )
        }
        val restingHrSourceLabel = sleepingRhr?.let {
            val nights = it.nightsCount
            val plural = if (nights == 1) "night" else "nights"
            // v0.7.7 — surface source breakdown inline. "12 strap + 3 band" tells
            // the rider exactly which sensor produced their RHR median.
            val breakdownStr = if (it.sourceBreakdown.size == 1) {
                val onlySource = it.sourceBreakdown.keys.first()
                when (onlySource) {
                    com.uruj.domain.SensorSource.STRAP -> " · all from chest strap ✓"
                    com.uruj.domain.SensorSource.BAND -> " · all from band"
                    com.uruj.domain.SensorSource.MIXED -> " · mixed sources"
                    com.uruj.domain.SensorSource.UNKNOWN_LEGACY -> ""
                }
            } else {
                " · " + it.sourceBreakdown.entries
                    .sortedByDescending { e -> e.value }
                    .joinToString(" + ") { e -> "${e.value} ${e.key.displayShort()}" }
            }
            "athletic RHR — median of $nights sleep $plural$breakdownStr"
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

        // v0.9.1 — persist today's VO2 snapshot if we have a consensus value.
        // Idempotent — today's file is preserved if it already exists. Captures
        // BOTH URUJ + Samsung values so future trend charts can show side-by-side
        // evolution (same transparency moat as the BioLab card).
        if (displayVo2 != null) {
            val today = java.time.LocalDate.now()
            vo2Snapshots.save(
                Vo2Snapshot(
                    dateIsoLocal = today.toString(),
                    urujConsensusMlKgMin = displayVo2,
                    urujHrBasedMlKgMin = vo2.hrBased,
                    urujPowerBasedMlKgMin = vo2.powerBased,
                    samsungMlKgMin = samsungVo2Max,
                    classification = vo2.classification,
                    methodologyVersion = Vo2SnapshotRepository.METHODOLOGY_VERSION,
                    computedAtMs = System.currentTimeMillis(),
                ),
                date = today,
            )
        }

        val karvonenZones = if (effectiveRestingHr != null && effectiveMaxHr > effectiveRestingHr) {
            karvonenCalc.compute(effectiveMaxHr, effectiveRestingHr)
        } else null

        // === HR Recovery (HRR1) — v0.9.1 disk-first ===
        // Cole NEJM 1999. Stronger CV mortality predictor than VO2 max alone.
        // Samsung doesn't expose this number.
        //
        // v0.9.1 architecture: disk holds the canonical history (all-time,
        // never lost). HC + strap NDJSON only get a fresh HrRecoveryCalculator
        // pass for sessions that DON'T already have a disk snapshot. Net result
        // for the typical open: zero new computes, just read disk + display.
        // When a new ride happens, the calculator runs once for that session,
        // writes the snapshot, then disk has it forever.
        //
        // Why disk-first matters (vs v0.9.0's "always recompute 30d + merge"):
        //   - Less calculator work per open (most opens have zero new sessions)
        //   - Aligned with the architectural rule
        //     [[reference_snapshot_persistence_architecture]]:
        //     "Trend charts read disk only. HC is never queried for history."
        //   - Frozen-at-snapshot-time methodology: old readings keep their
        //     original version tag; new computes get current. Methodology
        //     improvements can be traced + don't retroactively rewrite history.
        val urujRides = rideHistory.listAll()
        val diskHrrSnapshots = hrrSnapshots.listAll()
        val knownHrrSessionIds = diskHrrSnapshots.map { it.sessionId }.toSet()

        // Find session ends in last 30d that don't yet have a disk snapshot.
        // Mapping uses ±60s tolerance against URUJ ride endedAtMs (HC's
        // exercise-session end can drift slightly from URUJ's logged end).
        val newSessionEnds = combinedSessionEnds.filter { end ->
            val candidateId = mapSessionEndMsToSnapshotId(end.toEpochMilli(), urujRides)
            candidateId !in knownHrrSessionIds
        }

        // Run the calculator ONLY when there are new sessions to compute for.
        // hrTimed30d + strapHrSamples30d are already in memory (read earlier
        // for max HR auto-detect + sleeping RHR + 30d peak), so passing them
        // through costs nothing.
        val newHrrResult: HrRecoveryCalculator.Result? = if (newSessionEnds.isNotEmpty()) {
            val result = hrrCalc.compute(
                exerciseSessionEndTimes = newSessionEnds,
                hcHrSamples = hrTimed30d,
                strapHrSamples = strapHrSamples30d,
            )
            // v0.9.6 — tighter log line. Pre-fix said "21 new session(s)" which
            // counted ALL exercise-session ends (URUJ + Samsung), most of which
            // didn't qualify for HRR1 (low effort, no HR coverage). Now reports
            // evaluated → qualified → on-disk to make the actual outcome
            // visible. "21 evaluated · 2 qualified · 11 already on disk" tells
            // the rider the data is right; "21 new session(s) — computing"
            // implied 21 new readings were about to land, which was misleading.
            Log.d(
                TAG,
                "[v0.9.6] HRR1 disk-first: ${newSessionEnds.size} evaluated · " +
                    "${result?.samples?.size ?: 0} qualified · " +
                    "${diskHrrSnapshots.size} already on disk",
            )
            result
        } else {
            Log.d(
                TAG,
                "[v0.9.6] HRR1 disk-first: all ${diskHrrSnapshots.size} sessions on disk — no new compute",
            )
            null
        }

        // Persist new readings to disk (idempotent — already-saved are skipped).
        newHrrResult?.samples?.forEach { sample ->
            val sessionId = mapHrrSampleToSnapshotId(sample, urujRides)
            hrrSnapshots.save(
                HrrSnapshot(
                    sessionId = sessionId,
                    sessionEndMs = sample.sessionEnd.toEpochMilli(),
                    peakBpm = sample.effortPeakBpm,
                    hrr1Bpm = sample.hrr1Bpm,
                    classification = sample.classification,
                    source = sample.source.name,
                    methodologyVersion = HrrSnapshotRepository.METHODOLOGY_VERSION,
                    computedAtMs = System.currentTimeMillis(),
                ),
            )
        }

        // Build the display list from disk + freshly-computed. Disk is the
        // source of truth for history; new computes are appended.
        val allHrrSamples: List<HrrSample> = (
            diskHrrSnapshots.map { snap ->
                HrrSample(
                    endTimeMs = snap.sessionEndMs,
                    hrr1Bpm = snap.hrr1Bpm,
                    peakBpm = snap.peakBpm,
                    source = snap.sourceEnum,
                )
            } + (newHrrResult?.samples?.map { s ->
                HrrSample(
                    endTimeMs = s.sessionEnd.toEpochMilli(),
                    hrr1Bpm = s.hrr1Bpm,
                    peakBpm = s.effortPeakBpm,
                    source = s.source,
                )
            } ?: emptyList())
        ).sortedByDescending { it.endTimeMs }

        // Median + classification + breakdown computed across the FULL set
        // (disk + new). Was previously taken from hrr.medianHrr1 — that only
        // covered the 30d window. Now reflects all-time.
        val medianHrr1Bpm: Int? = if (allHrrSamples.isEmpty()) null else {
            val sorted = allHrrSamples.map { it.hrr1Bpm }.sorted()
            sorted[sorted.size / 2]
        }
        val medianHrrClassification: String? = medianHrr1Bpm?.let { classifyHrr1Bpm(it) }
        val hrrSourceBreakdown: Map<com.uruj.domain.SensorSource, Int> =
            allHrrSamples.groupingBy { it.source }.eachCount()

        val hrr1AthleteContext = computeHrr1AthleteContext(medianHrr1Bpm, vo2.classification)

        // v0.7.0 — Autonomic HRV from 24/7 BLE continuous capture. Compute over
        // last sleep window for the cleanest signal (parasympathetic dominance);
        // fall back to overnight 8h proxy if no sleep data.
        // v0.9.8 — use shared [effectiveHrvWindow] helper so Bio Lab + Readiness
        // agree exactly on HRV window resolution. Pre-v0.9.8: Bio Lab used 24h
        // fallback, Readiness used 8h fallback → divergent values on fallback
        // nights.
        val sleepWindow = lastSleepReader.read(client, granted)
        // v0.9.11 — also persist today's sleep snapshot when Bio Lab opens
        // independently of Checklist (e.g. LAB-tab-direct entry from app
        // restore). Sleep snapshot was previously only written by Readiness
        // compute, leaving a gap if Bio Lab ran first. Today-mutable rule
        // from v0.9.8 lets us safely overwrite; past dates stay immutable.
        if (sleepWindow != null) {
            runCatching {
                val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
                sleepSnapshots.save(
                    SleepSnapshot(
                        dateIsoLocal = today.toString(),
                        hoursTotal = sleepWindow.hours,
                        sessionStartMs = sleepWindow.startedAt.toEpochMilli(),
                        sessionEndMs = sleepWindow.endedAt.toEpochMilli(),
                        source = "samsung-hc",
                        methodologyVersion = SleepSnapshotRepository.METHODOLOGY_VERSION,
                        computedAtMs = System.currentTimeMillis(),
                    ),
                    date = today,
                )
            }.onFailure { Log.w(TAG, "[v0.9.11] sleep snapshot dual-save failed", it) }
        }
        val (hrvWindowStart, hrvWindowEnd) = effectiveHrvWindow(sleepWindow, now)
        val autonomicHrv = continuousBiometric.computeHrvForWindow(hrvWindowStart, hrvWindowEnd)
        // v0.9.25 — frequency-domain + non-linear HRV on the SAME RR window.
        // Reuses cached samples from the same NDJSON walk (no extra disk I/O).
        // Null when < 240 beats — frequency-domain math needs more data than
        // time-domain (~4 min at 60 bpm minimum). UI hides bands while
        // baseline builds.
        val autonomicFreqDomain = continuousBiometric.computeFrequencyDomainForWindow(hrvWindowStart, hrvWindowEnd)
        val autonomicSampleCount = autonomicHrv?.sampleCount ?: 0
        val autonomicWindowCount = autonomicHrv?.windowCount ?: 0
        val autonomicWindowLabel = if (sleepWindow != null) "last sleep" else "last 8h"
        // Count days of overnight HRV captured — drives "baseline building" UX.
        // v0.7.4: use Samsung sleep windows (same as Bio Lab Autonomic card)
        // instead of the old 22:00-09:00 heuristic so the day count agrees
        // with what the trend chart shows.
        val recentSleeps7d = lastSleepReader.listLastNDays(client, granted, 7)
        val autonomicDaysOfData = continuousBiometric
            .dailyOvernightHrvHistoryFromSessions(recentSleeps7d).size

        // v0.7.2 — Cortisol Awakening Response. Only resolved when the
        // most-recent sleep ended ≥45 min ago and 24/7 NDJSON has enough
        // samples for the pre/post-wake windows. Null otherwise (card hides).
        val carResult = carRepo.computeForLastWake()
        val carInterpretation = carResult?.let { carDetector.interpret(it) }

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
            // v0.9.1: all four fields now come from the disk-first all-time
            // set, not just HC's 30d window. Median + classification +
            // breakdown reflect the full lifetime history.
            hrr1Median = medianHrr1Bpm,
            hrr1Classification = medianHrrClassification,
            hrr1SampleCount = allHrrSamples.size,
            hrr1AthleteContext = hrr1AthleteContext,
            hrr1RecentSamples = allHrrSamples,
            hrr1SourceBreakdown = hrrSourceBreakdown,

            // v0.7.0 — Autonomic HRV from 24/7 BLE continuous capture
            autonomicRmssdMs = autonomicHrv?.rmssdMs,
            autonomicSdnnMs = autonomicHrv?.sdnnMs,
            autonomicPnn50Pct = autonomicHrv?.pnn50Percent,
            autonomicMeanHrBpm = autonomicHrv?.meanHrBpm,
            autonomicSampleCount = autonomicSampleCount,
            // v0.9.25 — frequency-domain + non-linear HRV (LF/HF/VLF + Poincaré
            // SD1/SD2 + DFA α1 + sample entropy). Null until ≥240 beats.
            autonomicFrequencyDomain = autonomicFreqDomain,
            autonomicWindowLabel = autonomicWindowLabel,
            autonomicWindowCount = autonomicWindowCount,
            autonomicDaysOfData = autonomicDaysOfData,

            // v0.7.2 — CAR
            carResult = carResult,
            carInterpretation = carInterpretation,
        )
    }

    /**
     * v0.9.0 — map a freshly-computed HRR1 sample to its persistence sessionId.
     *
     * URUJ rides own their sessionId from `StoredRideSummary.sessionId`. We
     * match by endedAtMs ± 60s tolerance (HC's ExerciseSessionRecord end time
     * can drift slightly from URUJ's recorded ride end).
     *
     * Samsung-tracked exercises that don't overlap a URUJ ride get the
     * synthetic `samsung-<EPOCH-MS>` ID. Stable across recomputes (epoch ms
     * doesn't change), so subsequent saves correctly skip as duplicates.
     */
    private fun mapHrrSampleToSnapshotId(
        sample: HrRecoveryCalculator.Sample,
        urujRides: List<StoredRideSummary>,
    ): String = mapSessionEndMsToSnapshotId(sample.sessionEnd.toEpochMilli(), urujRides)

    /**
     * v0.9.1 — same mapping as `mapHrrSampleToSnapshotId` but takes a raw
     * epoch-ms timestamp instead of a HrRecoveryCalculator.Sample. Used by
     * the disk-first path to identify candidate session ends BEFORE running
     * the calculator (so we can skip computing for already-snapshotted
     * sessions). Pulled into a shared helper to keep the mapping rule
     * in one place.
     */
    private fun mapSessionEndMsToSnapshotId(
        sessionEndMs: Long,
        urujRides: List<StoredRideSummary>,
    ): String {
        val matchingRide = urujRides.firstOrNull {
            kotlin.math.abs(it.endedAtMs - sessionEndMs) <= 60_000L
        }
        return matchingRide?.sessionId ?: "samsung-$sessionEndMs"
    }

    /**
     * v0.9.1 — Cole NEJM 1999 HRR1 classification, inlined for the disk-first
     * pipeline so we can classify the all-time median without re-running
     * HrRecoveryCalculator. Threshold strings match the calculator's
     * `Sample.classification` exactly so downstream code (BioLab card
     * subtitle, athlete-tier context lookup) doesn't need to learn new
     * vocabulary.
     */
    private fun classifyHrr1Bpm(bpm: Int): String = when {
        bpm >= 18 -> "Excellent"
        bpm >= 12 -> "Average"
        else -> "Elevated CV risk"
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
        }.rethrowCancellation().getOrDefault(emptyList())
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
        }.rethrowCancellation()
            .onFailure { Log.w(TAG, "sleep windows read failed", it) }
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
        }.rethrowCancellation()
            .onFailure { Log.w(TAG, "exercise ends read failed", it) }
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
        }.rethrowCancellation()
            .onFailure { Log.w(TAG, "VO2 max read failed", it) }
            .getOrNull()
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
        }.rethrowCancellation()
            .onFailure { Log.w(TAG, "weight read failed", it) }
            .getOrNull()
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
    /** v0.7.7 — per-source count for the HRR1 card badge.
     *  e.g. {STRAP=8, BAND=3} → "8 strap · 3 band". */
    val hrr1SourceBreakdown: Map<com.uruj.domain.SensorSource, Int> = emptyMap(),

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

    /**
     * v0.9.25 — frequency-domain + non-linear HRV measures (VLF/LF/HF, LF/HF
     * ratio, Poincaré SD1/SD2, DFA α1, sample entropy). Computed over the
     * same overnight RR window as `autonomicRmssdMs`. Null when fewer than
     * 240 beats in the window (~4 min at 60 bpm) — frequency-domain math
     * needs more data than time-domain. UI shows "baseline building" until
     * this fills in. See [com.uruj.power.FrequencyDomainCalculator].
     */
    val autonomicFrequencyDomain: com.uruj.power.FrequencyDomainCalculator.FrequencyDomainHrv? = null,

    /** v0.7.2 — Cortisol Awakening Response for the most recent wake event.
     *  Null when last sleep ended <45 min ago, or 24/7 NDJSON didn't have
     *  enough samples in the pre/post-wake windows. Card hides when null. */
    val carResult: CarResult? = null,
    /** Tier classification + plain-English summary, paired with carResult. */
    val carInterpretation: CarInterpretation? = null,
) {
    /**
     * v0.8.5 — fraction of the 7 key cycling-training signals that
     * produced non-null data this snapshot. Used by BioLabViewModel's
     * sticky-cache so a transient HC blip (rate-limit / Samsung sync
     * hiccup) doesn't blank good cards by overwriting them with a
     * less-complete snapshot. Same pattern as ReadinessSnapshot's
     * sticky cache introduced in v0.8.4.
     *
     * Signals counted: restingHrBpm, vo2MaxConsensus, karvonenZones,
     * hrr1Median, autonomicRmssdMs, carResult, highestHr30d.
     */
    val dataConfidence: Float
        get() {
            var present = 0
            if (restingHrBpm != null) present++
            if (vo2MaxConsensus != null) present++
            if (karvonenZones != null) present++
            if (hrr1Median != null) present++
            if (autonomicRmssdMs != null) present++
            if (carResult != null) present++
            if (highestHr30d != null) present++
            return present / 7f
        }
}

/** A single qualifying HRR1 reading from one exercise session. */
data class HrrSample(
    val endTimeMs: Long,
    val hrr1Bpm: Int,
    val peakBpm: Int,
    /** v0.7.7 — which sensor produced this reading. */
    val source: com.uruj.domain.SensorSource = com.uruj.domain.SensorSource.UNKNOWN_LEGACY,
)
