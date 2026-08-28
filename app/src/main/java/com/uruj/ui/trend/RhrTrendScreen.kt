package com.uruj.ui.trend

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.uruj.data.ReadinessRepository
import com.uruj.data.RhrSnapshot
import com.uruj.data.RhrSnapshotRepository
import com.uruj.data.SleepSnapshotRepository
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * v0.9.6 — Athletic RHR (sleep-window resting heart rate) trend over time.
 *
 * Data source: [RhrSnapshotRepository] daily snapshots saved at Bio Lab
 * compute time via the v0.9.1 disk-first persistence architecture. Reads
 * disk only — HC is never queried for history (per
 * [[reference_snapshot_persistence_architecture]]).
 *
 * Tier bands from endurance-athlete RHR norms:
 *   40–50 bpm  Elite endurance
 *   50–60 bpm  Trained
 *   60–70 bpm  Recreational fit
 *   70+ bpm    Untrained
 *
 * Lower is better — `higherIsBetter = false` for stats coloring.
 */
@Composable
fun RhrTrendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { RhrSnapshotRepository(context) }
    var snapshots by remember { mutableStateOf<List<RhrSnapshot>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        snapshots = withContext(Dispatchers.IO) {
            // v0.9.11 — lazy backfill on first open. If no past-date RHR
            // snapshots exist, populate from HC retention (via shared sleep
            // snapshots + SleepingRhrCalculator per night). Idempotent.
            val existing = repo.listAll()
            val hasPastSnapshots = existing.any {
                it.dateIsoLocal != LocalDate.now(ZoneId.systemDefault()).toString()
            }
            if (!hasPastSnapshots) {
                runCatching {
                    ReadinessRepository(context).backfillRhrSnapshots(
                        rhrRepo = repo,
                        sleepRepo = SleepSnapshotRepository(context),
                        days = 30,
                    )
                }
                return@withContext repo.listAll()
            }
            existing
        }
        loading = false
    }

    val zone = ZoneId.systemDefault()
    val todayIso = LocalDate.now(zone).toString()
    // v0.9.10 — labelMs anchored to local-noon of dateIsoLocal so backfilled
    // snapshots position correctly on the chart x-axis. Pre-fix used
    // computedAtMs which clusters all backfill writes at "now".
    val points = snapshots.map { snap ->
        TrendPoint(
            labelMs = dateAnchorMs(snap.dateIsoLocal, zone),
            y = snap.medianBpm.toFloat(),
            isToday = snap.dateIsoLocal == todayIso,
        )
    }

    val yMin = (points.minOfOrNull { it.y } ?: 40f).coerceAtMost(40f) - 5f
    val yMax = (points.maxOfOrNull { it.y } ?: 70f).coerceAtLeast(70f) + 5f

    TrendShell(
        spec = TrendSpec(
            eyebrow = "ATHLETIC RHR TREND",
            title = "Sleep-window resting heart rate",
            intro = "One reading per night, median across sleep nights captured by " +
                "strap or band. Lower RHR = stronger aerobic adaptation. Rising " +
                "RHR (3+ days above baseline) is a classic early illness / over-" +
                "reach warning. Read trend, not single points.",
            points = points,
            yMin = yMin.coerceAtLeast(30f),
            yMax = yMax.coerceAtMost(100f),
            yTicks = listOf(50f, 60f, 70f),
            tierBands = listOf(
                TierBand(40f, 50f, UrujZone2, "Elite"),
                TierBand(50f, 60f, UrujZone2, "Trained"),
                TierBand(60f, 70f, UrujZone3, "Recreational"),
                TierBand(70f, yMax.coerceAtMost(100f), UrujZone5, "Untrained"),
            ),
            lineColor = UrujAccent,
            valueFormatter = { "${"%.0f".format(it)} bpm" },
            detailFormatter = { p ->
                val s = snapshots.firstOrNull { it.computedAtMs == p.labelMs }
                if (s != null) "${s.nightsContributing}n median · ${s.mostRecentNightSource.lowercase()}"
                else ""
            },
            higherIsBetter = false,
            emptyTitle = "No Athletic RHR readings yet",
            emptyBody = "URUJ saves one Athletic RHR reading per day at Bio Lab " +
                "open. Wear the strap or band overnight + open Bio Lab tomorrow " +
                "morning to populate. Disk-persisted forever — won't roll off " +
                "with HC's 30-day window.",
            methodologyFootnote = "RHR = lowest sustained 5-minute mean HR inside " +
                "the longest stable sleep window (Garmin-style sustained statistic). " +
                "Before v0.9.83 this was the single lowest BEAT of the night, which " +
                "is a breath-cycle trough rather than a heart rate and read 6-8 bpm " +
                "low; points either side of that date are not directly comparable. " +
                "Corrupt nights are rejected outright, not averaged in. Methodology " +
                "${RhrSnapshotRepository.METHODOLOGY_VERSION}. Endurance-athlete " +
                "norms: 40-50 elite, 50-60 trained, 60-70 recreational, 70+ " +
                "untrained. Every dot is persisted to URUJ's local disk — no " +
                "data lost when HC's 30-day window rolls off. Today's reading " +
                "renders as a brighter dot than historical readings.",
            loading = loading,
        ),
        onBack = onBack,
    )
}
