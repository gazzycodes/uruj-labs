package com.uruj.ui.trend

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.uruj.data.Vo2Snapshot
import com.uruj.data.Vo2SnapshotRepository
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * v0.9.6 — VO2 max trend over time (URUJ's Uth-Sørensen consensus value).
 *
 * Data source: [Vo2SnapshotRepository] daily snapshots saved at Bio Lab
 * compute time via v0.9.1 disk-first persistence. Captures URUJ's consensus
 * value PLUS Samsung's HC-reported value side-by-side per snapshot, so the
 * trend chart shows URUJ's number — readings list footer shows Samsung's
 * for cross-validation when present.
 *
 * Cooper classification bands (male, 20-29):
 *   60+ mL/kg/min  Elite (top 5%)
 *   52–60          Excellent
 *   47–52          Good
 *   42–47          Above average
 *   <42            Average or below
 *
 * Long-arc fitness signal — VO2 trend over months > daily variation.
 */
@Composable
fun Vo2TrendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Vo2SnapshotRepository(context) }
    var snapshots by remember { mutableStateOf<List<Vo2Snapshot>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        snapshots = withContext(Dispatchers.IO) { repo.listAll() }
        loading = false
    }

    val zone = ZoneId.systemDefault()
    val todayIso = LocalDate.now(zone).toString()
    // v0.9.10 — labelMs date-anchored (see RhrTrendScreen comment).
    val points = snapshots.map { snap ->
        TrendPoint(
            labelMs = dateAnchorMs(snap.dateIsoLocal, zone),
            y = snap.urujConsensusMlKgMin,
            isToday = snap.dateIsoLocal == todayIso,
        )
    }

    val yMin = (points.minOfOrNull { it.y } ?: 35f).coerceAtMost(35f) - 2f
    val yMax = (points.maxOfOrNull { it.y } ?: 70f).coerceAtLeast(70f) + 3f

    TrendShell(
        spec = TrendSpec(
            eyebrow = "VO₂ MAX TREND",
            title = "Aerobic capacity over time",
            intro = "URUJ Uth-Sørensen estimate from max HR / Athletic RHR ratio. " +
                "Long-arc fitness signal — months > days. Strongest predictor of " +
                "endurance performance + all-cause mortality. Lab CPET is the " +
                "gold standard; URUJ is a calibrated field estimate.",
            points = points,
            yMin = yMin.coerceAtLeast(25f),
            yMax = yMax.coerceAtMost(80f),
            yTicks = listOf(42f, 47f, 52f, 60f),
            tierBands = listOf(
                TierBand(60f, yMax.coerceAtMost(80f), UrujZone2, "Elite"),
                TierBand(52f, 60f, UrujZone2, "Excellent"),
                TierBand(47f, 52f, UrujZone3, "Good"),
                TierBand(42f, 47f, UrujZone3, "Above avg"),
                TierBand(yMin.coerceAtLeast(25f), 42f, UrujZone5, "Average or below"),
            ),
            lineColor = UrujAccent,
            valueFormatter = { "${"%.1f".format(it)} mL/kg/min" },
            detailFormatter = { p ->
                val s = snapshots.firstOrNull { it.computedAtMs == p.labelMs }
                if (s?.samsungMlKgMin != null) "Samsung ${"%.1f".format(s.samsungMlKgMin)}"
                else ""
            },
            higherIsBetter = true,
            emptyTitle = "No VO2 readings yet",
            emptyBody = "URUJ saves one VO2 reading per day at Bio Lab open. " +
                "Open Bio Lab over the coming days — the trend builds as " +
                "snapshots accumulate. Persisted forever — won't roll off with " +
                "HC's 30-day window.",
            methodologyFootnote = "VO2 ≈ 15.3 × (MaxHR / RestingHR) — Uth-Sørensen " +
                "formula validated against lab CPET within ±10%. Methodology " +
                "${Vo2SnapshotRepository.METHODOLOGY_VERSION}. Cooper " +
                "classification (male 20-29): 60+ elite, 52-60 excellent, 47-52 " +
                "good, 42-47 above avg. Samsung's value (when present) shown in " +
                "the readings list for cross-validation. Every dot persisted to " +
                "URUJ's local disk. Today's reading renders as a brighter dot.",
            loading = loading,
        ),
        onBack = onBack,
    )
}
