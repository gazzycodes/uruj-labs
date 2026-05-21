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
 * v0.9.28 — LF/HF ratio trend over time. One reading per night from
 * [HrvSnapshotRepository] (v0.9.27 persistence). Disk-only reads per
 * [[reference_snapshot_persistence_architecture]].
 *
 * Per Heathers 2014 / Hayano 2019 the LF/HF "sympatho-vagal balance"
 * mechanistic claim is partially debunked, but the number remains a
 * useful empirical stress index when read as a TREND across days
 * (which is exactly what this screen is for).
 *
 * Tier bands:
 *   <1.0   → Parasympathetic dominant (deep recovery)
 *   1.0–2.0 → Balanced
 *   2.0–4.0 → Sympathetic dominant (stress / fatigue / training load)
 *   >4.0   → High sympathetic (overload / acute stress / alcohol)
 *
 * Lower is better (recovered state).
 */
@Composable
fun LfHfTrendScreen(onBack: () -> Unit) {
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
        val ratio = snap.lfHfRatio ?: return@mapNotNull null
        TrendPoint(
            labelMs = dateAnchorMs(snap.dateIsoLocal, zone),
            y = ratio,
            isToday = snap.dateIsoLocal == todayIso,
        )
    }

    val yMax = (points.maxOfOrNull { it.y } ?: 4f).coerceAtLeast(4f) + 1f
    val effectiveYMax = yMax.coerceAtMost(30f)

    TrendShell(
        spec = TrendSpec(
            eyebrow = "LF / HF RATIO TREND",
            title = "Autonomic balance — sympatho-vagal index",
            intro = "One reading per overnight HRV window. Lower = parasympathetic " +
                "dominant (deep recovery). Higher = sympathetic dominant (stress / " +
                "fatigue / acute alcohol / overload). The MECHANISTIC interpretation " +
                "is partially debunked (Heathers 2014) — read this as an empirical " +
                "stress index across days, not as a clean sympathetic/parasympathetic " +
                "split. Look at the TREND, not single points.",
            points = points,
            yMin = 0f,
            yMax = effectiveYMax,
            yTicks = listOf(1f, 2f, 4f),
            tierBands = listOf(
                TierBand(0f, 1f, UrujZone2, "Parasympathetic"),
                TierBand(1f, 2f, UrujZone3, "Balanced"),
                TierBand(2f, 4f, UrujZone4, "Sympathetic"),
                TierBand(4f, effectiveYMax, UrujZone5, "High sympathetic"),
            ),
            lineColor = UrujAccent,
            valueFormatter = { "%.2f".format(it) },
            detailFormatter = { p ->
                val s = snapshots.firstOrNull { dateAnchorMs(it.dateIsoLocal, zone) == p.labelMs }
                if (s != null) {
                    val lf = s.lfMs2?.let { "%.0f".format(it) } ?: "—"
                    val hf = s.hfMs2?.let { "%.0f".format(it) } ?: "—"
                    "LF $lf · HF $hf · ${s.windowCount}w"
                } else ""
            },
            higherIsBetter = false,
            emptyTitle = "No LF/HF readings yet",
            emptyBody = "URUJ saves one LF/HF reading per night at Bio Lab open. " +
                "Wear the strap overnight + open Bio Lab tomorrow morning to " +
                "populate. Disk-persisted forever (v0.9.27 snapshot architecture).",
            methodologyFootnote = "LF (0.04-0.15 Hz) / HF (0.15-0.4 Hz) ratio via " +
                "Lomb-Scargle periodogram on non-uniform RR series (no interpolation " +
                "artifacts). Methodology ${HrvSnapshotRepository.METHODOLOGY_VERSION}. " +
                "Caveat: Heathers 2014 + Hayano 2019 — LF contains BOTH sympathetic " +
                "AND parasympathetic activity via baroreflex. The number is empirically " +
                "useful as a stress index but not a clean autonomic split. Read trend " +
                "patterns (rising over days = building stress) over single values.",
            loading = loading,
        ),
        onBack = onBack,
    )
}
