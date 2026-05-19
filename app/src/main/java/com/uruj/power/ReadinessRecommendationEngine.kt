package com.uruj.power

import com.uruj.domain.CarTier
import com.uruj.domain.ReadinessComponent
import com.uruj.domain.ReadinessContext
import com.uruj.domain.ReadinessTier
import com.uruj.domain.Recommendation
import com.uruj.domain.TrendDirection
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * v0.9.4 — rule-based [ReadinessReasoner]. Reads [ReadinessContext] (NOT
 * raw inputs) and emits a [Recommendation].
 *
 * # Why context-based
 *
 * v0.9.3 engine took 6 scalar inputs and ran bucket logic. v0.9.4 reasoner
 * takes the unified signal pack — today's snapshot + trends + multi-day
 * patterns + provenance. Same engine shape externally; vastly richer
 * inputs internally. Concrete upgrades:
 *
 *  1. **CAR as 5th severe flag** — exaggerated (>30 bpm) OR blunted (<5 bpm)
 *     CAR captured this morning weights the tier decision. Blunted +
 *     chronic-stress pattern is the textbook over-reach early-warning.
 *
 *  2. **HRV trend direction** — falling slope across 7+ nights matters even
 *     when today's absolute looks OK. Rising slope from a low absolute is
 *     a different (recovering) story than falling slope from a normal
 *     absolute (deteriorating).
 *
 *  3. **Multi-day rest enforcement** — `consecutiveRestDays` from
 *     [com.uruj.data.RecommendationSnapshotRepository] escalates copy.
 *     2 days running → "two days in red — eat more, sleep more,
 *     investigate stress." 4+ days → "extended stand-down — check chronic
 *     load + lifestyle factors."
 *
 *  4. **Confidence-tiered language** — high data coverage + ≥2 severe
 *     flags → decisive copy ("REST. Train tomorrow."). Borderline → hedged
 *     ("Rest is better; if you must, 20-min Z1 only."). Driven by
 *     `provenance.overallConfidence` × `severeFlagCount`.
 *
 *  5. **Missing-data callout** — when expected signal absent (no HRV last
 *     night, no recent CAR), surface it as a separate UI line so the
 *     rider knows the engine knows.
 *
 *  6. **Cross-metric insights** — list of bullet observations that don't
 *     change the tier but enrich the rationale. Examples:
 *     "HRV trending down 2 nights running" / "TSB underwater 3 consecutive
 *     days" / "VO2 trending up despite suppressed autonomic state."
 *
 * # AI HOOK
 *
 * A future `GroqAiReasoner` (Task #105 / v0.5) implements the same
 * interface, takes the same [ReadinessContext], emits the same
 * [Recommendation]. The AI version generates `rationale` + `insights` as
 * free-form sentences instead of the structured assembly here. UI consumes
 * either identically. CompositeReasoner can wrap both (AI primary, rules
 * fallback) for bulletproofing.
 */
class RuleBasedReasoner : ReadinessReasoner {

    override suspend fun reason(
        context: ReadinessContext,
        score: Int,
        components: List<ReadinessComponent>,
    ): Recommendation {
        val today = context.today
        val trends = context.trends
        val patterns = context.patterns

        // region Severity flag extraction
        // Severe = trip the over-reach / recovery-mandated wire.
        // Mild = caution, not block.
        val tsbValue = today.tsb?.value
        val sleepHours = today.sleep?.hours
        val hrvRatio = today.hrv?.ratioVsBaseline
        val hrvAbs = today.hrv?.rmssdMs
        val rhrDelta = today.rhr?.let { r ->
            if (r.baselineBpm != null) r.todayBpm - r.baselineBpm else null
        }
        val carTier = today.car?.tier
        val carIsRecent = today.car != null && today.car.ageHours <= 24f

        val severeFlags = mutableListOf<String>()
        if (tsbValue != null && tsbValue <= -25f) severeFlags += "tsb-crashed"
        if (sleepHours != null && sleepHours < 5f) severeFlags += "sleep-crashed"
        if ((hrvRatio != null && hrvRatio < 0.70f) ||
            (hrvRatio == null && hrvAbs != null && hrvAbs < 12f)
        ) severeFlags += "hrv-crashed"
        if (rhrDelta != null && rhrDelta >= 5) severeFlags += "rhr-elevated"
        // v0.9.4 — CAR as 5th severe flag. Exaggerated OR blunted both
        // signal HPA dysregulation (acute stress / chronic over-reach).
        if (carIsRecent && (carTier == CarTier.EXAGGERATED || carTier == CarTier.BLUNTED)) {
            severeFlags += "car-${carTier.name.lowercase()}"
        }
        // v0.9.4 — HRV trend direction. Falling slope counts as severe even
        // when today's absolute is borderline.
        val hrvTrendFalling = trends.hrv?.direction == TrendDirection.FALLING
        if (hrvTrendFalling) severeFlags += "hrv-trending-down"

        val mildFlags = mutableListOf<String>()
        if (tsbValue != null && tsbValue <= -15f && "tsb-crashed" !in severeFlags) mildFlags += "tsb-deep"
        if (sleepHours != null && sleepHours < 6f && "sleep-crashed" !in severeFlags) mildFlags += "sleep-low"
        if (hrvRatio != null && hrvRatio < 0.85f && "hrv-crashed" !in severeFlags) mildFlags += "hrv-low"
        if (hrvAbs != null && hrvAbs < 18f && hrvRatio == null && "hrv-crashed" !in severeFlags) mildFlags += "hrv-low"
        if (rhrDelta != null && rhrDelta >= 3 && "rhr-elevated" !in severeFlags) mildFlags += "rhr-creeping"
        if (carIsRecent && carTier == CarTier.SUPPRESSED) mildFlags += "car-suppressed"
        if (patterns.tsbUnderwaterDays >= 3) mildFlags += "tsb-underwater-streak"
        // endregion

        val severeCount = severeFlags.size
        val mildCount = mildFlags.size

        // region Tier selection — multi-signal over-ride on composite score
        val tier = when {
            severeCount >= 2 -> ReadinessTier.FullRest
            score < 30 -> ReadinessTier.FullRest
            severeCount == 1 && score < 55 -> ReadinessTier.ActiveRecovery
            score < 45 -> ReadinessTier.ActiveRecovery
            mildCount >= 2 && score < 65 -> ReadinessTier.EasyAerobic
            score < 60 -> ReadinessTier.EasyAerobic
            score < 75 -> ReadinessTier.ModerateEndurance
            else -> ReadinessTier.HardGreenLight
        }
        // endregion

        // region Headline + duration — rotating taglines keyed off day-of-year
        val (headline, duration) = pickHeadline(
            tier = tier,
            dayKey = LocalDate.now().dayOfYear,
            consecutiveRestDays = patterns.consecutiveRestDays,
        )
        // endregion

        // region Rationale — drivers in plain language
        val rationale = buildRationale(
            tier = tier,
            tsbValue = tsbValue,
            sleepHours = sleepHours,
            hrvRatio = hrvRatio,
            hrvAbs = hrvAbs,
            rhrDelta = rhrDelta,
            carTier = if (carIsRecent) carTier else null,
            carAmplitude = today.car?.amplitudeBpm,
            components = components,
        )
        // endregion

        // region Cross-metric insights — bullets below the rationale.
        // v0.9.5 polish: pass severeFlags so we can skip dup info that's
        // already in the rationale (e.g. CAR exaggerated appears in both
        // — the rationale line is the source of truth, bullet was noise).
        val insights = buildInsights(today, trends, patterns, severeFlags)
        // endregion

        // region Missing-data callout
        val missingCallout = buildMissingDataCallout(context.provenance.missingSignals)
        // endregion

        return Recommendation(
            tier = tier,
            headline = headline,
            duration = duration,
            rationale = rationale,
            insights = insights,
            missingSignalsCallout = missingCallout,
            severeFlags = severeFlags,
            mildFlags = mildFlags,
        )
    }

    /**
     * Headline + duration pool per tier, rotated by day-of-year for variety.
     * Multi-day rest streak escalates the FullRest copy so the rider knows
     * we noticed the pattern.
     */
    private fun pickHeadline(
        tier: ReadinessTier,
        dayKey: Int,
        consecutiveRestDays: Int,
    ): Pair<String, String?> {
        // v0.9.4 — multi-day rest enforcement. Escalate copy when the
        // rider's been resting multiple days; chronic state needs lifestyle
        // attention, not just "rest more."
        //
        // v0.9.5 polish: threshold off-by-one fix.
        // consecutiveRestDays counts PRIOR days only (excludes today).
        //   consecutiveRestDays=0 → today is day 1 of a streak → no escalation
        //   consecutiveRestDays=1 → today is day 2 → "Day 2 in red"
        //   consecutiveRestDays=3 → today is day 4 → "Extended stand-down"
        // Pre-v0.9.5 conditions (>= 2 / >= 4) were off by one — would have
        // required 3 / 5 total days before firing. Plus the "two days
        // running" text was static — wrong for day 3+. Now dynamic.
        val totalDays = consecutiveRestDays + 1
        if (tier == ReadinessTier.FullRest && consecutiveRestDays >= 3) {
            return "Extended stand-down" to
                "$totalDays days of rest — investigate lifestyle (sleep, stress, fuel, illness)"
        }
        if (tier == ReadinessTier.FullRest && consecutiveRestDays >= 1) {
            return "Day $totalDays in red" to
                "$totalDays days running — eat more, sleep more, check chronic load"
        }

        val pool: List<Pair<String, String?>> = when (tier) {
            ReadinessTier.FullRest -> listOf(
                "Rest day" to "walk + hydrate, that's it",
                "Full stand-down" to "no ride — body is recovering",
                "Skip today" to "pause is the workout",
                "Hold the line" to "let adaptation catch up; train tomorrow",
            )
            ReadinessTier.ActiveRecovery -> listOf(
                "Active recovery only" to "Z1 spin ≤ 30 min, < 134 bpm",
                "Zone 1 spin" to "20–30 min cap, conversation pace",
                "Recovery ride or rest" to "≤ 30 min Z1, or skip entirely",
                "Spin out the legs" to "20 min Z1, easy gears only",
            )
            ReadinessTier.EasyAerobic -> listOf(
                "Easy Z2 endurance" to "45–60 min cap, don't push",
                "Aerobic only" to "Z2, 60 min, conversational",
                "Steady Z2" to "45–75 min, keep it boring",
                "Endurance pace" to "Z2 60 min, no tempo today",
            )
            ReadinessTier.ModerateEndurance -> listOf(
                "Moderate aerobic" to "Z2–Z3, 60–90 min",
                "Solid base session" to "60–120 min Z2, optional Z3 blocks",
                "Productive endurance" to "Z2 base + tempo if you feel it",
                "Good training day" to "60–90 min Z2, controlled Z3 OK",
            )
            ReadinessTier.HardGreenLight -> listOf(
                "Green light — go hard" to "threshold or VO2 session",
                "All systems primed" to "Z4 intervals or VO2 max work",
                "Hard day cleared" to "threshold, sweet-spot, or VO2 — pick one",
                "Race the past you" to "intervals, threshold, or sustained tempo",
            )
        }
        return pool[((dayKey % pool.size) + pool.size) % pool.size]
    }

    /**
     * Rationale — lists concerning drivers in plain language. CAR-aware in
     * v0.9.4 (exaggerated +49 bpm shows up in the rationale, no more
     * silent ignore).
     */
    private fun buildRationale(
        tier: ReadinessTier,
        tsbValue: Float?,
        sleepHours: Float?,
        hrvRatio: Float?,
        hrvAbs: Float?,
        rhrDelta: Int?,
        carTier: CarTier?,
        carAmplitude: Float?,
        components: List<ReadinessComponent>,
    ): String {
        val drivers = mutableListOf<String>()
        when {
            tsbValue != null && tsbValue <= -25f -> drivers += "TSB ${tsbValue.roundToInt()} (over-trained)"
            tsbValue != null && tsbValue <= -15f -> drivers += "TSB ${tsbValue.roundToInt()} (deep fatigue)"
        }
        when {
            sleepHours != null && sleepHours < 5f -> drivers += "${"%.1fh".format(sleepHours)} sleep (severe deficit)"
            sleepHours != null && sleepHours < 6f -> drivers += "${"%.1fh".format(sleepHours)} sleep (low)"
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
        // v0.9.4 — CAR as driver
        when (carTier) {
            CarTier.EXAGGERATED -> drivers += "CAR +${carAmplitude?.roundToInt() ?: 0} bpm (acute stress signal)"
            CarTier.BLUNTED -> drivers += "CAR flat (chronic-stress pattern)"
            CarTier.SUPPRESSED -> drivers += "CAR suppressed (HPA-axis dampened)"
            else -> {}
        }

        if (drivers.isNotEmpty()) {
            val tail = when (tier) {
                ReadinessTier.FullRest, ReadinessTier.ActiveRecovery -> " — don't dig the hole deeper."
                ReadinessTier.EasyAerobic -> " — keep it light today."
                else -> ""
            }
            return drivers.joinToString(" + ") + tail
        }

        return when (tier) {
            ReadinessTier.HardGreenLight -> "All recovery markers green. Body is primed."
            ReadinessTier.ModerateEndurance -> {
                val laggard = components.filter { it.score != null }
                    .minByOrNull { it.score ?: 100 }
                laggard?.let { "${it.label.lowercase()} slightly off (${it.detail}) but well within trainable range" }
                    ?: "balanced across all markers"
            }
            ReadinessTier.EasyAerobic -> {
                val laggard = components.filter { it.score != null }
                    .minByOrNull { it.score ?: 100 }
                laggard?.let { "${it.label.lowercase()} is the laggard (${it.detail}) — ease into it" }
                    ?: "mild recovery markers — easy day"
            }
            else -> "recovery markers low — protect tomorrow's session"
        }
    }

    /**
     * Cross-metric insights — bullets that enrich the rationale without
     * changing the tier. Surface trends + multi-day patterns the rider
     * would otherwise miss looking at single-day numbers.
     */
    private fun buildInsights(
        today: ReadinessContext.TodaySnapshot,
        trends: ReadinessContext.Trends,
        patterns: ReadinessContext.Patterns,
        /**
         * v0.9.5 — passed so insights can skip info already surfaced in the
         * rationale. Avoids "CAR +49 bpm" appearing twice (once as rationale
         * driver, once as bullet).
         */
        severeFlags: List<String>,
    ): List<String> {
        val insights = mutableListOf<String>()

        // HRV trend
        trends.hrv?.let { hrv ->
            if (hrv.direction == TrendDirection.FALLING && hrv.sampleCount >= 3) {
                insights += "HRV trending down ${hrv.sampleCount} nights running (slope ${"%.1f".format(hrv.slopePerDay)} ms/day)"
            } else if (hrv.direction == TrendDirection.RISING && hrv.sampleCount >= 3) {
                insights += "HRV recovering ${hrv.sampleCount} nights running (slope +${"%.1f".format(hrv.slopePerDay)} ms/day)"
            }
        }

        // RHR trend
        trends.rhr?.let { rhr ->
            if (rhr.direction == TrendDirection.RISING && rhr.sampleCount >= 5) {
                insights += "RHR creeping up across last ${rhr.sampleCount} nights (illness / over-reach early warning)"
            } else if (rhr.direction == TrendDirection.FALLING && rhr.sampleCount >= 5) {
                insights += "RHR dropping over last ${rhr.sampleCount} nights — long-arc fitness building"
            }
        }

        // VO2 trend (long-arc, encouraging signal even on bad days)
        trends.vo2?.let { vo2 ->
            if (vo2.direction == TrendDirection.RISING && vo2.sampleCount >= 5) {
                insights += "VO2 trend rising — aerobic fitness improving on the long arc"
            }
        }

        // TSB underwater streak
        if (patterns.tsbUnderwaterDays >= 3) {
            insights += "TSB underwater ${patterns.tsbUnderwaterDays} consecutive days — chronic over-reach"
        }

        // Multi-day low-readiness
        if (patterns.consecutiveLowReadinessDays >= 3) {
            insights += "Readiness < 50 for ${patterns.consecutiveLowReadinessDays} days running — check sleep + load"
        }

        // CAR / today's stress signal — surface MILD cases (SUPPRESSED) here
        // since rationale only lists EXAGGERATED/BLUNTED as severe drivers.
        // v0.9.5 polish: dedup EXAGGERATED/BLUNTED — already shown as rationale
        // driver, so the insight bullet was redundant noise. Pass severeFlags
        // so we can detect "already in rationale" cleanly.
        today.car?.let { car ->
            if (car.ageHours > 24f) return@let
            when (car.tier) {
                CarTier.SUPPRESSED ->
                    insights += "CAR suppressed (${car.amplitudeBpm.roundToInt()} bpm) — HPA-axis dampened, chronic stress watch"
                CarTier.EXAGGERATED,
                CarTier.BLUNTED -> {
                    // Already in rationale via severeFlag — skip dup.
                }
                CarTier.NORMAL,
                CarTier.ROBUST -> {
                    // Positive signal — surface as insight on otherwise-meh days
                    // so the rider sees the win, but only when no severe flag
                    // fired today (don't sugarcoat a bad day with a CAR ✓).
                    if (severeFlags.isEmpty()) {
                        insights += "CAR healthy (${car.amplitudeBpm.roundToInt()} bpm) — HPA-axis activating normally"
                    }
                }
            }
        }

        return insights
    }

    /**
     * Missing-data callout — surfaces when an expected signal isn't there
     * so the rider knows what the engine isn't seeing today.
     */
    private fun buildMissingDataCallout(missingSignals: List<String>): String? {
        if (missingSignals.isEmpty()) return null
        val human = missingSignals.mapNotNull { signal ->
            when (signal) {
                "hrv-last-night" -> "HRV (wore strap overnight?)"
                "sleep-last-night" -> "Sleep (Samsung sync?)"
                "rhr-baseline" -> "Athletic RHR"
                "training-load" -> "Training load"
                "car-today" -> null  // CAR is supplementary, don't nag
                else -> null
            }
        }
        if (human.isEmpty()) return null
        return "Missing today: ${human.joinToString(" · ")}"
    }
}

/**
 * v0.9.4 — kept as object wrapper for backward compatibility with the v0.9.3
 * call site in [ReadinessCalculator]. The actual logic lives in
 * [RuleBasedReasoner]. New code should construct a RuleBasedReasoner directly.
 *
 * # AI HOOK
 *
 * v0.9.3's `ReadinessRecommendationEngine.build(score, components, inputs)`
 * is the legacy entry. v0.9.4 replaces this with the reasoner interface.
 * Future AI swap-in happens at the [ReadinessReasoner] seam, not here.
 */
@Deprecated(
    "Use RuleBasedReasoner via ReadinessReasoner interface (v0.9.4)",
    ReplaceWith("RuleBasedReasoner()"),
)
object ReadinessRecommendationEngine
