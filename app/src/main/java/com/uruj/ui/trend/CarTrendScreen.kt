package com.uruj.ui.trend

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.uruj.data.CarRepository
import com.uruj.domain.CarResult
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujZone1
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone4
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v0.7.4 — CAR amplitude trend over time. One reading per wake event from
 * /tests/car/YYYY-MM-DD.json cache.
 *
 * Tier bands match the CAR card amplitude tiers (Pruessner / Clow / Stalder
 * consensus adapted to HR-response proxy):
 *   <5 bpm    Blunted (chronic stress marker)
 *   5-10 bpm  Suppressed
 *   10-20 bpm Healthy ✓
 *   20-30 bpm Robust ✓
 *   30+ bpm   Exaggerated (acute stress)
 */
@Composable
fun CarTrendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { CarRepository(context) }
    var history by remember { mutableStateOf<List<CarResult>?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        // v0.9.48.8 — make sure any legacy snapshots are recomputed with the
        // rigorous quiet-window methodology BEFORE we render the chart, so
        // user never sees mixed methodology values mid-load.
        repo.ensureBackfilled()
        history = withContext(Dispatchers.IO) { repo.listAll() }
        loading = false
    }

    val samples = history ?: emptyList()
    // v0.9.48.8 — chart rigorous quiet-window amplitude when available,
    // fall back to legacy wide-window for older snapshots saved before
    // the methodology fix.
    val points = samples.sortedBy { it.sleepEndMs }.map {
        val y = it.quietWindowAmplitudeBpm ?: it.amplitudeBpm
        TrendPoint(labelMs = it.sleepEndMs, y = y)
    }
    val yMax = (points.maxOfOrNull { it.y } ?: 30f).coerceAtLeast(30f) + 5f

    TrendShell(
        spec = TrendSpec(
            eyebrow = "CAR AMPLITUDE TREND",
            title = "Morning activation",
            intro = "One reading per morning wake event. Higher amplitude = stronger " +
                "cortisol surge proxied via HR climb. Healthy adult range 10-20 bpm. " +
                "Blunted CAR (<5 bpm) sustained over days is a chronic-stress / " +
                "burnout / overtraining marker.",
            points = points,
            yMin = 0f,
            yMax = yMax,
            yTicks = listOf(5f, 10f, 20f, 30f).filter { it <= yMax },
            tierBands = listOf(
                TierBand(30f, yMax, UrujZone4, "Exaggerated"),
                TierBand(20f, 30f, UrujZone1, "Robust"),
                TierBand(10f, 20f, UrujZone2, "Healthy"),
                TierBand(5f, 10f, UrujZone3, "Suppressed"),
                TierBand(0f, 5f, UrujZone5, "Blunted"),
            ),
            lineColor = UrujAccent,
            valueFormatter = { "${"%.0f".format(it)} bpm" },
            detailFormatter = { p ->
                val s = samples.firstOrNull { it.sleepEndMs == p.labelMs }
                if (s != null) "peak ${"%.0f".format(s.latencyMinutes)} min" else ""
            },
            higherIsBetter = true,
            emptyTitle = "No CAR readings yet",
            emptyBody = "CAR computes automatically each morning ~45 min after wake, " +
                "using 24/7 NDJSON + Samsung's sleep-end timestamp. The Bio Lab " +
                "CAR card will appear when the first reading completes. Each " +
                "morning adds one dot to this trend.",
            methodologyFootnote = "v0.9.48.8: amplitude = max(5-min bin MEAN HR in " +
                "0-30 min post-wake) − last-10-min-of-sleep baseline HR. Mean-based " +
                "binning matches salivary cortisol's smooth biochemical signal more " +
                "honestly than instant peak HR (which captures post-wake activity like " +
                "walking around). Tier thresholds adapted from Pruessner 1997, Clow " +
                "2010, Stalder 2016 consensus. URUJ measures the HR/HRV signature of " +
                "the cortisol surge, not cortisol itself — strong correlation in " +
                "research, but salivary measurement is the lab-gold standard. Older " +
                "readings (before v0.9.48.8) use legacy wide-window peak.",
            loading = loading,
        ),
        onBack = onBack,
    )
}
