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
    private val hrvSnapshots = HrvSnapshotRepository(appContext)
    // v0.9.39 — in-app subjective + behavioral tracker layer (#111)
    private val trackerRepo = TrackerRepository(appContext)
    // v0.9.31 — postprandial HRV response test (Tier B test #109).
    private val mealMarks = MealMarkRepository(appContext)
    private val postprandialSnapshots = PostprandialSnapshotRepository(appContext)
    private val postprandialCalc = com.uruj.power.PostprandialCalculator()

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
        // v0.9.46 — TWO sleep reads (see ReadinessRepository for the rule):
        //   sleepWindow      → single contiguous block, used as HRV window
        //   sleepAggregated  → sum of ALL blocks in last 18h, used for the
        //                      user-facing SLEEP HOURS + SleepSnapshot total.
        // Pre-v0.9.46 fragmented-sleep nights under-reported total hours
        // because [read] returns only the most recent block.
        val sleepWindow = lastSleepReader.read(client, granted)
        val sleepAggregated = lastSleepReader.readAggregated(client, granted)
        // v0.9.48 — read sleep stage segments for HRV stage-aware filtering.
        // Empty list = stages unavailable → HRV math gracefully falls back to
        // pre-v0.9.48 whole-window method (snapshot tagged accordingly).
        val stageSegments: List<SleepStageSegment> = if (sleepAggregated != null) {
            lastSleepReader.readStagesForResult(client, granted, sleepAggregated)
        } else emptyList()
        val awakeMinutesExcluded = stageSegments
            .filter { !it.isAsleep }
            .sumOf { (it.endMs - it.startMs) / 60_000L }
            .toInt()
        val asleepHours = sleepAggregated?.let { it.hours - (awakeMinutesExcluded / 60f) }
        // v0.9.11 — also persist today's sleep snapshot when Bio Lab opens
        // independently of Checklist (e.g. LAB-tab-direct entry from app
        // restore). Sleep snapshot was previously only written by Readiness
        // compute, leaving a gap if Bio Lab ran first. Today-mutable rule
        // from v0.9.8 lets us safely overwrite; past dates stay immutable.
        // v0.9.48 — now also persists stage segments + asleepHours so future
        // re-aggregation survives HC's 30d retention.
        if (sleepAggregated != null) {
            runCatching {
                val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
                sleepSnapshots.save(
                    SleepSnapshot(
                        dateIsoLocal = today.toString(),
                        hoursTotal = sleepAggregated.hours,
                        sessionStartMs = sleepAggregated.startedAt.toEpochMilli(),
                        sessionEndMs = sleepAggregated.endedAt.toEpochMilli(),
                        source = "samsung-hc",
                        methodologyVersion = SleepSnapshotRepository.METHODOLOGY_VERSION,
                        computedAtMs = System.currentTimeMillis(),
                        stages = stageSegments,
                        asleepHours = asleepHours,
                    ),
                    date = today,
                )
            }.onFailure { Log.w(TAG, "[v0.9.11] sleep snapshot dual-save failed", it) }
        }
        // v0.9.48.2 — when sleep stages are available, span the FULL aggregated
        // window (earliest start → latest end) so all sleep blocks contribute
        // to HRV math, not just the most-recent contiguous one. Stage filter
        // does the heavy lifting: AWAKE periods excluded, between-block gaps
        // excluded (uncovered timestamps → return false in shouldInclude).
        //
        // Pre-v0.9.48.2: HRV used [sleepWindow] (single LAST block). On
        // fragmented nights (multiple rollovers) the main 6-8h sleep got
        // ignored and only the morning 4h rollover was analyzed. Caught
        // 2026-05-25 evening — user's 10.6h sleep produced HRV from only
        // 4h35m of data.
        //
        // When stages are EMPTY (legacy fallback), keep the v0.9.46 last-
        // block behavior — the single clean window approach still applies.
        val (hrvWindowStart, hrvWindowEnd) = if (stageSegments.isNotEmpty() && sleepAggregated != null) {
            sleepAggregated.startedAt to sleepAggregated.endedAt
        } else {
            effectiveHrvWindow(sleepWindow, now)
        }
        // v0.9.48 — stage-aware HRV compute. Empty stages = graceful fallback
        // to pre-v0.9.48 whole-window method.
        val autonomicHrv = continuousBiometric.computeHrvForWindow(hrvWindowStart, hrvWindowEnd, stageSegments)
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

        // v0.9.27 — persist today's HRV snapshot to disk for trend chart
        // history. Today-mutable, past-immutable. Per [[reference_snapshot_
        // persistence_architecture]] every trend metric persists at compute
        // time; trend charts read disk only. Bundles time-domain (v0.7.0
        // RMSSD/SDNN/pNN50) + frequency-domain (v0.9.25 LF/HF/VLF) + non-
        // linear (v0.9.25 Poincaré/DFA/sample entropy) in one record.
        if (autonomicHrv != null || autonomicFreqDomain != null) {
            runCatching {
                val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
                val hrvSourceLabel = when {
                    autonomicHrv != null -> "strap"  // v0.7.0 strap NDJSON path
                    else -> "unknown"
                }
                hrvSnapshots.save(
                    HrvSnapshot(
                        dateIsoLocal = today.toString(),
                        rmssdMs = autonomicHrv?.rmssdMs,
                        sdnnMs = autonomicHrv?.sdnnMs,
                        pnn50Percent = autonomicHrv?.pnn50Percent,
                        pnn20Percent = autonomicHrv?.pnn20Percent,
                        meanHrBpm = autonomicHrv?.meanHrBpm,
                        sampleCount = autonomicHrv?.sampleCount ?: 0,
                        windowCount = autonomicHrv?.windowCount ?: 0,
                        source = hrvSourceLabel,
                        vlfMs2 = autonomicFreqDomain?.vlfMs2,
                        lfMs2 = autonomicFreqDomain?.lfMs2,
                        hfMs2 = autonomicFreqDomain?.hfMs2,
                        totalPowerMs2 = autonomicFreqDomain?.totalPowerMs2,
                        lfHfRatio = autonomicFreqDomain?.lfHfRatio,
                        sd1Ms = autonomicFreqDomain?.sd1Ms,
                        sd2Ms = autonomicFreqDomain?.sd2Ms,
                        dfaAlpha1 = autonomicFreqDomain?.dfaAlpha1,
                        sampleEntropy = autonomicFreqDomain?.sampleEntropy,
                        // v0.9.48 — per-stage breakdown when stage filter ran
                        deepRmssdMs = autonomicHrv?.perStageRmssdMs?.get("deep"),
                        remRmssdMs = autonomicHrv?.perStageRmssdMs?.get("rem"),
                        lightRmssdMs = autonomicHrv?.perStageRmssdMs?.get("light"),
                        stageFiltered = stageSegments.isNotEmpty(),
                        awakeMinutesExcluded = if (stageSegments.isNotEmpty()) awakeMinutesExcluded else null,
                        computedAtMs = System.currentTimeMillis(),
                        // Tag with stage-aware version when stages applied,
                        // fallback tag otherwise — preserves the audit trail
                        // for nights that couldn't get the lab-grade treatment.
                        methodologyVersion = if (stageSegments.isNotEmpty()) {
                            HrvSnapshotRepository.METHODOLOGY_VERSION
                        } else {
                            HrvSnapshotRepository.METHODOLOGY_VERSION_FALLBACK
                        },
                    ),
                    date = today,
                )
            }.onFailure { Log.w(TAG, "[v0.9.27] HRV snapshot save failed", it) }
        }

        // v0.9.31 — Postprandial HRV response (Tier B test #109).
        // For each recent meal mark (last 7d) that's ≥75 min old AND not
        // already snapshotted, compute pre/post HRV via the same windowed
        // pipeline used for overnight HRV. Save to disk for trend chart
        // (v0.9.32 will add the screen). Surfaces the latest as
        // `latestPostprandial` field on BioLabSnapshot.
        val latestPostprandial: com.uruj.domain.PostprandialSnapshot? = runCatching {
            val recentMarks = mealMarks.listRecent()
            // v0.9.38 — pre-fetch recent ride history once for ride-overlap detection.
            // Used to flag postprandial snapshots whose post-window was captured
            // during exercise (interpretation = exercise effect, not meal response).
            val recentRides = rideHistory.listAll().filter {
                now.toEpochMilli() - it.startedAtMs < 7L * 24L * 60L * 60L * 1000L
            }
            // Process any ready-but-unprocessed marks (idempotent).
            // v0.9.33 — for each, also detect (a) overlap with a prior meal's
            // post-window, (b) whether the mark fell within Samsung's last
            // sleep window. Both surface as warning chips on the card.
            for (mark in recentMarks.reversed()) {
                if (!postprandialCalc.isReadyForCompute(mark)) continue
                if (postprandialSnapshots.exists(mark.id)) continue
                val preStart = Instant.ofEpochMilli(
                    mark.timestampMs + com.uruj.data.PostprandialSnapshotRepository
                        .PRE_WINDOW_START_OFFSET_MS,
                )
                val preEnd = Instant.ofEpochMilli(
                    mark.timestampMs + com.uruj.data.PostprandialSnapshotRepository
                        .PRE_WINDOW_END_OFFSET_MS,
                )
                val postStart = Instant.ofEpochMilli(
                    mark.timestampMs + com.uruj.data.PostprandialSnapshotRepository
                        .POST_WINDOW_START_OFFSET_MS,
                )
                val postEnd = Instant.ofEpochMilli(
                    mark.timestampMs + com.uruj.data.PostprandialSnapshotRepository
                        .POST_WINDOW_END_OFFSET_MS,
                )
                val preHrv = continuousBiometric.computeHrvForWindow(preStart, preEnd)
                val postHrv = continuousBiometric.computeHrvForWindow(postStart, postEnd)
                val preFd = continuousBiometric.computeFrequencyDomainForWindow(preStart, preEnd)
                val postFd = continuousBiometric.computeFrequencyDomainForWindow(postStart, postEnd)

                // v0.9.33 (a) — overlap detection. Walk all OTHER marks
                // within the last 90 min before this one and check if
                // their post-window overlaps with this one's pre-window.
                val priorMark = recentMarks.firstOrNull { other ->
                    other.id != mark.id &&
                        other.timestampMs < mark.timestampMs &&
                        mark.timestampMs - other.timestampMs < 90L * 60_000L
                }
                val overlapsPrior = priorMark != null && run {
                    val priorPostEnd = priorMark.timestampMs +
                        com.uruj.data.PostprandialSnapshotRepository.POST_WINDOW_END_OFFSET_MS
                    priorPostEnd > preStart.toEpochMilli()
                }

                // v0.9.33 (b) — sleep-window flag. If the meal mark falls
                // within Samsung's last sleep window (per LastSleepReader),
                // pre-window covers sleep state. Flag so interpretation
                // adjusts.
                val markInsideSleep = sleepWindow != null && run {
                    val sleepStartMs = sleepWindow.startedAt.toEpochMilli()
                    val sleepEndMs = sleepWindow.endedAt.toEpochMilli()
                    mark.timestampMs in sleepStartMs..sleepEndMs
                }

                // v0.9.38 (#190) — ride-overlap detection. If any ride's
                // session interval overlaps the meal's post-window
                // [mark+45min .. mark+75min], flag the snapshot as
                // exercise-confounded. Also compute minutes-to-next-ride
                // for the warning chip text.
                val postStartMs = postStart.toEpochMilli()
                val postEndMs = postEnd.toEpochMilli()
                val rideOverlap = recentRides.any { ride ->
                    // standard overlap: ride.start < postEnd AND ride.end > postStart
                    ride.startedAtMs < postEndMs && ride.endedAtMs > postStartMs
                }
                val nextRideStart = recentRides
                    .filter { it.startedAtMs > mark.timestampMs &&
                              it.startedAtMs - mark.timestampMs < 4L * 60L * 60L * 1000L }
                    .minByOrNull { it.startedAtMs }
                    ?.startedAtMs
                val minutesToNextRide = nextRideStart?.let {
                    (it - mark.timestampMs) / 60_000L
                }

                val snap = postprandialCalc.compute(
                    mealMark = mark,
                    preHrv = preHrv,
                    postHrv = postHrv,
                    preFreqDomain = preFd,
                    postFreqDomain = postFd,
                    overlapsPriorMeal = overlapsPrior,
                    overlapsPriorMealId = if (overlapsPrior) priorMark?.id else null,
                    isDuringSleep = markInsideSleep,
                    rideOverlapsPostprandial = rideOverlap,
                    minutesToNextRide = minutesToNextRide,
                )
                postprandialSnapshots.save(snap)
            }
            // Return the most recent computed (whether new or pre-existing)
            postprandialSnapshots.listAll().firstOrNull()
        }
            .rethrowCancellation()
            .onFailure { Log.w(TAG, "[v0.9.31] postprandial compute failed", it) }
            .getOrNull()

        // v0.7.2 — Cortisol Awakening Response. Only resolved when the
        // most-recent sleep ended ≥45 min ago and 24/7 NDJSON has enough
        // samples for the pre/post-wake windows. Null otherwise (card hides).
        val carResult = carRepo.computeForLastWake()
        val carInterpretation = carResult?.let { carDetector.interpret(it) }
        // v0.9.36 — minutes since last sleep ended. Drives CAR card UX
        // polish (#180): when sleep ended <45 min ago, card shows
        // "computing post-wake window" placeholder instead of silent
        // hide. Null when no sleep window available.
        val carMinutesSinceWake: Long? = sleepWindow?.let {
            (now.toEpochMilli() - it.endedAt.toEpochMilli()) / 60_000L
        }

        // v0.9.39 — load today's subjective + behavioral tracker entries (#111)
        // for Bio Lab cards + ReadinessContext signal pack.
        val moodToday = runCatching {
            trackerRepo.listForToday(com.uruj.domain.TrackerType.MOOD.key)
        }.rethrowCancellation().getOrNull().orEmpty()
        val energyToday = runCatching {
            trackerRepo.listForToday(com.uruj.domain.TrackerType.ENERGY.key)
        }.rethrowCancellation().getOrNull().orEmpty()
        val hydrationToday = runCatching {
            trackerRepo.listForToday(com.uruj.domain.TrackerType.HYDRATION_ML.key)
        }.rethrowCancellation().getOrNull().orEmpty()
        val caffeineToday = runCatching {
            trackerRepo.listForToday(com.uruj.domain.TrackerType.CAFFEINE_MG.key)
        }.rethrowCancellation().getOrNull().orEmpty()
        // v0.9.40 Phase 2 tracker types
        val supplementsToday = runCatching {
            trackerRepo.listForToday(com.uruj.domain.TrackerType.SUPPLEMENTS.key)
        }.rethrowCancellation().getOrNull().orEmpty()
        val bristolToday = runCatching {
            trackerRepo.listForToday(com.uruj.domain.TrackerType.BRISTOL.key)
        }.rethrowCancellation().getOrNull().orEmpty()
        val sleepQualityToday = runCatching {
            trackerRepo.listForToday(com.uruj.domain.TrackerType.SLEEP_QUALITY.key)
        }.rethrowCancellation().getOrNull().orEmpty()
        val sorenessToday = runCatching {
            trackerRepo.listForToday(com.uruj.domain.TrackerType.SORENESS.key)
        }.rethrowCancellation().getOrNull().orEmpty()

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

            // v0.9.31 — postprandial HRV response (latest snapshot if any)
            latestPostprandial = latestPostprandial,

            // v0.7.2 — CAR
            carResult = carResult,
            carInterpretation = carInterpretation,
            // v0.9.36 — minutes since wake for CAR placeholder UX (#180)
            carMinutesSinceWake = carMinutesSinceWake,
            // v0.9.39 — subjective + behavioral tracker today (#111)
            moodEntriesToday = moodToday,
            energyEntriesToday = energyToday,
            hydrationEntriesToday = hydrationToday,
            caffeineEntriesToday = caffeineToday,
            // v0.9.40 Phase 2 — supplements / bristol / sleep quality / soreness
            supplementsEntriesToday = supplementsToday,
            bristolEntriesToday = bristolToday,
            sleepQualityEntriesToday = sleepQualityToday,
            sorenessEntriesToday = sorenessToday,
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

    /** v0.9.31 — Latest postprandial HRV response (Tier B test #109).
     *  Null until the rider's first meal mark + 75-min window has been
     *  captured. Surfaces the most-recent computed snapshot for the
     *  Bio Lab card; trend chart (v0.9.32+) reads the full repo. */
    val latestPostprandial: com.uruj.domain.PostprandialSnapshot? = null,

    /** v0.7.2 — Cortisol Awakening Response for the most recent wake event.
     *  Null when last sleep ended <45 min ago, or 24/7 NDJSON didn't have
     *  enough samples in the pre/post-wake windows. Card hides when null. */
    val carResult: CarResult? = null,
    /** Tier classification + plain-English summary, paired with carResult. */
    val carInterpretation: CarInterpretation? = null,
    /** v0.9.36 — Minutes since last sleep ended. Used by CAR card to show
     *  a "computing post-wake window" placeholder when <45 min post-wake
     *  (carResult is null in that window). Null when no sleep window. */
    val carMinutesSinceWake: Long? = null,
    /** v0.9.39 — Today's mood tracker entries (#111). Newest first. */
    val moodEntriesToday: List<com.uruj.domain.TrackerEntry> = emptyList(),
    /** v0.9.39 — Today's energy tracker entries. */
    val energyEntriesToday: List<com.uruj.domain.TrackerEntry> = emptyList(),
    /** v0.9.39 — Today's hydration tracker entries (sum for daily total). */
    val hydrationEntriesToday: List<com.uruj.domain.TrackerEntry> = emptyList(),
    /** v0.9.39 — Today's caffeine tracker entries (sum for daily mg total). */
    val caffeineEntriesToday: List<com.uruj.domain.TrackerEntry> = emptyList(),
    /** v0.9.40 — Today's supplements entries (each has textValue = name, optional numericValue = dose mg). */
    val supplementsEntriesToday: List<com.uruj.domain.TrackerEntry> = emptyList(),
    /** v0.9.40 — Today's Bristol stool entry (typically once daily). */
    val bristolEntriesToday: List<com.uruj.domain.TrackerEntry> = emptyList(),
    /** v0.9.40 — Today's subjective sleep quality rating (1-10). */
    val sleepQualityEntriesToday: List<com.uruj.domain.TrackerEntry> = emptyList(),
    /** v0.9.40 — Today's soreness entries (1-10 + optional body location). */
    val sorenessEntriesToday: List<com.uruj.domain.TrackerEntry> = emptyList(),
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
