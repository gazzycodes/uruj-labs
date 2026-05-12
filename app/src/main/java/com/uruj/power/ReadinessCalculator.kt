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
 */
class ReadinessCalculator {

    fun compute(inputs: ReadinessInputs): ReadinessResult {
        val components = mutableListOf<ReadinessComponent>()
        var weightedSum = 0f
        var totalWeight = 0f

        scoreSleep(inputs.sleepLastNightHours)?.let { (score, detail) ->
            components += ReadinessComponent("Sleep", score, detail)
            weightedSum += score * 0.35f
            totalWeight += 0.35f
        } ?: components.add(
            ReadinessComponent("Sleep", null, "wear band overnight"),
        )

        scoreHrv(inputs.hrvTodayRmssd, inputs.hrvBaseline7d)?.let { (score, detail) ->
            components += ReadinessComponent("HRV", score, detail)
            weightedSum += score * 0.30f
            totalWeight += 0.30f
        } ?: components.add(
            ReadinessComponent("HRV", null, "needs 7 days of data"),
        )

        scoreRestingHr(inputs.restingHrToday, inputs.restingHrBaseline7d)?.let { (score, detail) ->
            components += ReadinessComponent("Resting HR", score, detail)
            weightedSum += score * 0.15f
            totalWeight += 0.15f
        } ?: components.add(
            ReadinessComponent("Resting HR", null, "needs 7 days of data"),
        )

        scoreTsb(inputs.trainingStressBalance)?.let { (score, detail) ->
            components += ReadinessComponent("Training load", score, detail)
            weightedSum += score * 0.20f
            totalWeight += 0.20f
        } ?: components.add(
            ReadinessComponent("Training load", null, "ride more to build CTL"),
        )

        if (totalWeight < 0.001f) {
            return ReadinessResult(
                score = 0,
                grade = ReadinessGrade.Unknown,
                components = components,
                recommendation = "Wear a band overnight + ride a few times to unlock readiness scoring.",
                dataConfidence = 0f,
            )
        }

        // dataConfidence = how much of the full 4-input weight is actually available.
        // 1.0 = full data, 0.2 = only training load (one of four), etc.
        val dataConfidence = totalWeight  // weights sum to 1.0 when all four present
        val score = (weightedSum / totalWeight).roundToInt().coerceIn(0, 100)

        // Honesty gate: if less than half the inputs are real, don't make a strong
        // recommendation. Score is shown but flagged as low-confidence. Prevents
        // "GO HARD" from one data point misleading the rider.
        val grade = when {
            dataConfidence < 0.5f -> ReadinessGrade.LimitedData
            score >= 80 -> ReadinessGrade.GoHard
            score >= 60 -> ReadinessGrade.Moderate
            score >= 40 -> ReadinessGrade.Easy
            else -> ReadinessGrade.Rest
        }
        val recommendation = if (dataConfidence < 0.5f) {
            buildLimitedDataRecommendation(score, components, dataConfidence)
        } else {
            buildRecommendation(score, components)
        }

        return ReadinessResult(score, grade, components, recommendation, dataConfidence)
    }

    private fun buildLimitedDataRecommendation(
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

    private fun scoreHrv(today: Float?, baseline: Float?): Pair<Int, String>? {
        if (today == null || baseline == null || baseline < 1f) return null
        val ratio = today / baseline
        val score = when {
            ratio > 1.10f -> 100  // unusually high — exceptional recovery
            ratio > 1.00f -> 95
            ratio > 0.95f -> 85
            ratio > 0.90f -> 75
            ratio > 0.85f -> 60
            ratio > 0.80f -> 45
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
        val score = when {
            tsb > 10f -> 95     // very fresh, but possibly detraining
            tsb in 5f..10f -> 100
            tsb in -5f..5f -> 90
            tsb in -10f..-5f -> 75
            tsb in -25f..-10f -> 55
            tsb in -40f..-25f -> 30
            else -> 15
        }
        val detail = when {
            tsb > 5f -> "fresh (TSB +${tsb.roundToInt()})"
            abs(tsb) <= 5f -> "balanced (TSB ${tsb.roundToInt()})"
            tsb > -25f -> "fatigued (TSB ${tsb.roundToInt()})"
            else -> "over-trained (TSB ${tsb.roundToInt()})"
        }
        return score to detail
    }

    private fun buildRecommendation(score: Int, components: List<ReadinessComponent>): String {
        val lowestComponent = components
            .filter { it.score != null }
            .minByOrNull { it.score ?: 100 }
        return when {
            score >= 80 ->
                "All systems green — push hard. Threshold or VO2 session if planned."
            score >= 60 -> {
                val laggard = lowestComponent?.label?.lowercase() ?: "recovery"
                "Solid baseline, $laggard slightly off — moderate aerobic effort is the play."
            }
            score >= 40 ->
                "Easy spin / zone 2 only. Body is recovering; don't dig the hole deeper."
            score > 0 ->
                "Rest day. Walk, stretch, hydrate. Trying to train through this costs more than it earns."
            else ->
                "Wear a band overnight + ride a few times to unlock readiness scoring."
        }
    }
}
