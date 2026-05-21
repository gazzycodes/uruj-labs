package com.uruj.ui.trend

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.uruj.data.HrvSnapshot
import com.uruj.data.HrvSnapshotRepository
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone4
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * v0.9.28 — DFA α1 (Detrended Fluctuation Analysis short-scale) trend
 * over time. The athletic-tier killer feature foundation: per Rogero
 * 2021, DFA α1 crosses 0.75 at the rider's actual Aerobic Threshold
 * (LT1 / Z1↔Z2 boundary). Future v0.5+ ramp-test feature will use this.
 *
 * For OVERNIGHT DFA α1 (this screen), trend interpretation:
 *   0.85–1.15 → Healthy fractal scaling (sweet spot)
 *   0.75–0.85 → Mild fatigue / restful sleep
 *   0.50–0.75 → Above Aerobic Threshold (sympathetic active — high
 *               intensity zone, not where overnight should be)
 *   >1.15      → Over-correlated (overtraining warning, autonomic
 *               rigidity)
 *
 * Best is closest to 1.0. Rising trend over weeks = overtraining /
 * autonomic dysfunction early warning (tier-7 long-term health from
 * [[reference_biohacker_lab_vision]]).
 */
@Composable
fun DfaAlpha1TrendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { HrvSnapshotRepository(context) }
    var snapshots by remember { mutableStateOf<List<HrvSnapshot>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        snapshots = withContext(Dispatchers.IO) { repo.listAll() }
        loading = false
    }

    val zone = ZoneId.systemDefault()
    val todayIso = LocalDate.now(zone).toString()
    val points = snapshots.mapNotNull { snap ->
        val dfa = snap.dfaAlpha1 ?: return@mapNotNull null
        TrendPoint(
            labelMs = dateAnchorMs(snap.dateIsoLocal, zone),
            y = dfa,
            isToday = snap.dateIsoLocal == todayIso,
        )
    }

    val yMin = (points.minOfOrNull { it.y } ?: 0.5f).coerceAtMost(0.5f) - 0.1f
    val yMax = (points.maxOfOrNull { it.y } ?: 1.5f).coerceAtLeast(1.5f) + 0.1f

    TrendShell(
        spec = TrendSpec(
            eyebrow = "DFA α1 TREND",
            title = "Fractal scaling of heart rhythm (autonomic complexity)",
            intro = "One reading per overnight HRV window. ~1.0 = healthy " +
                "\"pink-noise\" scaling (sweet spot between randomness and " +
                "rigidity). Drops below 0.75 above your Aerobic Threshold " +
                "(Rogero 2021 — foundation for future LT1 ramp test). Rises " +
                "above 1.15 with overtraining / autonomic rigidity. Read as " +
                "TREND — sustained drift up over 2-3 weeks = early overtraining " +
                "warning (tier-7 long-arc health marker).",
            points = points,
            yMin = yMin.coerceAtLeast(0.3f),
            yMax = yMax.coerceAtMost(2.0f),
            yTicks = listOf(0.5f, 0.85f, 1.15f, 1.5f),
            tierBands = listOf(
                TierBand(0.3f, 0.5f, UrujZone5, "Very low"),
                TierBand(0.5f, 0.75f, UrujZone4, "Above AeT"),
                TierBand(0.75f, 0.85f, UrujZone3, "Mild fatigue"),
                TierBand(0.85f, 1.15f, UrujZone2, "Healthy"),
                TierBand(1.15f, yMax.coerceAtMost(2.0f), UrujZone5, "Over-correlated"),
            ),
            lineColor = UrujAccent,
            valueFormatter = { "%.2f".format(it) },
            detailFormatter = { p ->
                val s = snapshots.firstOrNull { dateAnchorMs(it.dateIsoLocal, zone) == p.labelMs }
                if (s != null) {
                    val sampEn = s.sampleEntropy?.let { "%.2f".format(it) } ?: "—"
                    "α1 ${"%.2f".format(p.y)} · sampEn $sampEn · ${s.windowCount}w"
                } else ""
            },
            higherIsBetter = false,  // closer-to-1.0 is best; UI conveys via bands
            emptyTitle = "No DFA α1 readings yet",
            emptyBody = "URUJ saves one DFA α1 reading per night at Bio Lab open. " +
                "Wear the strap overnight + open Bio Lab tomorrow morning to " +
                "populate. Disk-persisted forever (v0.9.27 snapshot architecture).",
            methodologyFootnote = "DFA α1 = Detrended Fluctuation Analysis " +
                "exponent over scales 4-16 beats (Peng 1994). Computed on " +
                "validated RR diffs per 5-min window, median-aggregated. " +
                "Methodology ${HrvSnapshotRepository.METHODOLOGY_VERSION}. " +
                "Healthy fractal organization ~1.0 (pink noise). <0.75 above " +
                "Aerobic Threshold (Rogero 2021). >1.15 over-correlated " +
                "(overtraining marker if sustained). For OVERNIGHT readings, " +
                "expect 0.85-1.15 healthy. Live during a ride, expect a drop " +
                "below 0.75 as intensity crosses LT1 (future ramp-test feature).",
            loading = loading,
        ),
        onBack = onBack,
    )
}
