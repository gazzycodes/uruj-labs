package com.uruj.data

import android.content.Context
import android.util.Log
import com.uruj.domain.CarToday
import com.uruj.domain.Hrr1Today
import com.uruj.domain.HrvToday
import com.uruj.domain.OrthostaticToday
import com.uruj.domain.ReadinessContext
import com.uruj.domain.ReadinessInputs
import com.uruj.domain.RhrToday
import com.uruj.domain.SleepToday
import com.uruj.domain.TrendDirection
import com.uruj.domain.TrendSeries
import com.uruj.domain.TsbToday
import com.uruj.domain.Vo2Today
import com.uruj.power.CarDetector
import com.uruj.util.rethrowCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * v0.9.4 — assembles a [ReadinessContext] from every snapshot repository
 * + the freshly-gathered HC inputs. See
 * [[reference_readiness_context_architecture]] for the architectural law.
 *
 * # Design
 *
 * Pure aggregator. Reads disk snapshots (cheap), computes trend slopes
 * + pattern streaks, never touches HC directly. The caller
 * ([ReadinessRepository]) provides the HC-side inputs already gathered via
 * the existing flow — this builder layers the longitudinal view on top.
 *
 * Each snapshot type contributes:
 *   - `today` field — most-recent value
 *   - `trends` entry — slope + direction over rolling window
 *   - `patterns` counter(s) where applicable (e.g. tsbUnderwaterDays)
 *
 * Missing data is not an error — it's a signal, surfaced via
 * `provenance.missingSignals` so the reasoner can adjust copy firmness.
 *
 * # Adding a new biomarker
 *
 * See `reference_readiness_context_architecture` checklist. Steps:
 *  1. Add `*Today` field to [ReadinessContext.TodaySnapshot]
 *  2. Build it here in this file from the new repo
 *  3. Add trend slice to [ReadinessContext.Trends] if longitudinal
 *  4. Add streak/pattern counter to [ReadinessContext.Patterns] if applicable
 *  5. Add methodology version tag
 *
 * Do NOT skip — see anti-patterns in the architecture memory.
 */
class ReadinessContextBuilder(context: Context) {

    private val appContext = context.applicationContext
    private val hrrSnapshots = HrrSnapshotRepository(appContext)
    private val rhrSnapshots = RhrSnapshotRepository(appContext)
    private val vo2Snapshots = Vo2SnapshotRepository(appContext)
    private val tsbSnapshots = TsbSnapshotRepository(appContext)
    private val recSnapshots = RecommendationSnapshotRepository(appContext)
    private val hrvSnapshots = HrvSnapshotRepository(appContext)  // v0.9.27
    private val carRepo = CarRepository(appContext)
    private val orthostaticRepo = OrthostaticTestRepository(appContext)
    private val carDetector = CarDetector()

    /**
     * Build the context. Cheap — reads disk snapshots (already on file, no
     * HC round-trips). The caller provides the freshly-gathered HC inputs
     * + source labels so we don't double-read what ReadinessRepository
     * already pulled.
     */
    suspend fun build(
        inputs: ReadinessInputs,
        rhrSource: String?,
        hrvSource: String?,
        urujHrvNights7d: Int,
        /**
         * Per-night RMSSD values from URUJ NDJSON, newest first. Used to
         * compute HRV trend direction. Empty when no overnight HRV data.
         */
        urujHrvNightlyRmssdMs: List<Float> = emptyList(),
        sleepEndMs: Long? = null,
        rhrCapturedAtMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): ReadinessContext = withContext(Dispatchers.IO) {
        val today = LocalDate.now(ZoneId.systemDefault())

        val rhrHistory = runCatching { rhrSnapshots.listAll() }.rethrowCancellation().getOrDefault(emptyList())
        val vo2History = runCatching { vo2Snapshots.listAll() }.rethrowCancellation().getOrDefault(emptyList())
        val tsbHistory = runCatching { tsbSnapshots.listAll() }.rethrowCancellation().getOrDefault(emptyList())
        val hrrHistory = runCatching { hrrSnapshots.listAll() }.rethrowCancellation().getOrDefault(emptyList())
        // v0.9.27 — load today's HRV snapshot (if persisted by BioLabRepository)
        // to populate the HrvToday.frequencyDomain signal in ReadinessContext.
        // Per [[reference_readiness_context_architecture]] every biomarker plugs
        // into the signal pack so the engine, AI coach, and future biomarkers
        // all read from the same struct.
        val todayHrvSnap = runCatching { hrvSnapshots.load(today.toString()) }
            .rethrowCancellation().getOrNull()
        val carResult = runCatching { carRepo.cachedLatest() }.rethrowCancellation().getOrNull()
        val orthostaticResult = runCatching { orthostaticRepo.latest() }.rethrowCancellation().getOrNull()

        val todaySnap = ReadinessContext.TodaySnapshot(
            sleep = inputs.sleepLastNightHours?.let {
                SleepToday(
                    hours = it,
                    sessionEndMs = sleepEndMs ?: nowMs,
                    source = "samsung-hc",
                )
            },
            hrv = inputs.hrvTodayRmssd?.let { rmssd ->
                HrvToday(
                    rmssdMs = rmssd,
                    ratioVsBaseline = inputs.hrvBaseline7d
                        ?.takeIf { it > 0f && inputs.hrvDaysOfDataIn7d >= 7 }
                        ?.let { rmssd / it },
                    daysOfDataIn7d = inputs.hrvDaysOfDataIn7d,
                    source = hrvSource ?: "unknown",
                    capturedAtMs = nowMs,
                    // v0.9.27 — plug freq-domain + non-linear into the signal
                    // pack when today's snapshot exists. Engine doesn't consume
                    // these yet but the field is populated so future AI coach +
                    // tier-B test pipelines + reasoner upgrades can read one
                    // canonical struct (architecture rule).
                    frequencyDomain = todayHrvSnap?.let { snap ->
                        com.uruj.domain.HrvFrequencyDomainSignals(
                            vlfMs2 = snap.vlfMs2,
                            lfMs2 = snap.lfMs2,
                            hfMs2 = snap.hfMs2,
                            totalPowerMs2 = snap.totalPowerMs2,
                            lfHfRatio = snap.lfHfRatio,
                            sd1Ms = snap.sd1Ms,
                            sd2Ms = snap.sd2Ms,
                            dfaAlpha1 = snap.dfaAlpha1,
                            sampleEntropy = snap.sampleEntropy,
                            sampleCount = snap.sampleCount,
                        )
                    },
                )
            },
            rhr = inputs.restingHrToday?.let {
                RhrToday(
                    todayBpm = it,
                    baselineBpm = inputs.restingHrBaseline7d,
                    source = rhrSource,
                    capturedAtMs = rhrCapturedAtMs ?: nowMs,
                )
            },
            tsb = inputs.trainingStressBalance?.let { tsb ->
                val mostRecent = tsbHistory.firstOrNull()
                TsbToday(
                    value = tsb,
                    ctl = mostRecent?.ctl ?: 0f,
                    atl = mostRecent?.atl ?: 0f,
                    totalLoad42d = mostRecent?.totalLoad42d ?: 0f,
                    capturedAtMs = mostRecent?.computedAtMs ?: nowMs,
                )
            },
            car = carResult?.let { car ->
                val interp = carDetector.interpret(car)
                val ageHours = (nowMs - car.sleepEndMs) / 3_600_000f
                CarToday(
                    tier = interp.overallTier,
                    amplitudeBpm = car.amplitudeBpm,
                    latencyMinutes = car.latencyMinutes,
                    rmssdDropPercent = car.rmssdDropPercent,
                    ageHours = ageHours,
                    capturedAtMs = car.computedAtMs,
                )
            },
            orthostatic = orthostaticResult?.let { test ->
                val ageHours = (nowMs - test.startedAtMs) / 3_600_000f
                OrthostaticToday(
                    hrDeltaBpm = test.hrDeltaBpm.toInt(),
                    rmssdRatio = test.rmssdRatio,
                    ageHours = ageHours,
                    capturedAtMs = test.startedAtMs,
                )
            },
            hrr1 = hrrHistory.firstOrNull()?.let { last ->
                val daysAgo = ((nowMs - last.sessionEndMs) / 86_400_000L).toInt().coerceAtLeast(0)
                // Median across all snapshots for the headline figure.
                val median = hrrHistory.map { it.hrr1Bpm }.sorted().let {
                    if (it.isEmpty()) 0 else it[it.size / 2]
                }
                Hrr1Today(
                    medianBpm = median,
                    classification = last.classification,
                    samplesCount = hrrHistory.size,
                    lastReadingAgeDays = daysAgo,
                )
            },
            vo2 = vo2History.firstOrNull()?.let {
                Vo2Today(
                    urujConsensusMlKgMin = it.urujConsensusMlKgMin,
                    samsungMlKgMin = it.samsungMlKgMin,
                    classification = it.classification,
                    capturedAtMs = it.computedAtMs,
                )
            },
        )

        val trends = ReadinessContext.Trends(
            hrv = computeTrend(
                values = urujHrvNightlyRmssdMs,
                windowDays = 7,
                flatBand = HRV_FLAT_BAND_MS_PER_DAY,
            ),
            rhr = computeTrend(
                values = rhrHistory.take(30).map { it.medianBpm.toFloat() },
                windowDays = 30,
                flatBand = RHR_FLAT_BAND_BPM_PER_DAY,
            ),
            tsb = computeTrend(
                values = tsbHistory.take(42).map { it.tsb },
                windowDays = 42,
                flatBand = TSB_FLAT_BAND_PER_DAY,
            ),
            vo2 = computeTrend(
                values = vo2History.take(30).map { it.urujConsensusMlKgMin },
                windowDays = 30,
                flatBand = VO2_FLAT_BAND_PER_DAY,
            ),
            hrr1 = computeTrend(
                values = hrrHistory.take(20).map { it.hrr1Bpm.toFloat() },
                windowDays = 20,
                flatBand = HRR1_FLAT_BAND_PER_DAY,
            ),
            sleep = null,
        )

        val patterns = ReadinessContext.Patterns(
            consecutiveRestDays = recSnapshots.consecutiveDaysMatching(today.minusDays(1)) {
                it.tier == "FullRest"
            },
            consecutiveLowReadinessDays = recSnapshots.consecutiveDaysMatching(today.minusDays(1)) {
                it.score < 50
            },
            missingHrvNights7d = (7 - urujHrvNights7d.coerceAtMost(7)).coerceAtLeast(0),
            missingSleepNights7d = 0,  // TODO: compute from LastSleepReader.listLastNDays when needed
            hrvCrashStreak = 0,         // TODO: requires per-night HRV history; today's reading drives FLAG, streak waits for v0.9.5
            tsbUnderwaterDays = countLeadingMatches(tsbHistory) { it.tsb <= TSB_UNDERWATER_THRESHOLD },
            sleepDebt7d = 0f,
        )

        val missingSignals = buildList {
            if (todaySnap.hrv == null) add("hrv-last-night")
            if (todaySnap.sleep == null) add("sleep-last-night")
            if (todaySnap.rhr == null) add("rhr-baseline")
            if (todaySnap.tsb == null) add("training-load")
            if (todaySnap.car == null || (todaySnap.car?.ageHours ?: Float.MAX_VALUE) > 24f) {
                add("car-today")
            }
        }

        val methodologyVersions = mapOf(
            "hrr1" to HrrSnapshotRepository.METHODOLOGY_VERSION,
            "rhr" to RhrSnapshotRepository.METHODOLOGY_VERSION,
            "vo2" to Vo2SnapshotRepository.METHODOLOGY_VERSION,
            "tsb" to TsbSnapshotRepository.METHODOLOGY_VERSION,
            "recommendation" to RecommendationSnapshotRepository.METHODOLOGY_VERSION,
        )

        val confidence = computeConfidence(todaySnap)

        Log.d(
            TAG,
            "context built: confidence=${"%.2f".format(confidence)} " +
                "missing=${missingSignals.size} restStreak=${patterns.consecutiveRestDays} " +
                "tsbUnderwater=${patterns.tsbUnderwaterDays}",
        )

        ReadinessContext(
            today = todaySnap,
            trends = trends,
            patterns = patterns,
            provenance = ReadinessContext.Provenance(
                builtAtMs = nowMs,
                overallConfidence = confidence,
                missingSignals = missingSignals,
                methodologyVersions = methodologyVersions,
            ),
        )
    }

    /**
     * Linear-regression slope across the values. Values are listed
     * newest-first; we re-index oldest=0 so positive slope means
     * "improving over time." Returns INSUFFICIENT_DATA when < 3 samples.
     */
    private fun computeTrend(
        values: List<Float>,
        windowDays: Int,
        flatBand: Float,
    ): TrendSeries {
        if (values.size < 3) {
            return TrendSeries(
                windowDays = windowDays,
                sampleCount = values.size,
                slopePerDay = 0f,
                direction = TrendDirection.INSUFFICIENT_DATA,
                mostRecentValues = values,
            )
        }
        // Re-index: oldest first (index 0..n-1). Original is newest-first.
        val reversed = values.reversed()
        val n = reversed.size
        val xs = (0 until n).map { it.toFloat() }
        val xMean = xs.average().toFloat()
        val yMean = reversed.average().toFloat()
        var num = 0f
        var den = 0f
        for (i in 0 until n) {
            num += (xs[i] - xMean) * (reversed[i] - yMean)
            den += (xs[i] - xMean) * (xs[i] - xMean)
        }
        val slope = if (den == 0f) 0f else num / den
        val direction = when {
            slope > flatBand -> TrendDirection.RISING
            slope < -flatBand -> TrendDirection.FALLING
            else -> TrendDirection.FLAT
        }
        return TrendSeries(
            windowDays = windowDays,
            sampleCount = n,
            slopePerDay = slope,
            direction = direction,
            mostRecentValues = values,
        )
    }

    /** Count leading items matching predicate (newest-first list). */
    private fun <T> countLeadingMatches(list: List<T>, predicate: (T) -> Boolean): Int {
        var count = 0
        for (item in list) {
            if (predicate(item)) count++ else break
        }
        return count
    }

    /**
     * 0–1 weighted by signal availability + freshness. Used by reasoner to
     * pick decisive vs hedged copy. Today's core signals (sleep + HRV + RHR
     * + TSB) carry 60% of the weight; supplementary (CAR + Orthostatic +
     * HRR1 + VO2) carry the remaining 40%.
     */
    private fun computeConfidence(today: ReadinessContext.TodaySnapshot): Float {
        var sum = 0f
        if (today.sleep != null) sum += 0.20f
        if (today.hrv != null) sum += 0.20f
        if (today.rhr != null) sum += 0.10f
        if (today.tsb != null) sum += 0.10f
        // Supplementary (only count when reasonably fresh).
        if (today.car != null && today.car.ageHours <= 24f) sum += 0.15f
        if (today.orthostatic != null && today.orthostatic.ageHours <= 72f) sum += 0.10f
        if (today.hrr1 != null && today.hrr1.lastReadingAgeDays <= 14) sum += 0.10f
        if (today.vo2 != null) sum += 0.05f
        return sum.coerceIn(0f, 1f)
    }

    companion object {
        private const val TAG = "URUJ-Context"

        // Flat-band thresholds — slope below these is "no real direction."
        // Tuned for the signal's typical noise floor.
        private const val HRV_FLAT_BAND_MS_PER_DAY = 0.50f
        private const val RHR_FLAT_BAND_BPM_PER_DAY = 0.20f
        private const val TSB_FLAT_BAND_PER_DAY = 0.50f
        private const val VO2_FLAT_BAND_PER_DAY = 0.10f
        private const val HRR1_FLAT_BAND_PER_DAY = 0.50f

        // TSB ≤ −25 = over-trained band (Coggan convention).
        private const val TSB_UNDERWATER_THRESHOLD = -25f
    }
}
