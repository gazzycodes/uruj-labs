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
import com.uruj.ui.theme.UrujZone1
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone4
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * v0.7.4 — overnight HRV trend.
 *
 * v0.9.9 — refactored to disk-first via SleepSnapshotRepository (kills
 * the ~90 HC reads per render the original version did).
 *
 * v0.9.67 — switched data source from re-computing via
 * [ContinuousBiometricRepository.computeHrvForWindow] (stage-blind path,
 * 94-window legacy method) to reading [HrvSnapshotRepository.listAll()]
 * disk snapshots. This is the canonical pattern every other trend screen
 * (LfHfTrendScreen, DfaAlpha1TrendScreen, RhrTrendScreen, Vo2TrendScreen,
 * TsbTrendScreen) already uses, but HRV was the holdout still re-computing.
 *
 * Closes presentation-layer inconsistency 2026-05-30:
 *   Bio Lab Autonomic card + Readiness HRV row showed 14.1 ms (stage-aware
 *   sliding 50% overlap, 188 windows) from the saved snapshot. The trend
 *   chart called computeHrvForWindow() WITHOUT stages → stage-blind legacy
 *   path → 94 windows → 13.8 ms for the SAME night. Three surfaces, two
 *   numbers — violates [[reference_snapshot_persistence_architecture]] +
 *   [[reference_lab_grade_architecture_rules]] Rule 4.
 *
 * After this fix all four HRV surfaces (Readiness row, Bio Lab card, RMSSD
 * trend, LF/HF trend, DFA α1 trend) read from the same `HrvSnapshot.rmssdMs`
 * field. One number per night, persisted once at Bio Lab compute time.
 */
@Composable
fun HrvTrendScreen(onBack: () -> Unit) {
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
        val rmssd = snap.rmssdMs ?: return@mapNotNull null
        TrendPoint(
            labelMs = dateAnchorMs(snap.dateIsoLocal, zone),
            y = rmssd,
            isToday = snap.dateIsoLocal == todayIso,
        )
    }
    val rmssdMax = (points.maxOfOrNull { it.y } ?: 50f).coerceAtLeast(50f) + 10f

    TrendShell(
        spec = TrendSpec(
            eyebrow = "OVERNIGHT HRV TREND",
            title = "Recovery curve",
            intro = "One reading per night, sliced from Samsung's actual sleep " +
                "window — same source the Bio Lab Autonomic card uses. Higher = " +
                "more parasympathetic recovery. Trend across days matters more " +
                "than any single value.",
            points = points,
            yMin = 0f,
            yMax = rmssdMax,
            yTicks = listOf(20f, 30f, 50f, 80f).filter { it <= rmssdMax },
            tierBands = listOf(
                TierBand(80f, rmssdMax, UrujZone1, "Elite"),
                TierBand(50f, 80f, UrujZone2, "Trained"),
                TierBand(30f, 50f, UrujZone3, "Average"),
                TierBand(20f, 30f, UrujZone4, "Below avg"),
                TierBand(0f, 20f, UrujZone5, "Suppressed"),
            ),
            lineColor = UrujAccent,
            valueFormatter = { "${"%.1f".format(it)} ms" },
            detailFormatter = { p ->
                val s = snapshots.firstOrNull { dateAnchorMs(it.dateIsoLocal, zone) == p.labelMs }
                "${s?.windowCount ?: 0} windows"
            },
            higherIsBetter = true,
            emptyTitle = "No overnight readings yet",
            emptyBody = "Wear the strap to sleep with Samsung Health tracking sleep " +
                "AND the 24/7 monitoring service enabled. First reading appears " +
                "tomorrow morning after Samsung writes the sleep record + 24/7 NDJSON " +
                "captures the window. HRV snapshots persist to disk forever, so this " +
                "trend chart builds without further compute.",
            methodologyFootnote = "Each point = one night's RMSSD from the saved " +
                "HrvSnapshot — same value the Bio Lab Autonomic card + Readiness HRV " +
                "row read. v0.9.48+ stage-aware sliding 50%-overlap windowing on BLE " +
                "chest strap RR intervals, NATURAL breathing during the actual " +
                "Samsung-detected sleep window (awake periods excluded). Methodology " +
                "${HrvSnapshotRepository.METHODOLOGY_VERSION}. Tier bands: Plews et " +
                "al. + Shaffer & Ginsberg 2017 athletic norms.",
            loading = loading,
        ),
        onBack = onBack,
    )
}
