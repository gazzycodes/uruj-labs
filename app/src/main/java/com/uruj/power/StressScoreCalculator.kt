package com.uruj.power

/**
 * Derived "stress load" — a cortisol-axis PROXY combining the same biometric
 * inputs Readiness uses, weighted toward sympathetic-nervous-system markers.
 * Returns a 0-100 score where HIGHER = MORE STRESS (inverse of readiness).
 *
 * This is NOT a cortisol measurement. Cortisol is measured via blood/saliva. This
 * is a behavioral proxy: when HRV drops, RHR rises, sleep collapses, training
 * load piles up, and hard days stack — the autonomic profile resembles elevated
 * sympathetic/HPA activation. UI must label honestly ("≈ stress load, not
 * blood cortisol"). Per [[feedback_honest_estimates]] + [[feedback_lab_grade_honesty]].
 *
 * Weighting:
 *   HRV trend (today vs 7d baseline)   — 30%   (most direct autonomic marker)
 *   RHR delta (today vs 7d baseline)   — 20%
 *   Sleep deficit (vs 7-9h target)     — 20%
 *   TSB (Coggan training balance)      — 20%
 *   Consecutive hard days (load stack) — 10%
 *
 * Each component is normalized to 0-100 and contributes its weighted share.
 * Components with missing inputs are dropped and remaining weights are re-scaled,
 * so a partial signal still produces a number (with dataConfidence reflecting
 * what fraction of weight was real).
 */
class StressScoreCalculator {

    data class Inputs(
        /** Most recent observed HRV (RMSSD ms or proxy SD ms) — lower = more stress. */
        val hrvToday: Float? = null,
        val hrvBaseline7d: Float? = null,
        /** RHR today vs 7d median — higher today = more stress. */
        val rhrToday: Int? = null,
        val rhrBaseline7d: Int? = null,
        /** Hours slept last night. <6 starts piling on stress. */
        val sleepLastNightHours: Float? = null,
        /** Coggan TSB (CTL − ATL). More negative = more fatigue = more stress. */
        val trainingStressBalance: Float? = null,
        /** Number of consecutive recent days (ending today) with a hard ride logged. */
        val consecutiveHardDays: Int = 0,
    )

    data class Result(
        /** 0-100 stress load score. Higher = more cortisol-axis load. */
        val score: Int,
        val band: Band,
        val tagline: String,
        val components: List<Component>,
        /** 0.0-1.0 — what fraction of weight came from real (non-null) inputs. */
        val dataConfidence: Float,
    )

    data class Component(
        val label: String,
        /** 0-100 — this component's contribution score (HIGHER = more stress). */
        val score: Int?,
        val detail: String,
    )

    enum class Band(val label: String, val tagline: String) {
        Calm("CALM", "Autonomic profile looks relaxed — green light to push."),
        Moderate("MODERATE", "Normal day-to-day load — train as planned."),
        Elevated("ELEVATED", "Stress signals stacking — prioritize recovery today."),
        High("HIGH", "Cortisol-axis indicators are loud — rest, sleep, hydrate.");
    }

    fun compute(i: Inputs): Result {
        val components = mutableListOf<Pair<Component, Float>>() // component + weight
        var totalWeight = 0f
        var weightedSum = 0f

        // HRV — drop = stress. 30% weight when available.
        scoreHrv(i.hrvToday, i.hrvBaseline7d).let { (score, detail) ->
            val cmp = Component("HRV", score, detail)
            if (score != null) {
                weightedSum += score * 0.30f
                totalWeight += 0.30f
            }
            components += cmp to 0.30f
        }

        // RHR — elevation = stress. 20% weight.
        scoreRhr(i.rhrToday, i.rhrBaseline7d).let { (score, detail) ->
            val cmp = Component("RHR", score, detail)
            if (score != null) {
                weightedSum += score * 0.20f
                totalWeight += 0.20f
            }
            components += cmp to 0.20f
        }

        // Sleep — deficit = stress. 20% weight.
        scoreSleep(i.sleepLastNightHours).let { (score, detail) ->
            val cmp = Component("SLEEP", score, detail)
            if (score != null) {
                weightedSum += score * 0.20f
                totalWeight += 0.20f
            }
            components += cmp to 0.20f
        }

        // TSB — fatigue = stress. 20% weight.
        scoreTsb(i.trainingStressBalance).let { (score, detail) ->
            val cmp = Component("LOAD", score, detail)
            if (score != null) {
                weightedSum += score * 0.20f
                totalWeight += 0.20f
            }
            components += cmp to 0.20f
        }

        // Consecutive hard days — always derivable from history. 10% weight.
        scoreConsecutiveHard(i.consecutiveHardDays).let { (score, detail) ->
            val cmp = Component("STREAK", score, detail)
            weightedSum += score * 0.10f
            totalWeight += 0.10f
            components += cmp to 0.10f
        }

        val score = if (totalWeight > 0f) (weightedSum / totalWeight).toInt().coerceIn(0, 100) else 0
        val band = when {
            score >= 76 -> Band.High
            score >= 51 -> Band.Elevated
            score >= 26 -> Band.Moderate
            else -> Band.Calm
        }
        val confidence = (totalWeight / 1.0f).coerceIn(0f, 1f)

        return Result(
            score = score,
            band = band,
            tagline = band.tagline,
            components = components.map { it.first },
            dataConfidence = confidence,
        )
    }

    private fun scoreHrv(today: Float?, baseline: Float?): Pair<Int?, String> {
        if (today == null || baseline == null || baseline <= 0f) {
            return null to "no HRV data — wear band overnight"
        }
        val ratio = today / baseline
        // ratio 1.0 = at baseline (low stress). <1 = HRV drop = stress. Use the same
        // wider buckets that Readiness uses (proxy HRV has 20-25% natural night-over-
        // night variance per v0.3.2 retune).
        val score = when {
            ratio >= 1.10f -> 5    // HRV above baseline = recovered, low stress
            ratio >= 0.95f -> 18   // at baseline
            ratio >= 0.85f -> 38   // slight drop
            ratio >= 0.75f -> 60   // notable drop
            ratio >= 0.65f -> 80   // severe drop
            else -> 92             // crash
        }
        val pct = ((ratio - 1f) * 100f).toInt()
        val sign = if (pct >= 0) "+" else ""
        return score to "$sign$pct% vs 7d baseline"
    }

    private fun scoreRhr(today: Int?, baseline: Int?): Pair<Int?, String> {
        if (today == null || baseline == null || baseline <= 0) {
            return null to "no RHR data"
        }
        val delta = today - baseline
        // +5 bpm vs baseline is a classic recovery-deficit / illness-onset marker.
        val score = when {
            delta <= -3 -> 5    // RHR below baseline = strong recovery
            delta <= 1 -> 18    // at baseline
            delta <= 4 -> 40    // slight elevation
            delta <= 7 -> 65    // moderate elevation
            delta <= 10 -> 82   // strong elevation
            else -> 92          // very high
        }
        val sign = if (delta >= 0) "+" else ""
        return score to "$sign$delta bpm vs 7d baseline"
    }

    private fun scoreSleep(hours: Float?): Pair<Int?, String> {
        if (hours == null) return null to "no sleep data"
        val score = when {
            hours >= 8f -> 8       // optimal
            hours >= 7f -> 20      // adequate
            hours >= 6f -> 45      // mild deficit
            hours >= 5f -> 68      // notable deficit
            hours >= 4f -> 82      // severe
            else -> 92             // crash
        }
        return score to "%.1fh last night".format(hours)
    }

    private fun scoreTsb(tsb: Float?): Pair<Int?, String> {
        if (tsb == null) return null to "need 2+ rides for load balance"
        // Coggan thresholds: TSB > +5 = fresh, -10 to +5 = neutral, -30 to -10 =
        // productive fatigue, < -30 = overreaching. Map to stress score inversely.
        val score = when {
            tsb >= 5f -> 10        // fresh
            tsb >= -5f -> 25       // neutral
            tsb >= -15f -> 50      // building fitness
            tsb >= -25f -> 70      // significant fatigue
            tsb >= -35f -> 85      // overreached
            else -> 95             // grave overreach
        }
        return score to "TSB ${tsb.toInt()} (CTL − ATL)"
    }

    private fun scoreConsecutiveHard(days: Int): Pair<Int, String> {
        val score = when {
            days <= 1 -> 10
            days == 2 -> 30
            days == 3 -> 55
            days == 4 -> 75
            else -> 90
        }
        val detail = when (days) {
            0 -> "no hard days in a row"
            1 -> "1 hard day today"
            else -> "$days hard days in a row"
        }
        return score to detail
    }
}
