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

        // v0.9.42 — subjective tracker inputs (#111). All optional. Null
        // when rider hasn't logged today. Engine treats missing data as
        // "no constraint" (gracefully degrades vs penalizing absence).
        val subjMood = today.tracker?.latestMood
        val subjEnergy = today.tracker?.latestEnergy
        val subjSoreness = today.tracker?.latestSoreness
        val subjSleepQuality = today.tracker?.sleepQualitySubjective

        val severeFlags = mutableListOf<String>()
        if (tsbValue != null && tsbValue <= -25f) severeFlags += "tsb-crashed"
        if (sleepHours != null && sleepHours < 5f) severeFlags += "sleep-crashed"
        if ((hrvRatio != null && hrvRatio < 0.70f) ||
            (hrvRatio == null && hrvAbs != null && hrvAbs < 12f)
        ) severeFlags += "hrv-crashed"
        // v0.9.41 — ABSOLUTE HRV FLOOR severe flag (chronic-baseline trap fix).
        // The old check above only fired when hrvRatio < 0.70 OR baseline absent.
        // Adds: even when ratio says "stable vs your suppressed self", an absolute
        // RMSSD < 15ms is the body screaming "non-functional overreach territory."
        // This is independent of ratio — pure population-norm safety net.
        if (hrvAbs != null && hrvAbs < 15f && "hrv-crashed" !in severeFlags) {
            severeFlags += "hrv-absolute-suppressed"
        }
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

        // v0.9.42 — SUBJECTIVE severe flags (#111 tracker integration).
        // Rider's body knows things sensors don't capture. When mood / energy
        // crashes, or soreness spikes — engine must respect it. Null-safe:
        // when rider hasn't logged, no flag fires (graceful degradation).
        if (subjMood != null && subjMood <= 3f) severeFlags += "subjective-mood-crashed"
        if (subjEnergy != null && subjEnergy <= 3f) severeFlags += "subjective-energy-crashed"
        if (subjSoreness != null && subjSoreness >= 8f) severeFlags += "subjective-soreness-severe"
        if (subjSleepQuality != null && subjSleepQuality <= 3f) severeFlags += "subjective-sleep-poor"

        val mildFlags = mutableListOf<String>()
        if (tsbValue != null && tsbValue <= -15f && "tsb-crashed" !in severeFlags) mildFlags += "tsb-deep"
        if (sleepHours != null && sleepHours < 6f && "sleep-crashed" !in severeFlags) mildFlags += "sleep-low"
        if (hrvRatio != null && hrvRatio < 0.85f && "hrv-crashed" !in severeFlags) mildFlags += "hrv-low"
        if (hrvAbs != null && hrvAbs < 18f && hrvRatio == null && "hrv-crashed" !in severeFlags) mildFlags += "hrv-low"
        if (rhrDelta != null && rhrDelta >= 3 && "rhr-elevated" !in severeFlags) mildFlags += "rhr-creeping"
        if (carIsRecent && carTier == CarTier.SUPPRESSED) mildFlags += "car-suppressed"
        if (patterns.tsbUnderwaterDays >= 3) mildFlags += "tsb-underwater-streak"

        // v0.9.42 — SUBJECTIVE mild flags. Rider feeling "meh" (4 / 10) is
        // caution but not crash. Soreness 5-7 = noticeable, not severe.
        if (subjMood != null && subjMood <= 4f && "subjective-mood-crashed" !in severeFlags) {
            mildFlags += "subjective-mood-low"
        }
        if (subjEnergy != null && subjEnergy <= 4f && "subjective-energy-crashed" !in severeFlags) {
            mildFlags += "subjective-energy-low"
        }
        if (subjSoreness != null && subjSoreness in 5f..7f) {
            mildFlags += "subjective-soreness-moderate"
        }
        if (subjSleepQuality != null && subjSleepQuality <= 4f && "subjective-sleep-poor" !in severeFlags) {
            mildFlags += "subjective-sleep-meh"
        }
        // endregion

        val severeCount = severeFlags.size
        val mildCount = mildFlags.size

        // region Tier selection — multi-signal over-ride on composite score
        val baseTier = when {
            severeCount >= 2 -> ReadinessTier.FullRest
            score < 30 -> ReadinessTier.FullRest
            severeCount == 1 && score < 55 -> ReadinessTier.ActiveRecovery
            score < 45 -> ReadinessTier.ActiveRecovery
            mildCount >= 2 && score < 65 -> ReadinessTier.EasyAerobic
            score < 60 -> ReadinessTier.EasyAerobic
            score < 75 -> ReadinessTier.ModerateEndurance
            else -> ReadinessTier.HardGreenLight
        }

        // v0.9.41 — TIER CEILING from absolute physiology floors.
        //
        // Even when composite score says "go hard", the absolute biomarker
        // values can veto a tier. This implements the safety net that prevents
        // "Green light" recommendations during chronic non-functional overreach
        // when 7d-baseline ratios are misleading.
        //
        // Rule: take the MORE CONSERVATIVE of baseTier vs absoluteCeiling.
        val absoluteCeiling = computeAbsoluteCeiling(today)
        val tier = pickMoreConservative(baseTier, absoluteCeiling)
        // endregion

        // v0.9.49 — TREND + CAR positive-signal booleans for narrative.
        // The engine math (tier selection above) is unchanged. These flags
        // only soften the WORDING when the body is responding to rest:
        //   - HRV trending stable or rising (not still crashing)
        //   - CAR today shows healthy/robust morning activation
        // When both true + rider is multi-day in red, copy shifts from
        // "Extended stand-down · investigate lifestyle" (alarming) to
        // "Day N · body responding · continue rest" (truthful + encouraging).
        // Per [[reference_lab_grade_architecture_rules]] Rule 5 — narrative
        // must reflect TREND DIRECTION, not just absolute thresholds (#217).
        val hrvTrendStableOrRising = trends.hrv?.let { t ->
            t.direction == TrendDirection.RISING ||
                (t.direction == TrendDirection.FLAT && t.sampleCount >= 5)
        } ?: false
        val carHealthyToday = carIsRecent &&
            (carTier == CarTier.NORMAL || carTier == CarTier.ROBUST)

        // region Headline + duration — rotating taglines keyed off day-of-year
        val (headline, duration) = pickHeadline(
            tier = tier,
            dayKey = LocalDate.now().dayOfYear,
            consecutiveRestDays = patterns.consecutiveRestDays,
            hrvTrendStableOrRising = hrvTrendStableOrRising,
            carHealthyToday = carHealthyToday,
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
            // v0.9.42 — subjective drivers for narration
            subjMood = subjMood,
            subjEnergy = subjEnergy,
            subjSoreness = subjSoreness,
            subjSleepQuality = subjSleepQuality,
            // v0.9.49 — trend qualifier for HRV-suppressed copy
            hrvTrend = trends.hrv,
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

        // v0.9.45 — ENGINE DECISION LOGCAT for debugging + audit.
        // One summary line per compute showing inputs + decision flow.
        // Tag: URUJ-ReadinessEngine. Grep this to verify tier overrides.
        android.util.Log.d(
            "URUJ-ReadinessEngine",
            "[v0.9.48] score=$score · tier=$tier (was $baseTier · ceiling $absoluteCeiling) · " +
                "severe=${severeFlags.size}[${severeFlags.joinToString(",")}] · " +
                "mild=${mildFlags.size} · " +
                "hrv=${hrvAbs?.let { "%.1f".format(it) } ?: "—"}ms · " +
                "tsb=${tsbValue?.toInt() ?: "—"} · " +
                "rhrΔ=${rhrDelta ?: "—"} · " +
                "car=${carTier?.name ?: "—"} · " +
                "subj=mood${subjMood?.toInt() ?: "—"}/energy${subjEnergy?.toInt() ?: "—"}/sore${subjSoreness?.toInt() ?: "—"}/sleep${subjSleepQuality?.toInt() ?: "—"}"
        )

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
     * v0.9.41 — Compute the maximum tier permitted by today's absolute
     * physiology values. Returns the most aggressive tier allowed.
     *
     * This is the population-norm safety ceiling that complements the
     * personal-baseline scoring. Rationale: a chronically overreached athlete
     * has a suppressed personal baseline, so "vs your 7d avg" comparisons
     * can permit hard sessions that population norms forbid.
     *
     * Thresholds anchored to:
     *   - Shaffer & Ginsberg 2017 (HRV norms)
     *   - Plews et al. 2013 (athlete HRV)
     *   - Coggan & Allen (TSB / training stress)
     *   - Pruessner 1997 / Clow 2010 (CAR norms)
     */
    private fun computeAbsoluteCeiling(today: ReadinessContext.TodaySnapshot): ReadinessTier {
        val hrvAbs = today.hrv?.rmssdMs
        val tsb = today.tsb?.value
        val rhrDelta = today.rhr?.let { r ->
            if (r.baselineBpm != null) r.todayBpm - r.baselineBpm else null
        }

        // HRV absolute ceiling
        val hrvCeiling = when {
            hrvAbs == null -> ReadinessTier.HardGreenLight
            hrvAbs < 12f -> ReadinessTier.FullRest         // critically suppressed
            hrvAbs < 15f -> ReadinessTier.ActiveRecovery   // severely suppressed
            hrvAbs < 20f -> ReadinessTier.EasyAerobic      // suppressed
            hrvAbs < 25f -> ReadinessTier.ModerateEndurance // below athletic norm
            else -> ReadinessTier.HardGreenLight
        }

        // TSB ceiling — only allow hard sessions when fatigue is resolved
        val tsbCeiling = when {
            tsb == null -> ReadinessTier.HardGreenLight
            tsb <= -25f -> ReadinessTier.FullRest
            tsb <= -20f -> ReadinessTier.ActiveRecovery
            tsb <= -15f -> ReadinessTier.EasyAerobic
            tsb <= -10f -> ReadinessTier.ModerateEndurance
            else -> ReadinessTier.HardGreenLight
        }

        // RHR elevation = illness brewing
        val rhrCeiling = if (rhrDelta == null) {
            ReadinessTier.HardGreenLight
        } else {
            when {
                rhrDelta >= 8 -> ReadinessTier.FullRest
                rhrDelta >= 5 -> ReadinessTier.ActiveRecovery
                rhrDelta >= 3 -> ReadinessTier.EasyAerobic
                else -> ReadinessTier.HardGreenLight
            }
        }

        // v0.9.42 — SUBJECTIVE ceilings (#111 tracker integration).
        // Rider's body knows things sensors don't. When subjective indicators
        // crash, even objective-green can't override (body knows illness
        // brewing, emotional load, hidden fatigue). Null-safe: missing input
        // → no constraint (returns HardGreenLight, allowing other ceilings to dominate).
        val subjMood = today.tracker?.latestMood
        val subjEnergy = today.tracker?.latestEnergy
        val subjSoreness = today.tracker?.latestSoreness
        val subjSleepQuality = today.tracker?.sleepQualitySubjective

        val moodCeiling = when {
            subjMood == null -> ReadinessTier.HardGreenLight
            subjMood <= 3f -> ReadinessTier.ActiveRecovery
            subjMood <= 4f -> ReadinessTier.EasyAerobic
            else -> ReadinessTier.HardGreenLight  // 5+ = no cap from mood alone
        }
        val energyCeiling = when {
            subjEnergy == null -> ReadinessTier.HardGreenLight
            subjEnergy <= 3f -> ReadinessTier.ActiveRecovery
            subjEnergy <= 4f -> ReadinessTier.EasyAerobic
            else -> ReadinessTier.HardGreenLight
        }
        val sorenessCeiling = if (subjSoreness == null) {
            ReadinessTier.HardGreenLight
        } else {
            when {
                subjSoreness >= 8f -> ReadinessTier.ActiveRecovery
                subjSoreness >= 7f -> ReadinessTier.EasyAerobic
                else -> ReadinessTier.HardGreenLight
            }
        }
        val sleepQualityCeiling = when {
            subjSleepQuality == null -> ReadinessTier.HardGreenLight
            subjSleepQuality <= 3f -> ReadinessTier.ActiveRecovery
            subjSleepQuality <= 4f -> ReadinessTier.EasyAerobic
            else -> ReadinessTier.HardGreenLight
        }

        // Take the most conservative (lowest tier) of all ceilings — objective + subjective.
        return listOf(hrvCeiling, tsbCeiling, rhrCeiling,
            moodCeiling, energyCeiling, sorenessCeiling, sleepQualityCeiling)
            .reduce { acc, next -> pickMoreConservative(acc, next) }
    }

    /**
     * v0.9.43 — FORWARD UNLOCK ESTIMATE for a training tier.
     *
     * Linear extrapolation from current trend slopes per gating signal.
     * Returns the predicted "X days at current trajectory" until ALL
     * gating signals cross their thresholds for the tier.
     *
     * Bottleneck = slowest signal. If any signal is moving the WRONG way
     * (e.g. HRV trending down when we need it to rise), returns warning
     * copy explaining the divergence.
     *
     * TSB defaults to +1.5/day rise during rest (typical Coggan EWMA decay).
     * HRV slope from 7d trend. Future v0.9.44+ will refine with personalized
     * recovery-rate learning.
     *
     * Returns null when:
     *   - Already unlocked (no estimate needed)
     *   - Insufficient data (no HRV today / no trend yet)
     *   - Critical signals trending wrong direction (different copy)
     */
    private fun computeTierUnlockDays(
        today: ReadinessContext.TodaySnapshot,
        trends: ReadinessContext.Trends,
        tierKey: String,
    ): String? {
        val hrvNow = today.hrv?.rmssdMs ?: return null
        val tsbNow = today.tsb?.value
        val hrvTrend = trends.hrv

        val (hrvTarget, tsbTarget, tierLabel) = when (tierKey) {
            "tempo" -> Triple(18f, -10f, "TEMPO (Z3)")
            "threshold" -> Triple(25f, -10f, "THRESHOLD (Z4)")
            "vo2" -> Triple(30f, -10f, "VO2 (Z5)")
            else -> return null
        }

        // v0.9.44 — VO2 tier additionally gated by DFA α1 ≤ 1.3 (chronic
        // marker — weeks-to-months timescale). When chronic, defer with
        // descriptive copy rather than huge numeric estimate.
        if (tierKey == "vo2") {
            val dfa = today.hrv?.frequencyDomain?.dfaAlpha1
            if (dfa != null && dfa > 1.3f) {
                return "VO2 (Z5) unlock: gated by DFA α1 (currently ${"%.2f".format(dfa)}, " +
                    "need ≤ 1.30). Chronic marker — typically 4-12 weeks of " +
                    "consistent recovery to normalize."
            }
        }

        // Already cleared? Skip.
        if (hrvNow >= hrvTarget && (tsbNow == null || tsbNow >= tsbTarget)) {
            return null
        }

        // HRV gap days
        val hrvDays: Int? = if (hrvNow >= hrvTarget) {
            0
        } else {
            val slope = hrvTrend?.slopePerDay
            if (slope == null || slope <= 0f) {
                // Not improving — warn explicitly
                if (hrvTrend?.direction == TrendDirection.FALLING) {
                    return "$tierLabel unlock: deferring — HRV trending DOWN " +
                        "${"%.1f".format(kotlin.math.abs(hrvTrend.slopePerDay))} ms/day, " +
                        "need to reverse first"
                }
                return "$tierLabel unlock: HRV stable at " +
                    "${"%.1f".format(hrvNow)} ms (need ${hrvTarget.toInt()}+) — " +
                    "no improvement trend yet, give it a few days"
            }
            ((hrvTarget - hrvNow) / slope).toInt().coerceAtLeast(1)
        }

        // TSB gap days (assume +1.5/day rest decay if no negative training)
        val tsbDays: Int = if (tsbNow == null || tsbNow >= tsbTarget) {
            0
        } else {
            val effectiveSlope = 1.5f  // typical TSB EWMA decay at full rest
            ((tsbTarget - tsbNow) / effectiveSlope).toInt().coerceAtLeast(1)
        }

        // Bottleneck = slowest gating signal
        val daysRaw = maxOf(hrvDays ?: 0, tsbDays)
        if (daysRaw <= 0) return null

        // v0.9.44 — cap at 90 days. Beyond that, slope-based estimate is
        // mostly noise — recovery rate will likely change before then.
        // Surface as "90+ days" rather than misleading huge numbers.
        val daysDisplay = if (daysRaw > 90) "90+" else daysRaw.toString()

        val bottleneck = when {
            hrvDays != null && hrvDays >= tsbDays -> "HRV bottleneck (gap ${"%.1f".format(hrvTarget - hrvNow)} ms)"
            else -> "TSB bottleneck (gap ${"%.1f".format(tsbTarget - tsbNow!!)} pts)"
        }

        // v0.9.44 — removed "~" tilde (rendered as "-" on some Android fonts).
        // Use "in" word + "(est.)" parenthetical for clarity.
        return "$tierLabel unlock in $daysDisplay days (est.) · $bottleneck"
    }

    /**
     * v0.9.41 — Returns the more conservative (lower) of two ReadinessTier
     * values. Tier ordering: FullRest < ActiveRecovery < EasyAerobic
     *   < ModerateEndurance < HardGreenLight.
     */
    private fun pickMoreConservative(a: ReadinessTier, b: ReadinessTier): ReadinessTier {
        val order = listOf(
            ReadinessTier.FullRest,
            ReadinessTier.ActiveRecovery,
            ReadinessTier.EasyAerobic,
            ReadinessTier.ModerateEndurance,
            ReadinessTier.HardGreenLight,
        )
        val aIdx = order.indexOf(a)
        val bIdx = order.indexOf(b)
        return order[minOf(aIdx, bIdx)]
    }

    /**
     * Headline + duration pool per tier, rotated by day-of-year for variety.
     * Multi-day rest streak escalates the FullRest copy so the rider knows
     * we noticed the pattern.
     *
     * v0.9.49 — TREND-AWARE copy (#207 + #217). When the rider is multi-day
     * in red BUT the trend is stable/rising AND today's CAR is healthy, the
     * copy softens from alarming ("Extended stand-down · investigate") to
     * truthful + encouraging ("Day N · body responding · continue rest").
     * The TIER itself is unchanged — only the WORDS reflect that recovery
     * is in progress vs deteriorating.
     */
    private fun pickHeadline(
        tier: ReadinessTier,
        dayKey: Int,
        consecutiveRestDays: Int,
        hrvTrendStableOrRising: Boolean = false,
        carHealthyToday: Boolean = false,
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
        //
        // v0.9.49 — bodyResponding flag determines whether multi-day-rest
        // copy is "investigate lifestyle" (no recovery signals yet) or
        // "body responding, continue rest" (signals improving).
        val totalDays = consecutiveRestDays + 1
        val bodyResponding = hrvTrendStableOrRising && carHealthyToday

        if (tier == ReadinessTier.FullRest && consecutiveRestDays >= 3) {
            return if (bodyResponding) {
                "Day $totalDays · body responding" to
                    "rest holding · HRV stabilizing · CAR healthy — continue full rest"
            } else {
                "Extended stand-down" to
                    "$totalDays days of rest — investigate lifestyle (sleep, stress, fuel, illness)"
            }
        }
        if (tier == ReadinessTier.FullRest && consecutiveRestDays >= 1) {
            return if (bodyResponding) {
                "Day $totalDays · early recovery" to
                    "signals stabilizing — continue rest, body responding"
            } else {
                "Day $totalDays in red" to
                    "$totalDays days running — eat more, sleep more, check chronic load"
            }
        }
        // v0.9.49 — ActiveRecovery + 3+ days resting + body responding =
        // graded re-entry phase. Acknowledge the streak (not silent).
        if (tier == ReadinessTier.ActiveRecovery &&
            consecutiveRestDays >= 3 && bodyResponding
        ) {
            return "Day $totalDays · graded recovery" to
                "Z1 spin ≤ 30 min · body healing · honor the trajectory"
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
     *
     * v0.9.49 — HRV-suppressed drivers now include a trend qualifier
     * (rising / stable / falling) so the rider sees direction at a glance.
     * "HRV 13 ms (suppressed)" → "HRV 13 ms (suppressed · stable, recovering)".
     * Per [[reference_lab_grade_architecture_rules]] Rule 5 — narrative
     * must reflect trend direction, not just absolute thresholds (#217).
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
        // v0.9.42 — subjective drivers (#111). Null when rider hasn't logged.
        subjMood: Float? = null,
        subjEnergy: Float? = null,
        subjSoreness: Float? = null,
        subjSleepQuality: Float? = null,
        // v0.9.49 — trend qualifier for HRV-suppressed copy
        hrvTrend: com.uruj.domain.TrendSeries? = null,
    ): String {
        // v0.9.49 — single qualifier string appended to HRV-suppressed
        // driver. Empty when trend is unknown / not significant. Honest
        // about the data we have: only call something "stable" when we
        // have ≥5 days of trend; only call "recovering" / "falling" when
        // direction is set by the trend computation.
        val hrvTrendQualifier: String = hrvTrend?.let { t ->
            when (t.direction) {
                TrendDirection.RISING -> " · recovering"
                TrendDirection.FALLING -> " · still falling"
                TrendDirection.FLAT -> if (t.sampleCount >= 5) " · stable" else ""
                TrendDirection.INSUFFICIENT_DATA -> ""
            }
        } ?: ""
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
                drivers += "HRV $pct% vs baseline (crashed$hrvTrendQualifier)"
            }
            hrvRatio != null && hrvRatio < 0.85f -> {
                val pct = ((hrvRatio - 1f) * 100).roundToInt()
                drivers += "HRV $pct% vs baseline (suppressed$hrvTrendQualifier)"
            }
            hrvRatio == null && hrvAbs != null && hrvAbs < 12f -> {
                drivers += "HRV ${"%.0f".format(hrvAbs)} ms (below athletic$hrvTrendQualifier)"
            }
            hrvRatio == null && hrvAbs != null && hrvAbs < 18f -> {
                drivers += "HRV ${"%.0f".format(hrvAbs)} ms (suppressed$hrvTrendQualifier)"
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

        // v0.9.42 — SUBJECTIVE drivers (#111). Only included when rider
        // logged AND value is in caution-or-worse band. Positive subjective
        // (7-10) doesn't add to driver list (no concern to surface).
        if (subjMood != null && subjMood <= 4f) {
            drivers += "mood ${subjMood.toInt()}/10 (low)"
        }
        if (subjEnergy != null && subjEnergy <= 4f) {
            drivers += "energy ${subjEnergy.toInt()}/10 (low)"
        }
        if (subjSoreness != null && subjSoreness >= 7f) {
            drivers += "soreness ${subjSoreness.toInt()}/10 (high)"
        }
        if (subjSleepQuality != null && subjSleepQuality <= 4f) {
            drivers += "sleep quality ${subjSleepQuality.toInt()}/10 subjective (low)"
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

        // v0.9.43 — FORWARD UNLOCK ESTIMATES (motivational layer).
        // For each LOCKED tier, compute "X days at current trajectory until
        // unlock." Linear extrapolation from 7d trend slopes. Reads gating
        // signal current values + slopes, picks the bottleneck signal, shows
        // estimated days. Critical: tells rider WHEN they'll be cleared,
        // ending the "never ready" worry. Caveats noted in copy.
        val tempoEstimate = computeTierUnlockDays(today, trends, tierKey = "tempo")
        val thresholdEstimate = computeTierUnlockDays(today, trends, tierKey = "threshold")
        val vo2Estimate = computeTierUnlockDays(today, trends, tierKey = "vo2")
        if (tempoEstimate != null) insights += tempoEstimate
        if (thresholdEstimate != null) insights += thresholdEstimate
        if (vo2Estimate != null) insights += vo2Estimate

        // v0.9.43 — SUBJECTIVE / Body Voice insight (#111 visibility).
        // When rider has logged subjective tracker values today, surface them
        // explicitly so they know body input is being heard by the engine.
        // Skipped when not logged (no nagging — graceful per v0.9.42 design).
        val subjMood = today.tracker?.latestMood
        val subjEnergy = today.tracker?.latestEnergy
        val subjSoreness = today.tracker?.latestSoreness
        val subjSleepQuality = today.tracker?.sleepQualitySubjective
        val anySubjective = subjMood != null || subjEnergy != null ||
            subjSoreness != null || subjSleepQuality != null

        if (anySubjective) {
            val parts = mutableListOf<String>()
            subjMood?.let { parts += "mood ${it.toInt()}/10" }
            subjEnergy?.let { parts += "energy ${it.toInt()}/10" }
            subjSoreness?.let { parts += "soreness ${it.toInt()}/10" }
            subjSleepQuality?.let { parts += "sleep ${it.toInt()}/10" }

            // Determine prefix based on whether subjective is in concern band
            val anyConcern = (subjMood != null && subjMood <= 4f) ||
                (subjEnergy != null && subjEnergy <= 4f) ||
                (subjSoreness != null && subjSoreness >= 7f) ||
                (subjSleepQuality != null && subjSleepQuality <= 4f)

            val prefix = if (anyConcern) {
                "Body voice ⚠"
            } else {
                "Body voice ✓"
            }

            // v0.9.45 — STALENESS flag for subjective entries.
            // When the LATEST entry today is older than 12 hours, surface
            // a hint that mood/energy may have shifted since logging.
            // Uses tracker.capturedAtMs (timestamp when entries were loaded
            // into context — close enough to "now" that comparing to entry
            // timestamps works as freshness proxy).
            val latestMs = listOfNotNull(
                today.tracker?.capturedAtMs,
            ).maxOrNull() ?: System.currentTimeMillis()
            val anyStale = today.tracker?.let { tracker ->
                // We don't have per-entry timestamps in TrackerToday signal pack
                // (it surfaces latest values not full entries). Best heuristic
                // available: if today is well into evening (>18:00) AND entries
                // exist, assume they were likely logged earlier and may be stale.
                // Future v0.9.46+ refinement: thread entry timestamps through.
                val nowHour = java.time.Instant.ofEpochMilli(latestMs)
                    .atZone(java.time.ZoneId.systemDefault())
                    .hour
                nowHour >= 18 && tracker.totalEntriesToday > 0
            } ?: false

            val staleSuffix = if (anyStale) " (logged earlier today — may have shifted)" else ""
            insights += "$prefix — ${parts.joinToString(" · ")}$staleSuffix"
        }
        // Note: when NO subjective logged, no insight added (graceful degradation
        // per v0.9.42 — engine doesn't nag rider to log more).

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
