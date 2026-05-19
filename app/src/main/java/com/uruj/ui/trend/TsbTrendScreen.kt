package com.uruj.ui.trend

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.uruj.data.ReadinessRepository
import com.uruj.data.TsbSnapshot
import com.uruj.data.TsbSnapshotRepository
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * v0.9.6 — Training Stress Balance (TSB) curve over time. The Coggan
 * fitness-fatigue chart cyclists have used since 2003.
 *
 * Data source: [TsbSnapshotRepository] daily snapshots saved by
 * [com.uruj.data.ReadinessRepository] at every Readiness compute, via
 * v0.9.1 disk-first persistence. Captures CTL + ATL + TSB triple per day
 * — the trend chart renders TSB; readings list shows CTL/ATL alongside.
 *
 * Tier bands (Coggan convention):
 *   +5 to +20    Race-ready / fresh
 *   −5 to +5     Balanced (normal training)
 *   −15 to −5    Productive fatigue (adaptation territory)
 *   −25 to −15   Significant fatigue (heavy block)
 *   <−25         Over-trained (rest mandatory)
 *
 * Rising TSB = recovery / taper. Falling = absorbing load. Both are normal
 * during structured training — the SHAPE of the curve tells the story.
 */
@Composable
fun TsbTrendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { TsbSnapshotRepository(context) }
    var snapshots by remember { mutableStateOf<List<TsbSnapshot>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        snapshots = withContext(Dispatchers.IO) {
            // v0.9.11 — lazy TSB backfill on first open. Runs EWMA loop
            // once per past day. URUJ rides are permanent (full history)
            // but Samsung non-cycling sessions ~42d HC retention → older
            // days may under-count multi-sport load. Trend chart still
            // shows the shape; methodology tag on backfilled days flags
            // the limitation.
            val existing = repo.listAll()
            val hasPastSnapshots = existing.any {
                it.dateIsoLocal != LocalDate.now(ZoneId.systemDefault()).toString()
            }
            if (!hasPastSnapshots) {
                runCatching {
                    ReadinessRepository(context).backfillTsbSnapshots(days = 30)
                }
                return@withContext repo.listAll()
            }
            existing
        }
        loading = false
    }

    val zone = ZoneId.systemDefault()
    val todayIso = LocalDate.now(zone).toString()
    // v0.9.10 — labelMs date-anchored (see RhrTrendScreen comment).
    val points = snapshots.map { snap ->
        TrendPoint(
            labelMs = dateAnchorMs(snap.dateIsoLocal, zone),
            y = snap.tsb,
            isToday = snap.dateIsoLocal == todayIso,
        )
    }

    val yMin = (points.minOfOrNull { it.y } ?: -40f).coerceAtMost(-40f) - 2f
    val yMax = (points.maxOfOrNull { it.y } ?: 20f).coerceAtLeast(20f) + 2f

    TrendShell(
        spec = TrendSpec(
            eyebrow = "TRAINING STATE TREND",
            title = "Fitness vs fatigue curve",
            intro = "TSB = CTL (42d fitness EWMA) − ATL (7d fatigue EWMA). " +
                "Coggan's fitness-fatigue model since 2003. SHAPE of the curve " +
                "tells the story: dropping = absorbing load, rising = recovery " +
                "or taper. Race-day target: +5 to +15. Productive training zone: " +
                "−10 to −20. Below −25 = rest day mandatory.",
            points = points,
            yMin = yMin.coerceAtLeast(-50f),
            yMax = yMax.coerceAtMost(30f),
            yTicks = listOf(-25f, -15f, -5f, 5f),
            tierBands = listOf(
                TierBand(5f, yMax.coerceAtMost(30f), UrujZone2, "Fresh"),
                TierBand(-5f, 5f, UrujZone2, "Balanced"),
                TierBand(-15f, -5f, UrujZone3, "Productive"),
                TierBand(-25f, -15f, UrujZone3, "Significant fatigue"),
                TierBand(yMin.coerceAtLeast(-50f), -25f, UrujZone5, "Over-trained"),
            ),
            lineColor = UrujAccent,
            valueFormatter = { "${if (it >= 0) "+" else ""}${it.roundToInt()}" },
            detailFormatter = { p ->
                val s = snapshots.firstOrNull { it.computedAtMs == p.labelMs }
                if (s != null) "CTL ${s.ctl.roundToInt()} · ATL ${s.atl.roundToInt()}"
                else ""
            },
            higherIsBetter = true,
            emptyTitle = "No TSB snapshots yet",
            emptyBody = "URUJ saves a TSB snapshot every time Readiness " +
                "computes. Open the LAB tab to capture today's. The curve " +
                "builds as snapshots accumulate. Disk-persisted forever — your " +
                "6-month fitness arc is yours, not Strava's paywall.",
            methodologyFootnote = "TSB = CTL − ATL. CTL = 42d EWMA of TSS; ATL " +
                "= 7d EWMA of TSS. Cycling TSS from power. Multi-sport hrTSS " +
                "(running/HIIT) from HR Reserve fraction. Methodology " +
                "${TsbSnapshotRepository.METHODOLOGY_VERSION}. Race-day " +
                "target +5..+15. Productive training −10..−20. <-25 mandates " +
                "rest. Every dot persisted to URUJ's local disk — Strava + " +
                "Garmin paywall this curve, URUJ doesn't. Today's reading " +
                "renders as a brighter dot.",
            loading = loading,
        ),
        onBack = onBack,
    )
}
