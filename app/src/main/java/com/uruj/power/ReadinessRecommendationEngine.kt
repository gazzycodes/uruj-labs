package com.uruj.power

import com.uruj.domain.ReadinessComponent
import com.uruj.domain.ReadinessInputs
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * v0.9.3 — multi-signal recommendation builder. Replaces v0.4.x's single-bucket
 * text function in [ReadinessCalculator] with a richer engine that reads the raw
 * inputs (not just the composite score), enabling:
 *
 *  1. **FULL REST tier** — when 2+ severe flags fire (overtrained + sleep crash +
 *     HRV crash), the engine over-rides bucket text. Previously a 41/100 EASY
 *     score generated "Easy spin / Zone 2 only..." for 60+ min, which still loads
 *     CTL when TSB is −30. New engine sends "Rest day" with explicit rationale.
 *  2. **Duration cap** — most apps say "Zone 2 only" with no time. Endurance
 *     riders interpret that as 60–120 min. Engine now caps minutes per tier so
 *     "Z2" doesn't mask further fatigue accumulation on a recovery day.
 *  3. **Rotating taglines** — 4 variants per tier, keyed off [LocalDate.dayOfYear]
 *     for deterministic daily rotation. Same input on consecutive days produces
 *     different copy. Avoids the "Body is recovering; don't dig the hole deeper"
 *     fatigue from seeing the same line for a week.
 *  4. **Rationale string** — surfaces WHICH signals are dragging the score in
 *     plain language (TSB −30 + 4.7h sleep + HRV 9 ms). The rider learns the
 *     mechanism, not just the verdict.
 *
 * Future work: Task #105 (v0.5 Groq AI narrative coach) layers a free-form
 * sentence on top. This rule-based engine remains the deterministic fallback
 * so the rider always gets ≤ 2 ms locally-computed guidance even if AI is
 * down / declined consent.
 *
 * @see com.uruj.power.ReadinessCalculator.compute
 */
object ReadinessRecommendationEngine {

    data class Recommendation(
        /** Headline call: "Rest day" / "Active recovery only" / etc. */
        val headline: String,
        /**
         * Duration cap or qualifier: "walk + hydrate, that's it" / "Z1 spin <30 min".
         * Nullable when the headline already implies duration (e.g. "Rest day").
         */
        val duration: String?,
        /**
         * Why-line. Surfaces the dominant drivers in plain language so the rider
         * learns the mechanism behind the recommendation.
         */
        val rationale: String,
    )

    fun build(
        score: Int,
        components: List<ReadinessComponent>,
        inputs: ReadinessInputs,
        today: LocalDate = LocalDate.now(),
    ): Recommendation {
        val tsb = inputs.trainingStressBalance
        val sleep = inputs.sleepLastNightHours
        val hrvRatio = if (
            inputs.hrvBaseline7d != null &&
            inputs.hrvBaseline7d > 0f &&
            inputs.hrvTodayRmssd != null &&
            inputs.hrvDaysOfDataIn7d >= 7
        ) inputs.hrvTodayRmssd / inputs.hrvBaseline7d else null
        val hrvAbsolute = inputs.hrvTodayRmssd
        val rhrDelta = if (
            inputs.restingHrToday != null && inputs.restingHrBaseline7d != null
        ) inputs.restingHrToday - inputs.restingHrBaseline7d else null

        // Severe = trip the over-reach / recovery-mandated wire.
        // Mild = caution, not block.
        val tsbCrashed = tsb != null && tsb <= -25f
        val tsbDeep = tsb != null && tsb <= -15f && !tsbCrashed
        val sleepCrashed = sleep != null && sleep < 5f
        val sleepLow = sleep != null && sleep < 6f && !sleepCrashed
        val hrvCrashed = (hrvRatio != null && hrvRatio < 0.70f) ||
            (hrvRatio == null && hrvAbsolute != null && hrvAbsolute < 12f)
        val hrvLow = !hrvCrashed && (
            (hrvRatio != null && hrvRatio < 0.85f) ||
            (hrvRatio == null && hrvAbsolute != null && hrvAbsolute < 18f)
        )
        val rhrElevated = rhrDelta != null && rhrDelta >= 5
        val rhrSlightlyUp = !rhrElevated && rhrDelta != null && rhrDelta >= 3

        val severeFlags = listOf(tsbCrashed, sleepCrashed, hrvCrashed, rhrElevated).count { it }
        val mildFlags = listOf(tsbDeep, sleepLow, hrvLow, rhrSlightlyUp).count { it }

        // Multi-signal tier selection — over-rides the score bucket when needed.
        // The score is one input; raw signals dominate when they're concerning.
        val tier = when {
            severeFlags >= 2 -> Tier.FullRest
            score < 30 -> Tier.FullRest
            severeFlags == 1 && score < 55 -> Tier.ActiveRecovery
            score < 45 -> Tier.ActiveRecovery
            mildFlags >= 2 && score < 65 -> Tier.EasyAerobic
            score < 60 -> Tier.EasyAerobic
            score < 75 -> Tier.ModerateEndurance
            else -> Tier.HardGreenLight
        }

        val (headline, duration) = pickHeadline(tier, today.dayOfYear)
        val rationale = buildRationale(
            tier, tsb, sleep, hrvRatio, hrvAbsolute, rhrDelta, components,
        )
        return Recommendation(headline, duration, rationale)
    }

    private enum class Tier { FullRest, ActiveRecovery, EasyAerobic, ModerateEndurance, HardGreenLight }

    /**
     * Headline pool per tier. Keyed off day-of-year so the same inputs produce
     * different copy on consecutive days but the same copy across multiple opens
     * on the same day (deterministic — easy to test, no random drift).
     */
    private fun pickHeadline(tier: Tier, dayKey: Int): Pair<String, String?> {
        val pool: List<Pair<String, String?>> = when (tier) {
            Tier.FullRest -> listOf(
                "Rest day" to "walk + hydrate, that's it",
                "Full stand-down" to "no ride — body is recovering",
                "Skip today" to "pause is the workout",
                "Hold the line" to "let adaptation catch up; train tomorrow",
            )
            Tier.ActiveRecovery -> listOf(
                "Active recovery only" to "Z1 spin ≤ 30 min, < 134 bpm",
                "Zone 1 spin" to "20–30 min cap, conversation pace",
                "Recovery ride or rest" to "≤ 30 min Z1, or skip entirely",
                "Spin out the legs" to "20 min Z1, easy gears only",
            )
            Tier.EasyAerobic -> listOf(
                "Easy Z2 endurance" to "45–60 min cap, don't push",
                "Aerobic only" to "Z2, 60 min, conversational",
                "Steady Z2" to "45–75 min, keep it boring",
                "Endurance pace" to "Z2 60 min, no tempo today",
            )
            Tier.ModerateEndurance -> listOf(
                "Moderate aerobic" to "Z2–Z3, 60–90 min",
                "Solid base session" to "60–120 min Z2, optional Z3 blocks",
                "Productive endurance" to "Z2 base + tempo if you feel it",
                "Good training day" to "60–90 min Z2, controlled Z3 OK",
            )
            Tier.HardGreenLight -> listOf(
                "Green light — go hard" to "threshold or VO2 session",
                "All systems primed" to "Z4 intervals or VO2 max work",
                "Hard day cleared" to "threshold, sweet-spot, or VO2 — pick one",
                "Race the past you" to "intervals, threshold, or sustained tempo",
            )
        }
        return pool[((dayKey % pool.size) + pool.size) % pool.size]
    }

    /**
     * Why-line. Lists concerning drivers in plain language ("TSB −30 + 4.7h sleep
     * + HRV 9 ms"). On a clean readiness day, surfaces the laggard component
     * (which signal is closest to dragging the score down).
     */
    private fun buildRationale(
        tier: Tier,
        tsb: Float?,
        sleep: Float?,
        hrvRatio: Float?,
        hrvAbs: Float?,
        rhrDelta: Int?,
        components: List<ReadinessComponent>,
    ): String {
        val drivers = mutableListOf<String>()
        when {
            tsb != null && tsb <= -25f -> drivers += "TSB ${tsb.roundToInt()} (over-trained)"
            tsb != null && tsb <= -15f -> drivers += "TSB ${tsb.roundToInt()} (deep fatigue)"
        }
        when {
            sleep != null && sleep < 5f -> drivers += "${"%.1fh".format(sleep)} sleep (severe deficit)"
            sleep != null && sleep < 6f -> drivers += "${"%.1fh".format(sleep)} sleep (low)"
        }
        when {
            hrvRatio != null && hrvRatio < 0.70f -> {
                val pct = ((hrvRatio - 1f) * 100).roundToInt()
                drivers += "HRV $pct% vs baseline (crashed)"
            }
            hrvRatio != null && hrvRatio < 0.85f -> {
                val pct = ((hrvRatio - 1f) * 100).roundToInt()
                drivers += "HRV $pct% vs baseline (suppressed)"
            }
            hrvRatio == null && hrvAbs != null && hrvAbs < 12f -> {
                drivers += "HRV ${"%.0f".format(hrvAbs)} ms (below athletic)"
            }
            hrvRatio == null && hrvAbs != null && hrvAbs < 18f -> {
                drivers += "HRV ${"%.0f".format(hrvAbs)} ms (suppressed)"
            }
        }
        when {
            rhrDelta != null && rhrDelta >= 5 -> drivers += "RHR +$rhrDelta bpm (elevated)"
            rhrDelta != null && rhrDelta >= 3 -> drivers += "RHR +$rhrDelta bpm (creeping up)"
        }

        if (drivers.isNotEmpty()) {
            val tail = when (tier) {
                Tier.FullRest, Tier.ActiveRecovery -> " — don't dig the hole deeper."
                Tier.EasyAerobic -> " — keep it light today."
                else -> ""
            }
            return drivers.joinToString(" + ") + tail
        }

        // No concerning signal — surface laggard or full-green message.
        return when (tier) {
            Tier.HardGreenLight -> "All recovery markers green. Body is primed."
            Tier.ModerateEndurance -> {
                val laggard = components.filter { it.score != null }
                    .minByOrNull { it.score ?: 100 }
                laggard?.let { "${it.label.lowercase()} slightly off (${it.detail}) but well within trainable range" }
                    ?: "balanced across all markers"
            }
            Tier.EasyAerobic -> {
                val laggard = components.filter { it.score != null }
                    .minByOrNull { it.score ?: 100 }
                laggard?.let { "${it.label.lowercase()} is the laggard (${it.detail}) — ease into it" }
                    ?: "mild recovery markers — easy day"
            }
            else -> "recovery markers low — protect tomorrow's session"
        }
    }
}
