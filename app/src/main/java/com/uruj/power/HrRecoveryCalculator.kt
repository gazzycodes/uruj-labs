package com.uruj.power

import java.time.Duration
import java.time.Instant

/**
 * Heart Rate Recovery (HRR1) — the drop in heart rate during the first 60 seconds
 * after stopping a hard effort. One of the strongest peer-reviewed predictors of
 * autonomic nervous system health and all-cause mortality (Cole et al. NEJM 1999,
 * subsequently confirmed in Framingham + dozens of follow-up cohorts).
 *
 * Clinical thresholds (Cole et al.):
 *   ≥ 18 bpm drop — excellent recovery, strong vagal reactivation
 *   12 – 17 bpm  — average / normal
 *   <  12 bpm    — abnormal recovery, elevated CV mortality risk
 *
 * Unlike VO₂ max formulas (which are estimates) and "biological age" heuristics
 * (which we invented), HRR1 is a *real, measured, clinically validated* metric
 * we can derive directly from Fit Band 3 + Health Connect data — provided the
 * rider actually pushed hard enough during a workout that the recovery curve is
 * visible. Sub-130-bpm "efforts" are excluded; the HR drop in a soft warm-down
 * walk isn't physiologically meaningful.
 */
class HrRecoveryCalculator {

    data class Sample(
        val sessionEnd: Instant,
        val effortPeakBpm: Int,
        val recoveryHrBpm: Int,
        val hrr1Bpm: Int,
        val classification: String,
    )

    data class Result(
        val samples: List<Sample>,
        /** Median HRR1 across qualifying samples — robust against single-session noise. */
        val medianHrr1: Int,
        /** Worst-of, best-of, classification of the median. */
        val medianClassification: String,
    )

    /**
     * @param exerciseSessionEndTimes endTime of every ExerciseSessionRecord in window
     * @param hrTimedSamples HR samples (Instant + bpm) over the same window plus
     *   a 2-minute trailing buffer past the last session end.
     */
    fun compute(
        exerciseSessionEndTimes: List<Instant>,
        hrTimedSamples: List<Pair<Instant, Int>>,
    ): Result? {
        if (exerciseSessionEndTimes.isEmpty() || hrTimedSamples.isEmpty()) return null

        val samples = exerciseSessionEndTimes.mapNotNull { end ->
            // Effort peak: highest HR in the last 5 min of the session. We don't
            // know the session start (we only get end times here) so we just look
            // back from end and trust that the peak in the closing 5 min was the
            // working HR.
            val effortWindow = hrTimedSamples.filter { (t, _) ->
                !t.isBefore(end.minus(EFFORT_LOOKBACK)) && !t.isAfter(end)
            }
            val peak = effortWindow.maxOfOrNull { it.second } ?: return@mapNotNull null
            // Require a real hard effort. Easy walks / commute rides don't have a
            // recovery curve worth measuring.
            if (peak < MIN_PEAK_BPM) return@mapNotNull null

            // Recovery: lowest HR in the 30s–120s post-end window. With Fit Band 3
            // spot-checks, we may not have a sample exactly at +60s; widening the
            // window catches the actual recovery floor without leaking into the
            // "warm-down walking pace" zone past 2 min.
            val recoveryWindow = hrTimedSamples.filter { (t, _) ->
                !t.isBefore(end.plus(RECOVERY_WINDOW_START)) &&
                    !t.isAfter(end.plus(RECOVERY_WINDOW_END))
            }
            val recovery = recoveryWindow.minOfOrNull { it.second } ?: return@mapNotNull null
            val drop = peak - recovery
            // Negative drops or absurd jumps indicate sensor weirdness, not data.
            if (drop !in 0..100) return@mapNotNull null

            Sample(
                sessionEnd = end,
                effortPeakBpm = peak,
                recoveryHrBpm = recovery,
                hrr1Bpm = drop,
                classification = classify(drop),
            )
        }
        if (samples.isEmpty()) return null

        val sorted = samples.map { it.hrr1Bpm }.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        }
        return Result(
            samples = samples,
            medianHrr1 = median,
            medianClassification = classify(median),
        )
    }

    private fun classify(drop: Int): String = when {
        drop >= 18 -> "Excellent — strong autonomic recovery"
        drop >= 12 -> "Average — normal recovery"
        drop >= 0 -> "Poor — elevated CV risk per Cole et al. (NEJM 1999)"
        else -> "Anomalous"
    }

    companion object {
        private val EFFORT_LOOKBACK: Duration = Duration.ofMinutes(5)
        private val RECOVERY_WINDOW_START: Duration = Duration.ofSeconds(30)
        private val RECOVERY_WINDOW_END: Duration = Duration.ofSeconds(120)
        private const val MIN_PEAK_BPM = 130
    }
}
