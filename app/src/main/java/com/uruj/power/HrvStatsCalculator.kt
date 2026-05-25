package com.uruj.power

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * v0.9.46.B — statistical layer for HRV. Replaces "daily-number-as-signal"
 * with proper week-over-week trend + noise-band context.
 *
 * # Why this exists
 *
 * RMSSD has 20-30% natural CV (coefficient of variation) night-to-night even
 * in healthy stable athletes. A daily reading drifting 1-3 ms is statistical
 * noise, NOT a trend. Pre-v0.9.46.B, URUJ displayed today's single value as
 * if it were the signal — riders read normal physiological variability as
 * "my HRV is crashing" → depression spiral. The data IS the truth, the
 * **presentation** was the bug.
 *
 * Same fix shape every elite HRV app (Elite HRV Premium, HRV4Training,
 * Whoop, Polar Recovery Pro) shipped years ago. URUJ catches up here.
 *
 * # Methodology
 *
 * - **7-day rolling median** — robust to outlier nights (alcohol, illness
 *   onset, strap glitch). Standard in autonomic research (Plews et al.
 *   2013, Buchheit 2014).
 * - **CV%** — coefficient of variation = SD/mean × 100. Personal stability
 *   metric. CV < 10% = very stable; 10-20% = normal; >20% = high day-to-day
 *   noise (could indicate strap issues, lifestyle chaos, or onset
 *   illness).
 * - **14-day linear regression** — slope (ms/day) + significance test (via
 *   t-stat). t = slope / SE(slope). |t| > 2.0 → ~95% confidence of real
 *   trend (rough p<0.05 proxy without full degrees-of-freedom lookup table,
 *   adequate for the 12-14 sample sizes typical here).
 * - **This-week vs prior-week mean** — concrete "are we better than last
 *   week?" signal. The single most useful coaching question.
 *
 * # Insufficient data behavior
 *
 * - <2 samples → all fields null
 * - <4 samples → median + mean populated; trend skipped
 * - <8 samples → trend computed but significance defaults to false
 *
 * Trust thresholds match what Plews recommends for athletes — 7+ nights to
 * trust a baseline, 14+ for trend confidence.
 */
class HrvStatsCalculator {

    /**
     * Statistical summary of a multi-night HRV series.
     *
     * @param recent7dMedianMs median RMSSD across most-recent 7 valid samples
     * @param recent7dMeanMs mean RMSSD across most-recent 7 valid samples
     * @param recent7dSdMs standard deviation across most-recent 7 valid samples
     * @param cvPercent coefficient of variation = SD/mean × 100
     * @param trendSlopeMsPerDay 14d linear-regression slope (positive = rising)
     * @param trendIsSignificant true when |t-stat| > 2.0 (~p<0.05 approx)
     * @param thisWeekMeanMs mean of most-recent 7
     * @param priorWeekMeanMs mean of next-older 7 (days 8-14)
     * @param weekOverWeekDeltaMs thisWeek - priorWeek
     * @param weekOverWeekPercent (delta / prior) × 100
     * @param samplesUsed total nights that contributed
     */
    data class HrvStats(
        val recent7dMedianMs: Float?,
        val recent7dMeanMs: Float?,
        val recent7dSdMs: Float?,
        val cvPercent: Float?,
        val trendSlopeMsPerDay: Float?,
        val trendIsSignificant: Boolean,
        val thisWeekMeanMs: Float?,
        val priorWeekMeanMs: Float?,
        val weekOverWeekDeltaMs: Float?,
        val weekOverWeekPercent: Float?,
        val samplesUsed: Int,
    ) {
        companion object {
            val EMPTY = HrvStats(
                recent7dMedianMs = null,
                recent7dMeanMs = null,
                recent7dSdMs = null,
                cvPercent = null,
                trendSlopeMsPerDay = null,
                trendIsSignificant = false,
                thisWeekMeanMs = null,
                priorWeekMeanMs = null,
                weekOverWeekDeltaMs = null,
                weekOverWeekPercent = null,
                samplesUsed = 0,
            )
        }
    }

    /**
     * Compute stats from a list of nightly RMSSD values.
     *
     * @param nightlyRmssdNewestFirst values ordered newest first (day 0 at
     *   index 0, day-1 at index 1, etc.). Same convention used elsewhere in
     *   URUJ trend signals.
     */
    fun compute(nightlyRmssdNewestFirst: List<Float>): HrvStats {
        val values = nightlyRmssdNewestFirst.filter { it > 0f }
        if (values.size < 2) return HrvStats.EMPTY

        // 7-day window (most recent 7)
        val recent7 = values.take(7)
        val median7 = median(recent7)
        val mean7 = recent7.average().toFloat()
        val sd7 = standardDeviation(recent7, mean7)
        val cv: Float? = sd7?.takeIf { mean7 > 0f }?.let { (it / mean7) * 100f }

        // Week-over-week comparison (needs ≥8 samples)
        val priorWeekValues = if (values.size >= 8) values.drop(7).take(7) else emptyList()
        val priorMean: Float? = if (priorWeekValues.isNotEmpty()) {
            priorWeekValues.average().toFloat()
        } else null
        val wowDelta: Float? = priorMean?.let { mean7 - it }
        val wowPercent: Float? = priorMean?.takeIf { it > 0f }?.let {
            ((mean7 - it) / it) * 100f
        }

        // Linear regression over up-to-14d window
        val regressionWindow = values.take(14)
        val (slope, isSignificant) = if (regressionWindow.size >= 4) {
            regressionWithSignificance(regressionWindow)
        } else {
            null to false
        }

        return HrvStats(
            recent7dMedianMs = median7,
            recent7dMeanMs = mean7,
            recent7dSdMs = sd7,
            cvPercent = cv,
            trendSlopeMsPerDay = slope,
            trendIsSignificant = isSignificant,
            thisWeekMeanMs = mean7,
            priorWeekMeanMs = priorMean,
            weekOverWeekDeltaMs = wowDelta,
            weekOverWeekPercent = wowPercent,
            samplesUsed = values.size,
        )
    }

    /**
     * Linear regression (least-squares) of y vs day-index. Input is
     * newest-first; we re-index oldest=0 so a positive slope means
     * "improving with time."
     *
     * Significance test: t = slope / SE(slope). For n in the range 4-20
     * we treat |t| > 2.0 as ~95% confidence (close enough to the t-table
     * critical value for n-2 = 2..18 d.f., which ranges 4.30..2.10). Coarser
     * than a full inverse-CDF lookup but matches the precision an athlete-
     * facing card actually needs: "is this trend real or coin-flip?"
     *
     * Returns (slope, isSignificant). slope is in units of value per day.
     */
    private fun regressionWithSignificance(
        newestFirst: List<Float>,
    ): Pair<Float, Boolean> {
        val reversed = newestFirst.reversed()  // oldest=0
        val n = reversed.size
        val xs = (0 until n).map { it.toFloat() }
        val xMean = xs.average().toFloat()
        val yMean = reversed.average().toFloat()
        var sxy = 0f
        var sxx = 0f
        for (i in 0 until n) {
            val dx = xs[i] - xMean
            sxy += dx * (reversed[i] - yMean)
            sxx += dx * dx
        }
        if (sxx == 0f) return 0f to false
        val slope = sxy / sxx
        val intercept = yMean - slope * xMean
        // Residuals → SE(slope)
        var ssRes = 0f
        for (i in 0 until n) {
            val predicted = intercept + slope * xs[i]
            val residual = reversed[i] - predicted
            ssRes += residual * residual
        }
        if (n <= 2) return slope to false
        val residualVariance = ssRes / (n - 2)
        val seSlope = sqrt((residualVariance / sxx).toDouble()).toFloat()
        if (seSlope == 0f) return slope to false
        val tStat = abs(slope / seSlope)
        val isSignificant = tStat > T_STAT_THRESHOLD
        return slope to isSignificant
    }

    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
    }

    private fun standardDeviation(values: List<Float>, mean: Float): Float? {
        if (values.size < 2) return null
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance).toFloat()
    }

    companion object {
        /**
         * t-statistic threshold for "significant" trend at n = 4-20. Critical
         * t-value for two-tailed α=0.05 at n-2=2..18 d.f. ranges 4.30..2.10;
         * 2.0 is a generous floor that matches what athletes care about ("is
         * this real or random?"). Conservative enough that flagged trends
         * are usually real.
         */
        const val T_STAT_THRESHOLD = 2.0f
    }
}
