package com.uruj.power

import com.uruj.domain.ReadinessComponent
import com.uruj.domain.ReadinessGrade
import com.uruj.domain.ReadinessInputs
import com.uruj.domain.ReadinessResult
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Compute a 0–100 readiness score from optional inputs. Mirrors the methodology
 * used by Whoop / Oura / Garmin Body Battery: weighted blend of overnight
 * recovery markers + recent training stress balance.
 *
 *   Weights: sleep 35% + HRV 30% + RHR 15% + TSB 20%
 *
 * Missing inputs degrade the score gracefully — when nothing is available, returns
 * a "Connect a wearable" grade rather than crashing or showing a fake number.
 *
 * v0.9.4 — recommendation generation moved out to [ReadinessReasoner]
 * (interface) + [RuleBasedReasoner] (default impl). This calculator now
 * owns SCORING only; the reasoner owns the verbose recommendation block
 * (headline + duration + rationale + insights). The orchestration lives
 * in [com.uruj.data.ReadinessRepository] which assembles inputs + context,
 * scores via this calculator, then asks the reasoner for the recommendation.
 * The AI hook (Task #105 / v0.5) plugs in at the reasoner seam — see
 * [[reference_readiness_context_architecture]].
 */
class ReadinessCalculator {

    /**
     * v0.9.4 — pure scoring path. Produces components + composite score +
     * grade + dataConfidence WITHOUT building the recommendation block.
     * Caller (ReadinessRepository) wraps with the reasoner output to form
     * the final [ReadinessResult].
     */
    data class Scored(
        val score: Int,
        val grade: ReadinessGrade,
        val components: List<ReadinessComponent>,
        val dataConfidence: Float,
    )

    fun score(inputs: ReadinessInputs): Scored {
        val components = mutableListOf<ReadinessComponent>()
        var weightedSum = 0f
        var totalWeight = 0f

        scoreSleep(inputs.sleepLastNightHours)?.let { (s, detail) ->
            components += ReadinessComponent("Sleep", s, detail)
            weightedSum += s * 0.35f
            totalWeight += 0.35f
        } ?: components.add(ReadinessComponent("Sleep", null, "wear band overnight"))

        scoreHrv(
            today = inputs.hrvTodayRmssd,
            baseline = inputs.hrvBaseline7d,
            daysOfData = inputs.hrvDaysOfDataIn7d,
        )?.let { (s, detail) ->
            components += ReadinessComponent("HRV", s, detail)
            weightedSum += s * 0.30f
            totalWeight += 0.30f
        } ?: components.add(
            ReadinessComponent("HRV", null, "enable 24/7 monitoring + wear strap"),
        )

        scoreRestingHr(inputs.restingHrToday, inputs.restingHrBaseline7d)?.let { (s, detail) ->
            components += ReadinessComponent("Resting HR", s, detail)
            weightedSum += s * 0.15f
            totalWeight += 0.15f
        } ?: components.add(ReadinessComponent("Resting HR", null, "needs sleep + HR data"))

        scoreTsb(inputs.trainingStressBalance)?.let { (s, detail) ->
            components += ReadinessComponent("Training load", s, detail)
            weightedSum += s * 0.20f
            totalWeight += 0.20f
        } ?: components.add(ReadinessComponent("Training load", null, "ride more to build CTL"))

        if (totalWeight < 0.001f) {
            return Scored(
                score = 0,
                grade = ReadinessGrade.Unknown,
                components = components,
                dataConfidence = 0f,
            )
        }
        val dataConfidence = totalWeight
        val score = (weightedSum / totalWeight).roundToInt().coerceIn(0, 100)
        val grade = when {
            dataConfidence < 0.5f -> ReadinessGrade.LimitedData
            score >= 80 -> ReadinessGrade.GoHard
            score >= 60 -> ReadinessGrade.Moderate
            score >= 40 -> ReadinessGrade.Easy
            else -> ReadinessGrade.Rest
        }
        return Scored(score, grade, components, dataConfidence)
    }

    /**
     * Legacy entry — pre-v0.9.4 path. Kept for backward compatibility with
     * call sites that don't yet have a [com.uruj.domain.ReadinessContext]
     * available. Produces a [ReadinessResult] with the simple limited-data
     * recommendation; the v0.9.4 reasoner output (insights, missing-signals
     * callout) will not be populated. ReadinessRepository uses the richer
     * path; this exists for tests + future incidental callers.
     */
    fun compute(inputs: ReadinessInputs): ReadinessResult {
        val s = score(inputs)
        if (s.grade == ReadinessGrade.Unknown) {
            return ReadinessResult(
                score = 0,
                grade = ReadinessGrade.Unknown,
                components = s.components,
                recommendation = "Wear a band overnight + ride a few times to unlock readiness scoring.",
                dataConfidence = 0f,
            )
        }
        val rec = buildLimitedDataRecommendation(s.score, s.components, s.dataConfidence)
        return ReadinessResult(
            score = s.score,
            grade = s.grade,
            components = s.components,
            recommendation = rec,
            dataConfidence = s.dataConfidence,
        )
    }

    fun buildLimitedDataRecommendation(
        score: Int,
        components: List<ReadinessComponent>,
        confidence: Float,
    ): String {
        val pct = (confidence * 100).roundToInt()
        val missing = components.filter { it.score == null }.map { it.label.lowercase() }
        val missingLabel = when (missing.size) {
            0 -> ""
            1 -> missing[0]
            else -> missing.dropLast(1).joinToString(", ") + " + " + missing.last()
        }
        return "$pct% data only — score is based on what's available. Wear band overnight to unlock $missingLabel."
    }

    private fun scoreSleep(hours: Float?): Pair<Int, String>? {
        if (hours == null) return null
        // Optimal sleep window 7-9h. Above 10h or below 6h gets penalized.
        val score = when {
            hours < 4f -> 20
            hours < 5f -> 40
            hours < 6f -> 60
            hours in 6f..7f -> 80
            hours in 7f..9f -> 100
            hours in 9f..10f -> 90
            else -> 70
        }
        val hStr = "%.1fh".format(hours)
        return score to hStr
    }

    /**
     * v0.7.0 follow-up — two-mode HRV scoring.
     *
     * Days 1-6 ("baseline building"): use ABSOLUTE-tier scoring. A real
     * 60 ms RMSSD scores well even without history; a real 20 ms scores
     * poorly even on day 1. Fixes the bug where day-1 baseline = today
     * produced a meaningless "+0% vs 7d avg" → score 90.
     *
     * Day 7+ (stable baseline): use ratio-based scoring vs personal 7d
     * median. Ratio captures "today vs MY normal," catches subtle changes
     * (overtraining, illness onset) better than absolute thresholds.
     *
     * Tier thresholds from Plews et al. elite cyclist RMSSD norms +
     * general adult HRV research (Shaffer & Ginsberg 2017):
     *   80+ ms = elite parasympathetic dominance
     *   50-80 ms = trained athlete range
     *   30-50 ms = average healthy adult
     *   20-30 ms = below athletic average
     *   <20 ms = below athletic average — check trend
     *
     * IMPORTANT: these thresholds reference NATURAL-BREATHING overnight RMSSD.
     * Paced-breathing morning readings (Elite HRV, HRV4Training) typically run
     * 1.5-3× higher for the same person because forced ~5 breaths/min maximizes
     * RSA amplitude. Don't cross-reference URUJ's number against a paced-breathing
     * benchmark without that scaling. See methodology footer in Bio Lab.
     */
    private fun scoreHrv(today: Float?, baseline: Float?, daysOfData: Int): Pair<Int, String>? {
        if (today == null) return null

        // Days 1-6: absolute-tier scoring (no real baseline yet)
        if (daysOfData < 7 || baseline == null || baseline < 1f) {
            val absScore = when {
                today >= 80f -> 100
                today >= 50f -> 90
                today >= 30f -> 75
                today >= 20f -> 50
                else -> 25
            }
            val detail = when (daysOfData) {
                0, 1 -> "${"%.0f".format(today)} ms · first reading"
                else -> "${"%.0f".format(today)} ms · baseline building ($daysOfData/7 nights)"
            }
            return absScore to detail
        }

        // Day 7+: ratio vs personal 7d median baseline
        val ratio = today / baseline
        val score = when {
            ratio > 1.10f -> 100  // unusually high — exceptional recovery
            ratio > 1.00f -> 95
            ratio > 0.90f -> 90
            ratio > 0.80f -> 75
            ratio > 0.70f -> 55
            ratio > 0.60f -> 35
            else -> 25
        }
        val pct = ((ratio - 1f) * 100).roundToInt()
        val pctStr = if (pct >= 0) "+$pct%" else "$pct%"
        return score to "$pctStr vs 7d avg"
    }

    private fun scoreRestingHr(today: Int?, baseline: Int?): Pair<Int, String>? {
        if (today == null || baseline == null || baseline < 1) return null
        // Lower RHR = better. Compare today vs 7-day baseline.
        val delta = today - baseline
        val score = when {
            delta <= -3 -> 100
            delta <= 0 -> 90
            delta <= 2 -> 75
            delta <= 5 -> 50
            delta <= 8 -> 30
            else -> 15
        }
        val deltaStr = if (delta >= 0) "+$delta vs 7d avg" else "$delta vs 7d avg"
        return score to deltaStr
    }

    private fun scoreTsb(tsb: Float?): Pair<Int, String>? {
        if (tsb == null) return null
        // v0.4.1: split the wide -25..-10 bucket. TSB -10 to -15 is "productive
        // fatigue" (where adaptation happens — pros live here mid-block); only
        // below -15 does it cross into the genuinely-fatigued zone.
        val score = when {
            tsb > 10f -> 95     // very fresh, but possibly detraining
            tsb in 5f..10f -> 100
            tsb in -5f..5f -> 90
            tsb in -10f..-5f -> 75
            tsb in -15f..-10f -> 65       // productive fatigue (NEW)
            tsb in -25f..-15f -> 50       // significant fatigue
            tsb in -40f..-25f -> 30
            else -> 15
        }
        val detail = when {
            tsb > 5f -> "fresh (TSB +${tsb.roundToInt()})"
            abs(tsb) <= 5f -> "balanced (TSB ${tsb.roundToInt()})"
            tsb > -15f -> "productive (TSB ${tsb.roundToInt()})"
            tsb > -25f -> "fatigued (TSB ${tsb.roundToInt()})"
            else -> "over-trained (TSB ${tsb.roundToInt()})"
        }
        return score to detail
    }

    // v0.9.3 — buildRecommendation removed. Replaced by ReadinessRecommendationEngine
    // which reads raw inputs (not just composite score) for multi-signal tiering,
    // duration capping, and rotating taglines.
}
