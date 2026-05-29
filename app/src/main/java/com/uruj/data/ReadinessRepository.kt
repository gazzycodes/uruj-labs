package com.uruj.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.uruj.domain.ReadinessGrade
import com.uruj.domain.ReadinessInputs
import com.uruj.domain.ReadinessResult
import com.uruj.domain.RecommendationSnapshot
import com.uruj.power.ReadinessCalculator
import com.uruj.power.ReadinessReasoner
import com.uruj.power.RuleBasedReasoner
import com.uruj.power.SleepingRhrCalculator
import com.uruj.util.rethrowCancellation
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
     * RestingHeartRateRecord existed; "sleep" or "sleep:<sensor>" = derived via
     * SleepingRhrCalculator (sensor suffix is strap/band/mixed/legacy per v0.7.7);
     * "proxy" = HR-sample percentile fallback (no sleep data); null = no RHR data
     * available at all. Prevents the misleading "0 RHR" diagnostics line when
     * the readiness score actually used a derived value.
     */
    val rhrSourceLabel: String? = null,
    /**
     * Same idea for HRV. "direct" = HeartRateVariabilityRmssdRecord existed
     * (rare on Fit Band 3 — needs RR intervals from chest strap); "ble_strap" =
     * computed from URUJ's overnight RR-interval NDJSON via Magene H613 (v0.7.0);
     * "proxy" = std-dev of HR samples (Garmin/Fitbit pre-strap pattern); null =
     * no HRV input available.
     */
    val hrvSourceLabel: String? = null,
    /**
     * v0.9.3 — count of overnight HRV nights in the last 7 days computed from
     * URUJ's BLE NDJSON (Magene H613 → 24/7 BiometricService). Separate from
     * [hrvRecords7d] which counts HC RmssdRecord writes (always 0 unless using
     * a sensor that writes that record type; Fit Band 3 and H613 both don't).
     * Surfaces "HRV(strap · 2n) ✓" in the Pipeline label so the rider isn't
     * misled by HC's "0 HRV records" when URUJ owns the HRV data path.
     */
    val urujHrvNights7d: Int = 0,
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
    private val profileStore = RiderProfileStore(appContext)
    private val sleepingRhrCalc = SleepingRhrCalculator()
    private val lastSleepReader = LastSleepReader()
    // v0.7.0 — BLE chest-strap RMSSD HRV from continuous monitoring NDJSON.
    private val continuousBiometric = ContinuousBiometricRepository(appContext)
    // v0.9.1 — daily TSB snapshots so the 6-month fitness curve survives
    // beyond HC's 30-day retention window. Same architecture as HRR1/RHR/VO2.
    private val tsbSnapshots = TsbSnapshotRepository(appContext)
    // v0.9.52.1 — disk-first HRV historical reads REVERTED. Reading stored
    // snapshots that were saved with pre-v0.9.48 methodology causes median
    // drift (this morning's logcat showed 7d median 13.7 → 11.3 ms after the
    // v0.9.52 change took effect — a data correctness regression). The
    // disk-first path is restored in v0.9.56 once v0.9.55's
    // HrvSnapshotRepository.ensureBackfilled() migrates legacy-methodology
    // snapshots to current. See [[reference_perf_architecture_findings_2026_05_27]].
    // v0.9.4 — unified signal pack assembler + reasoner + recommendation
    // history. See [[reference_readiness_context_architecture]] for the law.
    private val contextBuilder = ReadinessContextBuilder(appContext)
    // AI HOOK: swap this for CompositeReasoner(GroqAiReasoner(), RuleBasedReasoner())
    // when Task #105 / v0.5 ships. Same interface, plug-and-play.
    private val reasoner: ReadinessReasoner = RuleBasedReasoner()
    private val recSnapshots = RecommendationSnapshotRepository(appContext)
    // v0.9.8 — daily sleep-hours persistence so the trend survives HC's 30d
    // retention. Sleep is a primary Readiness input; pre-v0.9.8 it lived
    // only in HC. [[reference_snapshot_persistence_architecture]] rule.
    private val sleepSnapshots = SleepSnapshotRepository(appContext)
    // v0.9.55 — disk-first rhrBaseline for TSB pre-refresh path.
    // RhrSnapshotRepository already persists daily athletic RHR (medianBpm)
    // via BioLab.snapshot() since v0.9.1. The TSB hrTSS conversion needs
    // exactly that scalar — no reason to re-derive from raw 30d HR samples
    // on every cold launch. See readBaselineRhrOnly() for the disk-first
    // chain. Also closes the OOM hazard from v0.9.54.1 — the bypassed
    // hrSamplesForWindow(7d) call was the same allocation site.
    private val rhrSnapshots = RhrSnapshotRepository(appContext)
    // v0.9.58 — disk-first historical HRV reads (the v0.9.52 path,
    // re-enabled with the v0.9.57 ensureBackfilled() prerequisite that
    // guarantees methodology consistency before we read). Saves ~14s of
    // recompute per Readiness compute when ≥5 current-methodology
    // snapshots are on disk. Safe fallback to NDJSON recompute when the
    // backfill couldn't complete (fresh install, NDJSON gaps, HC offline).
    private val hrvSnapshots = HrvSnapshotRepository(appContext)

    // v0.9.8 — TSB refresh cooldown. Last refresh epoch-ms. 0L = never.
    @Volatile private var lastTsbRefreshMs: Long = 0L

    suspend fun compute(): ReadinessResult = withContext(Dispatchers.IO) {
        val inputs = gatherInputs()
        calculator.compute(inputs)
    }

    /**
     * v0.9.7 — lightweight TSB-only recompute. Reads HC exercise sessions +
     * URUJ rides, computes fresh CTL/ATL/TSB, overwrites today's
     * [TsbSnapshot] on disk. Used by [com.uruj.ui.biolab.BioLabViewModel]
     * to ensure the Training State card reads the same value the Readiness
     * card just showed.
     *
     * Cheaper than the full [computeWithDiagnostics] path:
     *   - Skips Sleep / HRV / HC RHR / HC HRV reads
     *   - Skips reasoner + recommendation snapshot save
     *   - Only HC reads needed: ExerciseSessionRecord + HeartRateRecord
     *     (for hrTSS), plus what `historyRepo.listAll()` returns from disk.
     *
     * v0.9.8 — blast-radius throttle. Worst-case rider with ~50 Samsung
     * non-cycling sessions in 42d = up to 51 HC reads per call. Without
     * gates, repeated Bio Lab opens could rapidly inflate HC pressure.
     *
     * Two gates:
     *   1. [HcReadGuard.isPostRideQuietWindow]  — skip during the 30s
     *      post-ride cascade (same rule the full Bio Lab snapshot already
     *      respects). The TSB snapshot from the morning is still on disk;
     *      consumers read the slightly-stale value and the next out-of-
     *      quiet-window open will refresh.
     *   2. In-memory cooldown — 5 minutes between refreshes. Bio Lab open
     *      → refresh fires → second open within 5 min → skip. Caps reads
     *      at 12/hr in worst case (active rider rapid-tabbing through
     *      surfaces). HC budget stays in v0.8.4 envelope.
     *
     * Force-true is intentionally NOT supported here — manual SYNC paths
     * trigger the full [computeWithDiagnostics] which writes TSB anyway.
     *
     * Returns the latest TSB scalar (or cached/null if skipped).
     */
    suspend fun refreshTsbSnapshotForToday(): Float? = withContext(Dispatchers.IO) {
        // Gate 1: post-ride quiet window
        if (HcReadGuard.isPostRideQuietWindow()) {
            Log.d("URUJ-TSB", "[v0.9.8] refresh skipped — post-ride quiet window")
            return@withContext null
        }
        // Gate 2: 5-minute cooldown, BUT always refresh when today's snapshot
        // is missing (e.g. first compute of a new calendar day). Without this
        // bypass the cooldown carried over from yesterday's last open would
        // block the new day's first save → Bio Lab would show yesterday's TSB.
        val today = LocalDate.now(ZoneId.systemDefault())
        val todaySnapshotExists = tsbSnapshots.load(today) != null
        val now = System.currentTimeMillis()
        val lastRefresh = lastTsbRefreshMs
        val cooldownActive = lastRefresh != 0L &&
            now - lastRefresh < TSB_REFRESH_COOLDOWN_MS
        if (cooldownActive && todaySnapshotExists) {
            val ageS = (now - lastRefresh) / 1000L
            Log.d("URUJ-TSB", "[v0.9.8] refresh skipped — last refresh ${ageS}s ago (<300s cooldown)")
            return@withContext null
        }
        if (cooldownActive && !todaySnapshotExists) {
            Log.d(
                "URUJ-TSB",
                "[v0.9.8] cooldown bypassed — new calendar day, no snapshot for $today yet",
            )
        }
        lastTsbRefreshMs = now
        HcReadGuard.recordRead("readiness.refresh-tsb")
        val sdkOk = HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE
        if (!sdkOk) {
            return@withContext computeTsb(null, emptySet(), null)
        }
        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }.rethrowCancellation().getOrNull()
        val granted = if (client != null) {
            runCatching { client.permissionController.getGrantedPermissions() }
                .getOrDefault(emptySet())
        } else emptySet()

        // Need Athletic RHR for multi-sport hrTSS — try direct RestingHR record
        // first, then sleep-derived. Lightweight versions of those reads.
        val rhrForLoad = readBaselineRhrOnly(client, granted)
        val detailed = computeTsbDetailed(client, granted, rhrForLoad)
        if (detailed != null) {
            val today = LocalDate.now(ZoneId.systemDefault())
            tsbSnapshots.save(
                TsbSnapshot(
                    dateIsoLocal = today.toString(),
                    tsb = detailed.tsb,
                    ctl = detailed.ctl,
                    atl = detailed.atl,
                    totalLoad42d = detailed.totalLoad42d,
                    methodologyVersion = TsbSnapshotRepository.METHODOLOGY_VERSION,
                    computedAtMs = System.currentTimeMillis(),
                ),
                date = today,
            )
            Log.d(
                "URUJ-TSB",
                "[v0.9.7] refreshed today's TSB: ${"%.2f".format(detailed.tsb)} " +
                    "(CTL ${"%.2f".format(detailed.ctl)} · ATL ${"%.2f".format(detailed.atl)} · " +
                    "totalLoad42d ${"%.0f".format(detailed.totalLoad42d)}) — snapshot overwritten",
            )
            return@withContext detailed.tsb
        }
        null
    }

    /**
     * v0.9.11 — one-time backfill of historical TSB snapshots. Runs
     * computeTsbDetailed once per past day with that day as the EWMA
     * anchor. Per-day TsbSnapshot written to disk.
     *
     * Limitations:
     * - URUJ rides are permanent (full history available on disk)
     * - Samsung non-cycling sessions retained in HC ~42d only — older
     *   backfill days will under-count multi-sport load. Accepted; the
     *   trend chart still shows the shape.
     * - Past-date immutability rejects overwrites — idempotent.
     * - Gated by HcReadGuard.isPostRideQuietWindow.
     * - One HC read for ExerciseSessionRecord across the window, reused
     *   across all per-day computes via the cached `client` reference.
     *
     * Returns count of new snapshots created.
     */
    suspend fun backfillTsbSnapshots(days: Int = 30): Int = withContext(Dispatchers.IO) {
        if (HcReadGuard.isPostRideQuietWindow()) {
            Log.d("URUJ-TSB", "[v0.9.11] backfill skipped — post-ride quiet window")
            return@withContext 0
        }
        HcReadGuard.recordRead("backfill.tsb")
        val sdkOk = HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE
        val client = if (sdkOk) {
            runCatching { HealthConnectClient.getOrCreate(appContext) }.rethrowCancellation().getOrNull()
        } else null
        val granted = if (client != null) {
            runCatching { client.permissionController.getGrantedPermissions() }
                .rethrowCancellation()
                .getOrDefault(emptySet())
        } else emptySet()
        val rhrForLoad = readBaselineRhrOnly(client, granted)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        var written = 0
        for (i in 1..days) {
            val date = today.minusDays(i.toLong())
            if (tsbSnapshots.load(date) != null) continue  // already backfilled
            val detailed = computeTsbDetailed(client, granted, rhrForLoad, targetDate = date)
                ?: continue
            val saved = tsbSnapshots.save(
                TsbSnapshot(
                    dateIsoLocal = date.toString(),
                    tsb = detailed.tsb,
                    ctl = detailed.ctl,
                    atl = detailed.atl,
                    totalLoad42d = detailed.totalLoad42d,
                    methodologyVersion = "v0.9.11-backfill-coggan-ewma",
                    computedAtMs = System.currentTimeMillis(),
                ),
                date = date,
            )
            if (saved) written++
        }
        Log.d("URUJ-TSB", "[v0.9.11] backfill: $written new TSB snapshots over last ${days}d")
        written
    }

    /**
     * v0.9.11 — RHR + VO2 backfill orchestrator. Delegates to the per-repo
     * helpers. Called from RhrTrendScreen / Vo2TrendScreen / SleepTrendScreen
     * `LaunchedEffect` on first open after install (lazy + idempotent).
     */
    suspend fun backfillRhrSnapshots(
        rhrRepo: RhrSnapshotRepository,
        sleepRepo: SleepSnapshotRepository,
        days: Int = 30,
    ): Int = withContext(Dispatchers.IO) {
        val sdkOk = HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE
        if (!sdkOk) return@withContext 0
        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }.rethrowCancellation().getOrNull()
            ?: return@withContext 0
        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .rethrowCancellation()
            .getOrDefault(emptySet())
        rhrRepo.backfillFromHc(client, granted, sleepRepo, sleepingRhrCalc, days)
    }

    /**
     * v0.9.11 — VO2 backfill. No HC reads. Reads RHR snapshots from disk
     * and applies Uth-Sørensen formula with current profile MaxHR. Run
     * AFTER RHR backfill has populated RHR snapshots.
     */
    suspend fun backfillVo2Snapshots(
        vo2Repo: Vo2SnapshotRepository,
        rhrRepo: RhrSnapshotRepository,
    ): Int = withContext(Dispatchers.IO) {
        val profile = runCatching { profileStore.current() }.rethrowCancellation().getOrNull() ?: return@withContext 0
        vo2Repo.backfillFromRhr(rhrRepo, maxHr = profile.maxHrBpm)
    }

    /**
     * Lightweight RHR lookup for TSB compute only.
     *
     * v0.9.55 — DISK-FIRST chain. RhrSnapshotRepository persists today's
     * athletic RHR via BioLab.snapshot() (v0.9.1). For TSB hrTSS conversion
     * we only need the rhrBaseline scalar — no reason to re-derive from raw
     * HC HR + sleep windows + 7d NDJSON walk on every cold launch.
     *
     * Chain (first non-null wins):
     *   1. Today's RhrSnapshot on disk (microseconds, zero allocation)
     *   2. Most recent past RhrSnapshot within 7d (zero allocation)
     *   3. HC RestingHeartRateRecord direct (one HC read, ~50ms)
     *   4. Sleep-derived recompute (~10-15s + 420K Pair allocations)
     *      — this is the OOM hazard site from v0.9.54.1.
     *
     * Math correctness invariant:
     *   - Step 1 returns the exact value BioLab.snapshot() just saved last
     *     time it ran. Same sleepingRhrCalc, same inputs, same number.
     *   - Step 2 returns yesterday's (or older) medianBpm. Athletic RHR
     *     drifts 1-2 bpm day-to-day; for hrTSS conversion this is well
     *     within HC sync noise. TSB EWMA smooths daily shifts further.
     *   - Steps 3-4 unchanged from pre-v0.9.55 (existing behavior).
     *
     * OOM safety: the 7d NDJSON walk at step 4 was the OOM site in
     * v0.9.54.1 (concurrent with BioLab.snapshot's 30d walk = ~3.6M
     * Pair objects). Sequential post-v0.9.54.2 is safe, but step 4 is
     * still expensive — disk-first skips it entirely once any RhrSnapshot
     * exists.
     */
    private suspend fun readBaselineRhrOnly(
        client: HealthConnectClient?,
        granted: Set<String>,
    ): Int? {
        // Step 1: today's RhrSnapshot. Saved by BioLab.snapshot() at the
        // end of its compute path; ~1KB JSON file; load is microseconds.
        val today = LocalDate.now(ZoneId.systemDefault())
        val todaySnap = runCatching { rhrSnapshots.load(today) }
            .rethrowCancellation()
            .getOrNull()
        if (todaySnap != null && todaySnap.medianBpm in 30..120) {
            Log.d(
                "URUJ-TSB",
                "[v0.9.55] rhrBaseline from today's RhrSnapshot: ${todaySnap.medianBpm} bpm",
            )
            return todaySnap.medianBpm
        }
        // Step 2: most recent past RhrSnapshot (last 7 days). Athletic RHR
        // changes slowly — a day-old reading is fine for hrTSS conversion.
        for (i in 1..7) {
            val date = today.minusDays(i.toLong())
            val past = runCatching { rhrSnapshots.load(date) }
                .rethrowCancellation()
                .getOrNull()
            if (past != null && past.medianBpm in 30..120) {
                Log.d(
                    "URUJ-TSB",
                    "[v0.9.55] rhrBaseline from past RhrSnapshot ($date): ${past.medianBpm} bpm (${i}d old)",
                )
                return past.medianBpm
            }
        }
        // Step 3-4: existing fallback chain (HC direct → sleep-derived).
        if (client == null) return null
        if (HealthPermission.getReadPermission(RestingHeartRateRecord::class) in granted) {
            val (_, baseline) = readRhrTodayAndBaseline(client, Instant.now())
            if (baseline != null) {
                Log.d("URUJ-TSB", "[v0.9.55] rhrBaseline from HC RestingHeartRateRecord: $baseline bpm")
                return baseline
            }
        }
        // Fallback: sleep-derived RHR (one HC HR + sleep window read + 7d NDJSON walk).
        // Heavy. Only fires when no RhrSnapshot exists AND HC RestingHR direct
        // returned null. Typical case: fresh install on first BioLab open.
        val hrPerm = HealthPermission.getReadPermission(HeartRateRecord::class) in granted
        val sleepPerm = HealthPermission.getReadPermission(SleepSessionRecord::class) in granted
        if (!hrPerm || !sleepPerm) return null
        val sleepingData = readSleepingHrInputs(client, Instant.now()) ?: return null
        val weekAgo = Instant.now().minus(Duration.ofDays(7))
        val strapSamples = continuousBiometric.hrSamplesForWindow(weekAgo, Instant.now())
        val sleepingResult = sleepingRhrCalc.compute(
            hcSamples = sleepingData.first,
            sleepWindows = sleepingData.second,
            strapSamples = strapSamples,
        )
        sleepingResult?.medianBpm?.let {
            Log.d("URUJ-TSB", "[v0.9.55] rhrBaseline from sleep-derived compute: $it bpm")
        }
        return sleepingResult?.medianBpm
    }

    /**
     * Returns the result PLUS raw record counts. UI uses this to show users exactly
     * what Health Connect has — no more guessing whether sync worked.
     */
    suspend fun computeWithDiagnostics(): ReadinessSnapshot = withContext(Dispatchers.IO) {
        val diagnostics = collectDiagnostics()
        val gathered = gatherInputsWithSource()
        val scored = calculator.score(gathered.inputs)

        // v0.9.4 — limited-data path stays simple. Otherwise build the
        // unified ReadinessContext + run the reasoner. Recommendation
        // gets persisted to disk so multi-day pattern tracking works.
        // See [[reference_readiness_context_architecture]].
        val result = if (scored.dataConfidence < 0.5f) {
            ReadinessResult(
                score = scored.score,
                grade = scored.grade,
                components = scored.components,
                recommendation = calculator.buildLimitedDataRecommendation(
                    scored.score, scored.components, scored.dataConfidence,
                ),
                dataConfidence = scored.dataConfidence,
            )
        } else {
            val ctx = contextBuilder.build(
                inputs = gathered.inputs,
                rhrSource = gathered.rhrSource,
                hrvSource = gathered.hrvSource,
                urujHrvNights7d = gathered.urujHrvNights7d,
                urujHrvNightlyRmssdMs = gathered.urujHrvNightlyRmssdMs,
            )
            val rec = reasoner.reason(ctx, scored.score, scored.components)

            // Persist daily recommendation snapshot — unlocks multi-day rest
            // tracking + future AI feedback loop. Idempotent overwrite per day.
            runCatching {
                recSnapshots.save(
                    RecommendationSnapshot(
                        dateIsoLocal = LocalDate.now(ZoneId.systemDefault()).toString(),
                        score = scored.score,
                        tier = rec.tier.name,
                        headline = rec.headline,
                        duration = rec.duration,
                        rationale = rec.rationale,
                        severeFlags = rec.severeFlags,
                        mildFlags = rec.mildFlags,
                        missingSignals = ctx.provenance.missingSignals,
                        computedAtMs = System.currentTimeMillis(),
                        methodologyVersion = RecommendationSnapshotRepository.METHODOLOGY_VERSION,
                    ),
                )
            }.onFailure {
                Log.w("URUJ-Readiness", "recommendation snapshot save failed", it)
            }

            // v0.9.49.2 — tier-aligned grade. Pre-v0.9.49.2 the badge was
            // derived purely from raw score (74 → "MODERATE") while the
            // headline came from the engine tier (ActiveRecovery → "graded
            // recovery"). When the absolute biomarker ceiling fired,
            // these contradicted each other on screen. Now the badge
            // follows the engine's final tier, so a chronic-baseline rider
            // sees a single consistent answer.
            val tierAlignedGrade = when (rec.tier) {
                com.uruj.domain.ReadinessTier.FullRest -> ReadinessGrade.Rest
                com.uruj.domain.ReadinessTier.ActiveRecovery -> ReadinessGrade.Recovery
                com.uruj.domain.ReadinessTier.EasyAerobic -> ReadinessGrade.Easy
                com.uruj.domain.ReadinessTier.ModerateEndurance -> ReadinessGrade.Moderate
                com.uruj.domain.ReadinessTier.HardGreenLight -> ReadinessGrade.GoHard
            }
            // v0.9.21 — surface TSB breakdown so Readiness card's TRAINING
            // LOAD ⓘ can render the same personalized ATL:CTL ratio + Build-
            // CTL prescription as the Bio Lab Training State ⓘ. Same numbers
            // already persisted via TsbSnapshotRepository — this just makes
            // them available to the Readiness-side dialog without a second
            // disk read.
            ReadinessResult(
                score = scored.score,
                grade = tierAlignedGrade,
                components = scored.components,
                recommendation = rec.headline,
                dataConfidence = scored.dataConfidence,
                recommendationDuration = rec.duration,
                recommendationRationale = rec.rationale,
                recommendationInsights = rec.insights,
                recommendationMissingSignals = rec.missingSignalsCallout,
                tsbCtl = ctx.today.tsb?.ctl,
                tsbAtl = ctx.today.tsb?.atl,
                tsbTotalLoad42d = ctx.today.tsb?.totalLoad42d,
                // v0.9.46.B — surface HRV statistical context for the card's
                // new sub-line (7d median + trend + week-over-week). Replaces
                // the daily-noise-as-signal pattern with proper variability
                // framing per HrvStatsCalculator methodology.
                hrvStats = ctx.today.hrv?.stats,
            )
        }

        ReadinessSnapshot(
            result = result,
            diagnostics = diagnostics.copy(
                rhrSourceLabel = gathered.rhrSource,
                hrvSourceLabel = gathered.hrvSource,
                urujHrvNights7d = gathered.urujHrvNights7d,
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
        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }.rethrowCancellation().getOrNull()
            ?: return ReadinessDiagnostics(installed, 0, 4, 0, 0, 0, 0, rideCount)
        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .rethrowCancellation()
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
        }.rethrowCancellation()
            .onFailure { Log.w("URUJ-Readiness", "count failed for ${recordType.simpleName}", it) }
            .getOrDefault(0)
    }

    private suspend fun gatherInputs(): ReadinessInputs = gatherInputsWithSource().inputs

    /**
     * v0.9.3 — named result for [gatherInputsWithSource]. Replaces the prior
     * Triple<ReadinessInputs, String?, String?> shape so adding new diagnostics
     * fields (URUJ HRV nights count, future source labels) doesn't require
     * touching every call site.
     */
    private data class GatheredInputs(
        val inputs: ReadinessInputs,
        /** "direct" / "sleep" / "sleep:strap" / "sleep:band" / "sleep:mixed" /
         *  "sleep:legacy" / "proxy" / null. */
        val rhrSource: String?,
        /** "direct" / "ble_strap" / "proxy" / null. */
        val hrvSource: String?,
        /** Count of overnight HRV nights captured from URUJ NDJSON in last 7d. */
        val urujHrvNights7d: Int,
        /**
         * v0.9.4 — per-night RMSSD values, most recent first, last 7d.
         * Empty when URUJ NDJSON path didn't fire (e.g. HC direct record path
         * or no strap data). [ReadinessContextBuilder] consumes this to
         * compute HRV trend direction (RISING/FALLING/FLAT).
         */
        val urujHrvNightlyRmssdMs: List<Float>,
    )

    /**
     * Returns the inputs PLUS source labels naming where RHR / HRV came from.
     * The UI uses these to replace misleading "0 RHR" / "0 HRV" diagnostics
     * with proper source-aware text when the readiness score actually had
     * a derived value via SleepingRhrCalculator or URUJ NDJSON.
     */
    private suspend fun gatherInputsWithSource(): GatheredInputs {
        // If Health Connect isn't available, fall back to cycling-only TSB.
        val sdkOk = HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE
        if (!sdkOk) {
            return GatheredInputs(
                inputs = ReadinessInputs(trainingStressBalance = computeTsb(null, emptySet(), null)),
                rhrSource = null,
                hrvSource = null,
                urujHrvNights7d = 0,
                urujHrvNightlyRmssdMs = emptyList(),
            )
        }
        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }.rethrowCancellation().getOrNull()
            ?: return GatheredInputs(
                inputs = ReadinessInputs(trainingStressBalance = computeTsb(null, emptySet(), null)),
                rhrSource = null,
                hrvSource = null,
                urujHrvNights7d = 0,
                urujHrvNightlyRmssdMs = emptyList(),
            )

        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .rethrowCancellation()
            .getOrDefault(emptySet())

        val now = Instant.now()

        // Unified LastSleepReader — same source of truth as Bio Lab. Fixes the
        // v0.3.6 mismatch where Readiness showed 5.3h while Bio Lab showed 9.2h
        // on the same user's data because the two summed different windows.
        // v0.9.8 — read the full sleep result (window endpoints + hours)
        // so the snapshot can persist start/end times alongside hours.
        // Sleep is the largest Readiness input (35% weight); persisting it
        // unlocks the long-arc sleep trend chart + audit trail.
        //
        // v0.9.46 — TWO reads now:
        //   1. [readAggregated]  → sum of ALL sleep blocks in last 18h, used
        //      for the user-facing SLEEP HOURS display + SleepSnapshot total.
        //      Fixes the 2026-05-25 fragmented-sleep bug where 9.7h actual
        //      sleep appeared as 4.6h "severe deficit" because the rider
        //      rolled over multiple times after a main sleep block.
        //   2. [read]            → most-recent contiguous sleep block, used
        //      as the HRV/RHR computation window. That math needs a single
        //      clean parasympathetic-dominant period, not the union of all
        //      blocks. Two questions, two reads.
        val sleepAggregated = lastSleepReader.readAggregated(client, granted)
        val sleep = sleepAggregated?.hours
        // v0.9.48 — also read stages so the sleep snapshot persists them
        // for stage-aware HRV math + future re-aggregation. Empty when
        // unavailable (graceful).
        val sleepStages: List<SleepStageSegment> = if (sleepAggregated != null) {
            lastSleepReader.readStagesForResult(client, granted, sleepAggregated)
        } else emptyList()
        val sleepAwakeMin = sleepStages
            .filter { !it.isAsleep }
            .sumOf { (it.endMs - it.startMs) / 60_000L }
            .toInt()
        val sleepAsleepHours = sleepAggregated?.let { it.hours - (sleepAwakeMin / 60f) }
        if (sleepAggregated != null) {
            runCatching {
                val sleepDate = LocalDate.now(ZoneId.systemDefault())
                sleepSnapshots.save(
                    SleepSnapshot(
                        dateIsoLocal = sleepDate.toString(),
                        hoursTotal = sleepAggregated.hours,
                        sessionStartMs = sleepAggregated.startedAt.toEpochMilli(),
                        sessionEndMs = sleepAggregated.endedAt.toEpochMilli(),
                        source = "samsung-hc",
                        methodologyVersion = SleepSnapshotRepository.METHODOLOGY_VERSION,
                        computedAtMs = System.currentTimeMillis(),
                        stages = sleepStages,
                        asleepHours = sleepAsleepHours,
                    ),
                    date = sleepDate,
                )
            }.onFailure { Log.w("URUJ-Readiness", "[v0.9.8] sleep snapshot save failed", it) }
        }

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
                // v0.7.7 — also pass strap NDJSON to the calculator so it can
                // pick STRAP when 24/7 service covered the sleep window.
                val weekAgo = now.minus(Duration.ofDays(7))
                val strapSamples = continuousBiometric.hrSamplesForWindow(weekAgo, now)
                val sleepingResult = sleepingRhrCalc.compute(
                    hcSamples = sleepingHrData.first,
                    sleepWindows = sleepingHrData.second,
                    strapSamples = strapSamples,
                )
                if (sleepingResult != null) {
                    rhrToday = sleepingResult.mostRecentNightBpm
                    rhrBaseline = sleepingResult.medianBpm
                    rhrSource = "sleep:${sleepingResult.mostRecentNightSource.displayShort()}"
                }
            }
        }

        // v0.4.0: HRV proxy fallback REMOVED. SleepingHrvProxyCalculator was
        // std-dev of HR samples in sleep windows — directionally correlated
        // with RMSSD but NOT real HRV. Per [[feedback_no_samsung_proxy]] +
        // [[reference_lab_level_uruj]] rule #4 (no fake numbers).
        //
        // v0.7.0: REAL RMSSD HRV unlocked via BLE chest strap (Magene H613)
        // captured 24/7 by BiometricService and stored as RR-interval NDJSON.
        // ContinuousBiometricRepository reads the overnight sleep window and
        // computes RMSSD/SDNN/pNN50 from the actual beat-to-beat data. This
        // is the same calculation Polar / Kubios / EliteHRV do on the same
        // input — real autonomic measurement, not a proxy.
        //
        // Priority:
        //   1. HC direct record (rare on Fit Band 3, never on Magene-only setup)
        //   2. URUJ-computed RMSSD from BLE NDJSON (THIS path is the new win)
        //   3. null — HRV component drops from Readiness score
        // v0.7.0 follow-up — count of days with valid overnight HRV in last 7
        // days. ReadinessCalculator uses this to switch between absolute-tier
        // scoring (1-6 days, no real baseline yet) and ratio-vs-baseline
        // scoring (7+ days, stable baseline). Fixes day-1 "+0%" artifact.
        var hrvDaysOfDataIn7d = 0
        var hrvNightlyRmssdMs: List<Float> = emptyList()
        if (hrvToday == null) {
            // v0.9.8 — use the shared [effectiveHrvWindow] helper so this
            // path agrees with Bio Lab's Autonomic card when sleep data
            // is missing. Pre-v0.9.8: Readiness fell back to 8h, Bio Lab
            // fell back to 24h → divergent RMSSD values on fallback nights.
            // Now both surfaces share the same fallback (8h overnight
            // proxy when no sleep session present — matches deep-sleep
            // duration typical for trained adults).
            val sleepWindowResult = lastSleepReader.read(client, granted)
            // v0.9.48.2 — full aggregated span when stages available (matches
            // BioLabRepository.snapshot() — see that file for rationale).
            // Stage filter handles AWAKE exclusion + between-block gap exclusion.
            // Falls back to single-block last-sleep window when stages absent.
            val (start, end) = if (sleepStages.isNotEmpty() && sleepAggregated != null) {
                sleepAggregated.startedAt to sleepAggregated.endedAt
            } else {
                effectiveHrvWindow(sleepWindowResult, now)
            }
            // v0.9.48 — reuse stages we already pulled for snapshot. Saves
            // a second HC read. Empty list = graceful fallback to pre-v0.9.48
            // whole-window method in computeHrvForWindow.
            val computedHrv = continuousBiometric.computeHrvForWindow(start, end, sleepStages)
            if (computedHrv != null) {
                hrvToday = computedHrv.rmssdMs
                hrvSource = "ble_strap"
                // v0.9.58 — DISK-FIRST historical reads, with methodology
                // safety guard from v0.9.57's ensureBackfilled().
                //
                // The flow:
                //   1. ensureBackfilled() — one-time per process, migrates
                //      any pre-v0.9.48 snapshots to current methodology.
                //      Idempotent: subsequent calls return 0 immediately.
                //   2. listLastNDays(7) — read from disk (fast, ~10ms).
                //   3. Filter results to ONLY current-methodology snapshots.
                //      Defense-in-depth against the v0.9.52 regression:
                //      even if backfill couldn't migrate a snapshot (e.g.
                //      NDJSON missing for that day), we drop it from the
                //      value list rather than poisoning the median.
                //   4. If we have ≥5 current historical snapshots, use the
                //      disk-first fast path (today's fresh value + disk
                //      values = methodology-consistent 7-night list).
                //   5. Otherwise fall back to NDJSON recompute — the
                //      v0.9.52.1 safe path. Slower but guaranteed correct.
                //
                // Net effect after v0.9.57 backfill has run once:
                //   - Cold launch HRV historical: ~14s recompute → ~10ms read
                //   - Readiness compute total: 17s → ~3-5s
                //   - Math byte-identical (same calculator, just read once
                //     and cached on disk vs recomputed each session).
                //
                // See [[reference_perf_architecture_findings_2026_05_27]]
                // for the v0.9.52 regression analysis that informed this
                // careful re-enable.
                runCatching {
                    hrvSnapshots.ensureBackfilled(
                        continuousBiometric,
                        lastSleepReader,
                        client,
                        granted,
                    )
                }.rethrowCancellation()
                    .onFailure { Log.w("URUJ-Readiness", "[v0.9.58] HRV backfill failed", it) }
                    .onSuccess { migrated ->
                        if (migrated > 0) {
                            Log.d(
                                "URUJ-Readiness",
                                "[v0.9.58] HRV backfill migrated $migrated snapshots before read",
                            )
                        }
                    }
                // v0.9.64 — one-shot freq-domain recovery for snapshots that
                // lost LF/HF + DFA α1 + SD1/SD2 + sample entropy due to the
                // v0.9.61 Readiness-overwrite regression. Idempotent; runs
                // once per process. For the specific case captured 2026-05-29:
                // recovers 2026-05-28 snapshot whose freq-domain fields were
                // nulled by Readiness's 23:50 IST save (then frozen at midnight).
                // Math validated via pure-Python RMSSD check — see
                // HrvSnapshotRepository.ensureFreqDomainBackfilled() KDoc.
                runCatching {
                    hrvSnapshots.ensureFreqDomainBackfilled(
                        continuousBiometric,
                        lastSleepReader,
                        client,
                        granted,
                    )
                }.rethrowCancellation()
                    .onFailure { Log.w("URUJ-Readiness", "[v0.9.64] freq-domain backfill failed", it) }
                    .onSuccess { recovered ->
                        if (recovered > 0) {
                            Log.d(
                                "URUJ-Readiness",
                                "[v0.9.64] freq-domain backfill recovered $recovered snapshots",
                            )
                        }
                    }

                val today = LocalDate.now(ZoneId.systemDefault())
                val historicalSnapshots = runCatching {
                    hrvSnapshots.listLastNDays(7)
                }.rethrowCancellation().getOrDefault(emptyList())
                val acceptableMethodologies = setOf(
                    HrvSnapshotRepository.METHODOLOGY_VERSION,
                    HrvSnapshotRepository.METHODOLOGY_VERSION_FALLBACK,
                )
                val currentHistoricalRmssd = historicalSnapshots
                    .filter { it.methodologyVersion in acceptableMethodologies }
                    .filter { it.dateIsoLocal != today.toString() }  // dedup today (fresh value owns it)
                    .mapNotNull { it.rmssdMs }

                if (currentHistoricalRmssd.size >= 5) {
                    // Fast path: enough current-methodology snapshots on disk.
                    // Combined with today's fresh value = methodology-consistent
                    // 6+ night list. ReadinessCalculator's 7-day stats become
                    // reliable when this size reaches 7.
                    val nightly = listOf(computedHrv.rmssdMs) + currentHistoricalRmssd
                    hrvDaysOfDataIn7d = nightly.size
                    if (nightly.size >= 2) {
                        val sorted = nightly.sorted()
                        hrvBaseline = sorted[sorted.size / 2]
                    }
                    hrvNightlyRmssdMs = nightly
                    Log.d(
                        "URUJ-Readiness",
                        "[v0.9.58] HRV disk-first hit: ${nightly.size} nights from disk " +
                            "(${currentHistoricalRmssd.size} historical + today fresh, " +
                            "saved ~14s recompute)",
                    )
                } else {
                    // Fallback: not enough current snapshots — recompute via
                    // the v0.9.52.1 safe path. Slower (~14s) but methodology-
                    // consistent.
                    Log.d(
                        "URUJ-Readiness",
                        "[v0.9.58] HRV disk-first miss: only ${currentHistoricalRmssd.size}/5+ " +
                            "current snapshots in listLastNDays(7) — recomputing from NDJSON",
                    )
                    val recentSleeps = lastSleepReader.listLastNDays(client, granted, 7)
                    val recentNights = continuousBiometric
                        .dailyOvernightHrvHistoryFromSessions(recentSleeps)
                    hrvDaysOfDataIn7d = recentNights.size
                    if (recentNights.size >= 2) {
                        val sorted = recentNights.map { it.hrv.rmssdMs }.sorted()
                        hrvBaseline = sorted[sorted.size / 2]
                    }
                    // v0.9.4 — preserve per-night RMSSD list (newest first) for
                    // the ReadinessContextBuilder to compute trend direction.
                    hrvNightlyRmssdMs = recentNights.map { it.hrv.rmssdMs }
                }
                // Note: when nightly.size / recentNights.size < 2 we leave
                // hrvBaseline = null. ReadinessCalculator will use
                // absolute-tier scoring instead of computing a meaningless
                // "+0% vs same value" ratio.

                // v0.9.61 — persist today's HRV snapshot to disk so the
                // statistical layer (ReadinessContextBuilder.hrvNightsFromDisk
                // via hrvSnapshots.listAll()) and the trend chart see today's
                // value without requiring Bio Lab to open first. Closes #211.
                //
                // Pre-v0.9.61 ONLY BioLabRepository.snapshot() wrote today's
                // HRV snapshot. Result: on first morning open before Bio Lab
                // is touched, ReadinessContextBuilder line 120 prefers
                // hrvNightsFromDisk (which excludes today) over the live 7d
                // list (which includes today's inline-computed value) because
                // disk has more nights than the 7d window. The displayed
                // 7d median, CV%, trend slope, and WoW% all lagged by one day.
                //
                // The score itself was NEVER affected — the disk-first read
                // at lines 853-863 above explicitly EXCLUDES today
                // (`dedup today (fresh value owns it)`) and always uses the
                // inline-computed `computedHrv.rmssdMs`. This fix only closes
                // the statistical-display gap.
                //
                // Time-domain only — frequency-domain compute (LF/HF/VLF +
                // DFA α1 + sample entropy) is intentionally kept exclusive to
                // Bio Lab where it already runs because (a) the Readiness
                // score doesn't consume it, (b) it's the expensive part of
                // the HRV pipeline (~3s of Lomb-Scargle FFT × 100 windows),
                // and (c) today-mutable rule allows Bio Lab to overwrite this
                // snapshot with the full time+freq+stage record when the user
                // opens that tab. No data loss, no compute doubling — only
                // the cheap RMSSD/SDNN/pNN math is duplicated and that
                // duplication already existed pre-v0.9.61.
                //
                // Per-stage breakdown IS included when sleep stages were
                // passed (matches BioLab's logic) so the stage-aware
                // methodology version is honored even on the Readiness-first
                // path. awakeMinutesExcluded is left null — that field is
                // populated by BioLab where it's already tracked; Readiness
                // doesn't currently compute it and adding the math here would
                // duplicate Bio Lab's work for no benefit (the field is
                // informational, not consumed by any signal-pack downstream).
                runCatching {
                    val todayDate = LocalDate.now(ZoneId.systemDefault())
                    // v0.9.64 — `saveMerged` instead of `save`. Field-by-field
                    // non-null merge preserves whatever freq-domain (LF/HF,
                    // DFA α1, SD1/SD2, sample entropy, totalPowerMs2)
                    // BioLab may have already written for today. Pre-v0.9.64
                    // we used save() which silently wiped those fields with
                    // null defaults — that caused the 2026-05-28 data-loss
                    // regression (Readiness fired at 23:50 IST overwriting
                    // BioLab's 18:36 full save, then midnight froze it).
                    // See HrvSnapshotRepository.saveMerged() KDoc for the
                    // full merge semantics + race-safety rationale.
                    hrvSnapshots.saveMerged(
                        HrvSnapshot(
                            dateIsoLocal = todayDate.toString(),
                            rmssdMs = computedHrv.rmssdMs,
                            sdnnMs = computedHrv.sdnnMs,
                            pnn50Percent = computedHrv.pnn50Percent,
                            pnn20Percent = computedHrv.pnn20Percent,
                            meanHrBpm = computedHrv.meanHrBpm,
                            sampleCount = computedHrv.sampleCount,
                            windowCount = computedHrv.windowCount,
                            source = "strap",
                            // Frequency-domain + non-linear left null —
                            // populated when Bio Lab opens (today-mutable).
                            // saveMerged() preserves these from existing snapshot.
                            deepRmssdMs = computedHrv.perStageRmssdMs?.get("deep"),
                            remRmssdMs = computedHrv.perStageRmssdMs?.get("rem"),
                            lightRmssdMs = computedHrv.perStageRmssdMs?.get("light"),
                            stageFiltered = sleepStages.isNotEmpty(),
                            awakeMinutesExcluded = null,
                            computedAtMs = System.currentTimeMillis(),
                            methodologyVersion = if (sleepStages.isNotEmpty()) {
                                HrvSnapshotRepository.METHODOLOGY_VERSION
                            } else {
                                HrvSnapshotRepository.METHODOLOGY_VERSION_FALLBACK
                            },
                        ),
                        date = todayDate,
                    )
                }.rethrowCancellation()
                    .onFailure {
                        Log.w("URUJ-Readiness", "[v0.9.61] HRV snapshot save failed", it)
                    }
            }
        }

        // For multi-sport TSB, prefer rhrBaseline (7d median, stable) — falls
        // back to rhrToday if baseline missing. This is the personal RHR that
        // anchors the HR Reserve fraction for running/HIIT/etc hrTSS.
        val rhrForLoad = rhrBaseline ?: rhrToday

        // v0.9.1 — compute TSB once with full detail, then both:
        //   (1) use the scalar .tsb for ReadinessInputs (downstream scoring)
        //   (2) persist the daily snapshot (CTL + ATL + TSB) to disk for the
        //       future TSB trend chart that watches fitness curves over months
        val tsbDetailed = computeTsbDetailed(client, granted, rhrForLoad)
        if (tsbDetailed != null) {
            val today = LocalDate.now(ZoneId.systemDefault())
            tsbSnapshots.save(
                TsbSnapshot(
                    dateIsoLocal = today.toString(),
                    tsb = tsbDetailed.tsb,
                    ctl = tsbDetailed.ctl,
                    atl = tsbDetailed.atl,
                    totalLoad42d = tsbDetailed.totalLoad42d,
                    methodologyVersion = TsbSnapshotRepository.METHODOLOGY_VERSION,
                    computedAtMs = System.currentTimeMillis(),
                ),
                date = today,
            )
            // v0.9.11 — cascade dedup. Pre-fix: full Readiness compute (Checklist
            // path) wrote TSB snapshot but didn't update lastTsbRefreshMs, so
            // an immediate Bio Lab open ~8s later triggered the lightweight
            // refreshTsbSnapshotForToday() to compute TSB AGAIN. Same value,
            // wasted ~7 HC reads per cascade. Now Checklist path also marks the
            // refresh — Bio Lab cooldown check sees recent activity and skips.
            lastTsbRefreshMs = System.currentTimeMillis()
        }

        return GatheredInputs(
            inputs = ReadinessInputs(
                sleepLastNightHours = sleep,
                hrvTodayRmssd = hrvToday,
                hrvBaseline7d = hrvBaseline,
                restingHrToday = rhrToday,
                restingHrBaseline7d = rhrBaseline,
                trainingStressBalance = tsbDetailed?.tsb,
                hrvDaysOfDataIn7d = hrvDaysOfDataIn7d,
            ),
            rhrSource = rhrSource,
            hrvSource = hrvSource,
            urujHrvNights7d = hrvDaysOfDataIn7d,
            urujHrvNightlyRmssdMs = hrvNightlyRmssdMs,
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
        }.rethrowCancellation()
            .onFailure { Log.w("URUJ-Readiness", "sleeping HR inputs read failed", it) }
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
        }.rethrowCancellation()
            .onFailure { Log.w("URUJ-Readiness", "HRV read failed", it) }
            .getOrDefault(null to null)
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
        }.rethrowCancellation()
            .onFailure { Log.w("URUJ-Readiness", "RHR read failed", it) }
            .getOrDefault(null to null)
    }

    /**
     * Training Stress Balance — Coggan-style EWMA across BOTH cycling rides AND
     * Samsung-recorded non-cycling sessions (running, HIIT, strength etc.).
     *
     *   ATL = EWMA of last 7 days of TSS  (α = 1/7)
     *   CTL = EWMA of last 42 days of TSS (α = 1/42)
     *   TSB = CTL − ATL  (positive = fresh, negative = fatigued)
     *
     * Cycling TSS (URUJ rides): IF² × hours × 100, IF = avgPower / FTP.
     *
     * Non-cycling hrTSS (Samsung exercise sessions): IF_hr² × hours × 100, where
     *   IF_hr = ((avgHR − RHR) / (MaxHR − RHR)) / 0.87
     *   The /0.87 normalizes "HR Reserve at lactate threshold" to IF=1.0, so a
     *   1h run at threshold pace produces ~100 TSS, matching cycling's scale.
     *   (0.87 = HR-Reserve fraction at ~88% maxHR LTHR, standard convention.)
     *
     * Cycling Samsung sessions are SKIPPED — URUJ rides are the authoritative
     * cycling source. Sessions within ±2 min of a URUJ ride are also skipped
     * (Samsung sometimes auto-detects what URUJ already recorded).
     */
    /**
     * v0.9.1 — TSB compute now returns the detailed CTL + ATL + total-load
     * triple, not just the headline TSB scalar. Caller in [compute] uses
     * `.tsb` for ReadinessInputs (unchanged) AND saves a daily disk snapshot
     * capturing all three values for the future TSB trend chart (the long-
     * arc fitness curve that lets riders see taper / overload / recovery
     * patterns across months).
     *
     * Old `computeTsb` shape (returning Float?) is preserved at call sites
     * through a thin wrapper [computeTsbScalar] so any code path that just
     * needs the number keeps working.
     */
    private suspend fun computeTsbDetailed(
        client: HealthConnectClient?,
        granted: Set<String>,
        athleticRhr: Int?,
        /**
         * v0.9.11 — pass a historical date for backfill. Default = today.
         * The EWMA loop runs with this date as "day 0", so daysAgo is
         * computed relative to it. Lets us produce per-day TSB snapshots
         * for any historical date up to HC's ExerciseSessionRecord
         * retention (~42d). Older days where HC dropped Samsung sessions
         * will under-count multi-sport load — accepted limitation.
         */
        targetDate: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    ): TsbCompute? {
        val rides = historyRepo.listAll()
        val zone = ZoneId.systemDefault()
        val todayDate = targetDate
        val dailyTss = FloatArray(43)
        var totalLoad = 0f
        val urujRideStarts = mutableListOf<Instant>()

        // 1. URUJ cycling rides (power-based TSS)
        val ftp = rides.lastOrNull()?.ftpWatts?.coerceAtLeast(1) ?: 200
        var urujRideTssTotal = 0f
        var urujRidesInWindow = 0
        for (ride in rides) {
            val rideInstant = Instant.ofEpochMilli(ride.startedAtMs)
            val rideDate = rideInstant.atZone(zone).toLocalDate()
            val daysAgo = ChronoUnit.DAYS.between(rideDate, todayDate).toInt()
            if (daysAgo !in 0..42) continue
            val hours = ride.movingTimeMs / 3_600_000f
            val intensityFactor = if (ride.averagePowerWatts > 0f) {
                ride.averagePowerWatts / ftp
            } else 0f
            val tss = intensityFactor * intensityFactor * hours * 100f
            dailyTss[daysAgo] += tss
            totalLoad += tss
            urujRideTssTotal += tss
            urujRidesInWindow++
            urujRideStarts += rideInstant
        }

        // 2. Samsung non-cycling sessions (HR-based hrTSS). Needs RHR + maxHR
        //    to compute HR Reserve fraction. If RHR unknown, skip — fake hrTSS
        //    without a personal baseline would mislead.
        var samsungHrTssTotal = 0f
        var samsungSessionsInWindow = 0
        if (client != null && athleticRhr != null && athleticRhr > 0) {
            val profile = runCatching { profileStore.current() }.rethrowCancellation().getOrNull()
            val maxHr = profile?.maxHrBpm ?: 190
            if (maxHr > athleticRhr) {
                val sessions = readNonCyclingSessionLoads(
                    client = client,
                    granted = granted,
                    todayDate = todayDate,
                    zone = zone,
                    urujRideStarts = urujRideStarts,
                    rhr = athleticRhr,
                    maxHr = maxHr,
                )
                for (s in sessions) {
                    if (s.daysAgo !in 0..42) continue
                    dailyTss[s.daysAgo] += s.tss
                    totalLoad += s.tss
                    samsungHrTssTotal += s.tss
                    samsungSessionsInWindow++
                }
            }
        }

        // Below 1 TSS total: not enough load to compute a meaningful balance.
        // 2-ride floor preserved for cycling-only riders (no non-cycling data).
        if (totalLoad < 1f) return null
        if (urujRideStarts.size < 2 && athleticRhr == null) return null

        var atl = 0f
        var ctl = 0f
        for (day in 42 downTo 0) {
            val tss = dailyTss[day]
            atl = atl * (1f - 1f / 7f) + tss * (1f / 7f)
            ctl = ctl * (1f - 1f / 42f) + tss * (1f / 42f)
        }
        val tsbValue = ctl - atl
        // v0.9.7 — verbose log on TSB compute path. When the rider asks "why
        // did TSB shift?" between two Bio Lab opens, this log line documents
        // exactly which inputs the engine saw at compute time. Pair with the
        // [TsbSnapshotRepository] save log to trace drift via Samsung's
        // batched HC sync arriving mid-day.
        Log.d(
            "URUJ-TSB",
            "[v0.9.7] computed: TSB=${"%.2f".format(tsbValue)} " +
                "(CTL=${"%.2f".format(ctl)} · ATL=${"%.2f".format(atl)}) | " +
                "inputs: $urujRidesInWindow URUJ rides → TSS ${"%.0f".format(urujRideTssTotal)} · " +
                "$samsungSessionsInWindow Samsung non-cycling sessions → hrTSS ${"%.0f".format(samsungHrTssTotal)} · " +
                "totalLoad42d ${"%.0f".format(totalLoad)} · " +
                "athleticRhr=${athleticRhr ?: "null"} · " +
                "ftp=$ftp",
        )
        return TsbCompute(tsb = tsbValue, ctl = ctl, atl = atl, totalLoad42d = totalLoad)
    }

    /**
     * Backwards-compatible wrapper — same signature + return as pre-v0.9.1.
     * Used by call sites that only need the scalar TSB value.
     */
    private suspend fun computeTsb(
        client: HealthConnectClient?,
        granted: Set<String>,
        athleticRhr: Int?,
    ): Float? = computeTsbDetailed(client, granted, athleticRhr)?.tsb

    /** v0.9.1 — full CTL/ATL/TSB triple plus the 42d total load contribution. */
    private data class TsbCompute(
        val tsb: Float,
        val ctl: Float,
        val atl: Float,
        val totalLoad42d: Float,
    )

    private data class SessionLoad(val daysAgo: Int, val tss: Float)

    /**
     * Reads Samsung exercise sessions from HC (last 42d), filters out cycling
     * (URUJ owns that), filters out sessions overlapping URUJ rides within ±2min,
     * and computes hrTSS per session.
     */
    private suspend fun readNonCyclingSessionLoads(
        client: HealthConnectClient,
        granted: Set<String>,
        todayDate: LocalDate,
        zone: ZoneId,
        urujRideStarts: List<Instant>,
        rhr: Int,
        maxHr: Int,
    ): List<SessionLoad> {
        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) !in granted) return emptyList()
        if (HealthPermission.getReadPermission(HeartRateRecord::class) !in granted) return emptyList()
        val now = Instant.now()
        val cutoff = now.minus(Duration.ofDays(42))
        val sessions = runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(cutoff, now),
                    ascendingOrder = false,
                    pageSize = 200,
                ),
            ).records
        }.rethrowCancellation()
            .onFailure { Log.w("URUJ-Readiness", "exercise sessions read failed", it) }
            .getOrDefault(emptyList())
        if (sessions.isEmpty()) return emptyList()

        val hrReserveRange = (maxHr - rhr).toFloat()
        val results = mutableListOf<SessionLoad>()
        for (session in sessions) {
            // Skip cycling — URUJ owns it.
            if (session.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_BIKING ||
                session.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
            ) continue
            // Skip sessions overlapping URUJ rides (Samsung sometimes auto-detects
            // a cycling workout for the same window URUJ recorded).
            val overlapsUruj = urujRideStarts.any { urujStart ->
                Duration.between(urujStart, session.startTime).abs() < Duration.ofMinutes(2)
            }
            if (overlapsUruj) continue

            val durationMs = Duration.between(session.startTime, session.endTime).toMillis()
            val durationMin = durationMs / 60_000f
            if (durationMin < 5f) continue  // too short to count

            val hrSamples = runCatching {
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime),
                        ascendingOrder = false,
                        pageSize = 500,
                    ),
                ).records.flatMap { it.samples }.map { it.beatsPerMinute.toInt() }
            }.rethrowCancellation().getOrDefault(emptyList())
            if (hrSamples.isEmpty()) continue

            val avgHr = hrSamples.average().toInt()
            if (avgHr <= rhr) continue  // sub-rest is invalid

            // HR Reserve fraction, normalized to IF=1.0 at threshold (0.87 HRR).
            val hrReserveFrac = (avgHr - rhr).toFloat() / hrReserveRange
            val intensityFactor = (hrReserveFrac / 0.87f).coerceIn(0f, 1.4f)
            val hours = durationMin / 60f
            val hrTss = intensityFactor * intensityFactor * hours * 100f

            val sessionDate = session.startTime.atZone(zone).toLocalDate()
            val daysAgo = ChronoUnit.DAYS.between(sessionDate, todayDate).toInt()
            results += SessionLoad(daysAgo, hrTss)
        }
        return results
    }

    companion object {
        /**
         * v0.9.8 — minimum gap between TSB-only refreshes. Caps the
         * blast-radius from a worst-case rider with many Samsung sessions
         * (~51 HC reads/refresh) at 12 refreshes/hour. Aligned with the
         * v0.8.4 HC architecture envelope.
         */
        private const val TSB_REFRESH_COOLDOWN_MS = 5L * 60L * 1000L
    }
}

/**
 * v0.9.8 — shared HRV-window resolver. Pre-v0.9.8 [ReadinessRepository]
 * (8h fallback) and [BioLabRepository] (24h fallback) used different
 * windows when Samsung sleep data was missing — same overnight HRV could
 * produce two different displayed RMSSD values across the two surfaces.
 *
 * Now both call this helper. When sleep session is present, use its
 * window (the clean signal). When absent, fall back to 8h overnight
 * proxy (deep-sleep duration typical for trained adults — matches the
 * window most HRV apps use for ratio-mode baseline).
 */
fun effectiveHrvWindow(
    sleepResult: LastSleepReader.Result?,
    now: java.time.Instant,
): Pair<java.time.Instant, java.time.Instant> =
    if (sleepResult != null) {
        sleepResult.startedAt to sleepResult.endedAt
    } else {
        now.minus(java.time.Duration.ofHours(8)) to now
    }
