package com.uruj.ui.trend

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.uruj.data.PostprandialSnapshotRepository
import com.uruj.domain.PostprandialSnapshot
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone4
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * v0.9.34 — Postprandial HRV response trend over time. Reads exclusively
 * from [PostprandialSnapshotRepository] (disk-first per
 * [[reference_snapshot_persistence_architecture]]). Closes the trend-chart
 * gap for the Tier B test #109 family.
 *
 * Each dot = one meal's postprandial RMSSD drop magnitude (% change).
 * Plotted as ABSOLUTE drop (always non-negative) so the y-axis tells the
 * "how strong was the response" story consistently across meals.
 *
 * **Why absolute value**: a +5% RISE post-meal (rare, e.g. for late taps
 * or anti-stress meals) and a -5% drop both represent the same magnitude
 * of metabolic perturbation. We plot |delta| for cleaner visual reading;
 * the per-point detail formatter shows raw signed value.
 *
 * **Tier bands** match the card's tier definitions:
 *   - 0–15 % Stable (UrujZone2 green)
 *   - 15–30 % Typical (UrujZone3 amber)
 *   - 30–50 % Sensitive (UrujZone4)
 *   - 50 %+   Strong (UrujZone5 red)
 *
 * **Direction**: lower drop = better (recovered/stable metabolism).
 * Higher = sensitive/stressed → tier color escalates upward.
 *
 * **Empty state**: rendered by TrendShell when there are no snapshots.
 * Auto-resolves once the rider has marked ≥1 meal + waited 75 min for
 * the post-window compute.
 */
@Composable
fun PostprandialTrendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { PostprandialSnapshotRepository(context) }
    var snapshots by remember { mutableStateOf<List<PostprandialSnapshot>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        snapshots = withContext(Dispatchers.IO) { repo.listAll() }
        loading = false
    }

    val zone = ZoneId.systemDefault()
    val todayIso = LocalDate.now(zone).toString()

    // Map each snapshot to a TrendPoint at its actual meal-mark time.
    // y = absolute RMSSD delta %; null deltas skipped (insufficient data).
    val points = snapshots.mapNotNull { snap ->
        val delta = snap.rmssdDeltaPercent ?: return@mapNotNull null
        TrendPoint(
            labelMs = snap.mealMarkMs,
            y = abs(delta),
            isToday = snap.dateIsoLocal == todayIso,
        )
    }

    val yMax = (points.maxOfOrNull { it.y } ?: 50f).coerceAtLeast(50f) + 10f

    TrendShell(
        spec = TrendSpec(
            eyebrow = "POSTPRANDIAL RESPONSE TREND",
            title = "Meal-induced HRV drop magnitude (Tier B)",
            intro = "Each dot is one meal's autonomic response measured 45-75 min " +
                "after the mark, compared to a -30..-5 min pre-meal baseline. " +
                "Larger drops = more metabolically reactive meal. Track over 1-2 " +
                "weeks to identify your personal baseline response pattern + which " +
                "meal compositions / timings give cleaner responses. Direction: " +
                "lower is better (stable metabolism); higher = sensitive.",
            points = points,
            yMin = 0f,
            yMax = yMax.coerceAtMost(150f),
            yTicks = listOf(15f, 30f, 50f),
            tierBands = listOf(
                TierBand(0f, 15f, UrujZone2, "Stable"),
                TierBand(15f, 30f, UrujZone3, "Typical"),
                TierBand(30f, 50f, UrujZone4, "Sensitive"),
                TierBand(50f, yMax.coerceAtMost(150f), UrujZone5, "Strong"),
            ),
            lineColor = UrujAccent,
            valueFormatter = { "${"%.0f".format(it)}%" },
            detailFormatter = { p ->
                val snap = snapshots.firstOrNull { it.mealMarkMs == p.labelMs }
                if (snap != null) {
                    val signedDelta = snap.rmssdDeltaPercent?.let { "%+.1f%%".format(it) } ?: "—"
                    val mealTime = Instant.ofEpochMilli(snap.mealMarkMs)
                        .atZone(zone).toLocalTime().withSecond(0).withNano(0).toString()
                    val flags = buildList {
                        if (snap.overlapsPriorMeal) add("overlap")
                        if (snap.isDuringSleep) add("sleep")
                        if ((snap.preCoveragePct ?: 1f) < 0.6f) add("pre-low")
                        if ((snap.postCoveragePct ?: 1f) < 0.6f) add("post-low")
                    }
                    val flagStr = if (flags.isNotEmpty()) " · ⚠${flags.joinToString(",")}" else ""
                    "$mealTime · RMSSD Δ $signedDelta · ${snap.source}$flagStr"
                } else ""
            },
            higherIsBetter = false,  // lower drop = stable = better
            emptyTitle = "No postprandial readings yet",
            emptyBody = "Tap MARK MEAL in Bio Lab when you START eating. URUJ " +
                "computes pre/post HRV deltas 75 min later + saves to disk. " +
                "Track over 7-14 days to identify your personal patterns. " +
                "Disk-persisted forever per snapshot architecture.",
            methodologyFootnote = "Postprandial = HRV change 45-75 min after meal mark " +
                "vs -30..-5 min before. Computed via 5-min windowed Lomb-Scargle " +
                "(Task Force 1996) on strap NDJSON. Tier bands per Hara 2016 + " +
                "Tarvainen 2018. Methodology " +
                "${PostprandialSnapshotRepository.METHODOLOGY_VERSION}. Y-axis is " +
                "the absolute magnitude of the change; tap a dot to see the signed " +
                "value (positive = HRV rose post-meal, indicating late-tap or " +
                "anti-stress meal; negative = typical drop). Flags: ⚠overlap " +
                "(prior meal still recovering), ⚠sleep (mark during sleep), " +
                "⚠pre-low/post-low (strap off >40% of window).",
            loading = loading,
        ),
        onBack = onBack,
    )
}
