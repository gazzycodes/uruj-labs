package com.uruj.ui.biolab

import kotlin.math.roundToInt

/**
 * v0.9.21 — single source of truth for TSB ⓘ dialog content (the deeper
 * ATL:CTL ratio + Build-CTL prescription sections that v0.9.18 introduced
 * on Bio Lab). Mirrored into the Readiness card's TRAINING LOAD ⓘ so the
 * rider sees the same math no matter where they tap.
 *
 * Why pull strings out of the dialog composables: when the same content
 * lives in two surfaces (Bio Lab + Readiness card), keeping them in sync
 * via copy-paste is a known failure mode. Single file means future edits
 * touch one place.
 *
 * The InfoSection / YouSection composables themselves stay in their
 * respective dialog files (BioLabInfoDialogs.kt + ReadinessCard.kt) —
 * extracting THOSE is a separate refactor.
 */

/**
 * Educational section: the asymmetric EWMA weighting + ATL:CTL ratio
 * tier table (the "real signal" beyond raw TSB).
 */
internal fun tsbAtlCtlRatioBody(): String =
    "TSB tells you BALANCE. The RATIO of ATL to CTL tells you whether " +
        "today's fatigue is in proportion to your chronic adapted capacity.\n\n" +
        "<1.0  → recovery / detraining (off-season, taper)\n" +
        "1.0–1.3 → maintenance (just keeping fitness)\n" +
        "1.3–1.5 → PRODUCTIVE BUILD (the sweet spot — fitness grows)\n" +
        "1.5–2.0 → heavy block (watch recovery markers daily)\n" +
        "Above 2.0 → OVERLOAD SPIKE — body breaking down faster than " +
        "adapting (non-functional overreach)\n\n" +
        "Every ride hits ATL six times harder than CTL (1/7 vs 1/42 " +
        "weighting). That asymmetry is the WHOLE GAME of progressive " +
        "overload: fatigue spikes, body adapts, fitness slowly creeps up, " +
        "recovery weeks let ATL drain → TSB goes positive → you feel " +
        "fresh AND strong."

/**
 * Educational section: chronic adaptation, CTL benchmarks, the build
 * pattern that grows CTL without spiking ATL.
 */
internal fun tsbBuildCtlPrescriptionBody(): String =
    "The body adapts to CHRONIC load, not acute spikes. Riding once a " +
        "week at 100 TSS won't build CTL because the EWMA washes it " +
        "out. 5×/week at 30 TSS DOES build CTL because the chronic " +
        "input becomes steady-state.\n\n" +
        "CTL benchmarks (cycling, after months of training):\n" +
        "  10–20  → beginner / detrained\n" +
        "  20–40  → recreational rider\n" +
        "  40–70  → competitive / serious amateur\n" +
        "  70–100 → pro base season\n\n" +
        "The build pattern that works: 5–6 short rides/week, identical " +
        "dose (20–40 TSS each), strict Z1/Z2 only, no spikes. CTL drifts " +
        "up by ~1/42 of weekly TSS-average every day. Sustained for 4–6 " +
        "weeks, you can reasonably double your starting CTL. Then " +
        "introduce 1× hard interval session per week WITHOUT crashing " +
        "because chronic capacity can absorb the spike."

/**
 * Personalized verdict on the rider's current ATL:CTL ratio. Matches the
 * tier bands in [tsbAtlCtlRatioBody] so reader can map their number to
 * the published thresholds.
 */
internal fun tsbRatioVerdict(ratio: Float, ctl: Float): String = when {
    ctl < 0.5f -> "Not enough chronic data yet."
    ratio < 1.0f -> "Recovery / detraining zone."
    ratio < 1.3f -> "Maintenance — just keeping fitness."
    ratio < 1.5f -> "PRODUCTIVE BUILD — the sweet spot. Fitness is growing."
    ratio < 2.0f -> "Heavy block — watch recovery markers daily."
    else -> "OVERLOAD SPIKE — body breaking down faster than adapting. Multi-day stand-down indicated."
}

/**
 * Personalized weekly-TSS build prescription. Targets +20% CTL growth
 * over 4–6 weeks, distributed across 5 rides/week of strict Z1/Z2.
 * When ratio is >2.0 (overload spike), recommends draining ATL first
 * before adding more load.
 */
internal fun tsbBuildPrescription(ctl: Float, atlCtlRatio: Float): String {
    if (ctl < 0.5f) return "Not enough data for a CTL target yet."
    if (atlCtlRatio >= 2.0f) {
        return "Priority: drain ATL via genuine rest, then resume building. " +
            "Don't add TSS until ATL/CTL drops below 1.5."
    }
    val ctlTarget = (ctl * 1.2f).coerceAtLeast(11f)
    val weeklyTssTarget = (ctlTarget * 7f).coerceAtLeast(80f)
    val ridesPerWeek = 5
    val tssPerRide = (weeklyTssTarget / ridesPerWeek).toInt()
    return "Target weekly TSS: ${weeklyTssTarget.toInt()} " +
        "(${ridesPerWeek} rides × ~$tssPerRide TSS each, strict Z1/Z2). " +
        "Holds for 4–6 weeks → CTL ${ctl.roundToInt()} → ${ctlTarget.roundToInt()}."
}

/**
 * Compose the full personalized YouSection content shared by both
 * surfaces. Caller wraps in a YouSection block (composable lives in each
 * dialog file).
 *
 * tierLabel + action are surface-specific (Bio Lab uses "FRESH +N",
 * Readiness uses "Today: detail → score") — passed in by the caller.
 */
internal fun tsbYouSectionBody(
    tierLabel: String,
    ctl: Float,
    atl: Float,
    totalLoad42d: Float,
    action: String?,
): String {
    val ratio = if (ctl > 0.5f) atl / ctl else 0f
    val verdict = tsbRatioVerdict(ratio, ctl)
    val prescription = tsbBuildPrescription(ctl, ratio)
    val actionLine = if (action.isNullOrBlank()) "" else "\n\nToday's action: $action"
    return "$tierLabel\n\n" +
        "Fitness (CTL): ${"%.1f".format(ctl)}\n" +
        "Fatigue (ATL): ${"%.1f".format(atl)}\n" +
        "ATL : CTL ratio: ${"%.2f".format(ratio)}\n" +
        "42d total TSS: ${"%.0f".format(totalLoad42d)}\n\n" +
        "Ratio read: $verdict" +
        actionLine +
        "\n\nBuild CTL: $prescription"
}
