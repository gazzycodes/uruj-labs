package com.uruj.ui.trend

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.uruj.data.BioLabRepository
import com.uruj.data.BioLabSnapshot
import com.uruj.data.HrrSample
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v0.7.4 — HRR1 (1-min HR drop post-effort) trend over time.
 *
 * Data source: BioLabSnapshot.hrr1RecentSamples (now contains ALL qualifying
 * 30d samples per v0.7.4's snapshot change). One dot per ride that hit ≥120
 * bpm peak and had ≥30 sec of post-effort HR samples available.
 *
 * Tier bands from Cole NEJM 1999 + URUJ athlete-tier overlay:
 *   ≥22 bpm  Elite range
 *   ≥18 bpm  Excellent (Cole)
 *   12-17 bpm Average (Cole)
 *   <12 bpm  Elevated CV risk (Cole)
 */
@Composable
fun Hrr1TrendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { BioLabRepository(context) }
    var snapshot by remember { mutableStateOf<BioLabSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        snapshot = withContext(Dispatchers.IO) { repo.snapshot() }
        loading = false
    }

    val samples: List<HrrSample> = snapshot?.hrr1RecentSamples ?: emptyList()
    val points = samples.sortedBy { it.endTimeMs }.map {
        TrendPoint(labelMs = it.endTimeMs, y = it.hrr1Bpm.toFloat())
    }
    val yMax = (points.maxOfOrNull { it.y } ?: 30f).coerceAtLeast(30f) + 5f

    TrendShell(
        spec = TrendSpec(
            eyebrow = "HRR1 TREND",
            title = "Recovery curve",
            intro = "One reading per qualifying ride (peak HR ≥120 bpm). Higher " +
                "drop = stronger parasympathetic reactivation post-effort. " +
                "Cole NEJM 1999 cardiology-grade cardiovascular health marker — " +
                "stronger CV mortality predictor than VO2 max alone.",
            points = points,
            yMin = 0f,
            yMax = yMax,
            yTicks = listOf(12f, 18f, 22f).filter { it <= yMax },
            tierBands = listOf(
                TierBand(22f, yMax, UrujZone2, "Elite"),
                TierBand(18f, 22f, UrujZone2, "Excellent"),
                TierBand(12f, 18f, UrujZone3, "Average"),
                TierBand(0f, 12f, UrujZone5, "Elevated CV risk"),
            ),
            lineColor = UrujAccent,
            valueFormatter = { "${"%.0f".format(it)} bpm" },
            detailFormatter = { p ->
                val s = samples.firstOrNull { it.endTimeMs == p.labelMs }
                if (s != null) "peak ${s.peakBpm}" else ""
            },
            higherIsBetter = true,
            emptyTitle = "No qualifying HRR1 readings yet",
            emptyBody = "Ride hard enough to push HR past 120 bpm, then let URUJ " +
                "see the post-effort recovery samples. Each qualifying ride " +
                "adds one dot here. The median across rides drives the Bio Lab " +
                "Heart Rate Recovery card.",
            methodologyFootnote = "HRR1 = peak HR − HR at 60s post-effort. Cole et " +
                "al. NEJM 1999 thresholds: ≥18 excellent, 12-17 average, <12 elevated " +
                "all-cause mortality risk. URUJ athletic-tier overlay: ≥22 elite. " +
                "Computed from HC HR samples within 30s-180s post-session-end. " +
                "Future: same calc from BLE NDJSON for per-second resolution.",
            loading = loading,
        ),
        onBack = onBack,
    )
}
