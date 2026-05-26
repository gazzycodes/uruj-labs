package com.uruj.ui.trend

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.uruj.data.OrthostaticTestRepository
import com.uruj.domain.OrthostaticTestResult
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujZone1
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone4
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v0.7.4 — Orthostatic test trend.
 *
 * For v0.7.4 MVP we surface ONLY the HR delta line. RMSSD ratio is interesting
 * but at low absolute RMSSD (which the user has been showing) the ratio is
 * noisy and the trend chart would mislead. v0.7.5+ when sample count grows
 * can layer a second line; for now: HR delta on its own = clear "how is my
 * autonomic flexibility tracking week-over-week" view.
 *
 * Each dot = one orthostatic test reading from /tests/orthostatic/.
 *
 * Tier bands match the orthostatic launcher card (Klivington 1995, Plews et al.):
 *   <10 bpm   Elite
 *   10-15 bpm Healthy
 *   15-25 bpm Moderate strain
 *   25-35 bpm Significant strain
 *   35+ bpm   Severe (overreaching)
 *
 * Note: lower HR delta = better here, opposite direction from HRV/HRR1/CAR.
 * higherIsBetter flag in TrendSpec handles the stat-color flip.
 */
@Composable
fun OrthostaticTrendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { OrthostaticTestRepository(context) }
    var history by remember { mutableStateOf<List<OrthostaticTestResult>?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        // v0.9.49.1 — migrate legacy snapshots to current methodology before
        // rendering so the chart never silently mixes versions.
        repo.ensureBackfilled()
        history = withContext(Dispatchers.IO) { repo.listAll() }
        loading = false
    }

    val samples = history ?: emptyList()
    val points = samples.sortedBy { it.startedAtMs }.map {
        TrendPoint(labelMs = it.startedAtMs, y = it.hrDeltaBpm)
    }
    val yMax = (points.maxOfOrNull { it.y } ?: 35f).coerceAtLeast(35f) + 5f

    TrendShell(
        spec = TrendSpec(
            eyebrow = "ORTHOSTATIC TREND",
            title = "Autonomic flexibility",
            intro = "One reading per sit→stand test. LOWER HR delta = better — your " +
                "autonomic system handles the postural change without overreacting. " +
                "Rising trend over days = autonomic strain building. Falling = " +
                "recovering. Best sampled same time of day for fair comparison.",
            points = points,
            yMin = 0f,
            yMax = yMax,
            yTicks = listOf(10f, 15f, 25f, 35f).filter { it <= yMax },
            tierBands = listOf(
                TierBand(35f, yMax, UrujZone5, "Severe"),
                TierBand(25f, 35f, UrujZone4, "Significant"),
                TierBand(15f, 25f, UrujZone3, "Moderate"),
                TierBand(10f, 15f, UrujZone2, "Healthy"),
                TierBand(0f, 10f, UrujZone1, "Elite"),
            ),
            lineColor = UrujAccent,
            valueFormatter = { "${"%.0f".format(it)} bpm" },
            detailFormatter = { p ->
                val s = samples.firstOrNull { it.startedAtMs == p.labelMs }
                if (s != null) "ratio %.2f".format(s.rmssdRatio) else ""
            },
            higherIsBetter = false,  // LOWER HR delta is BETTER for this metric
            emptyTitle = "No orthostatic readings yet",
            emptyBody = "Run the 4-min sit→stand test from the Lab Tests section " +
                "of Bio Lab. Each test adds one dot to this trend. Best at the same " +
                "time of day (morning, post-pee, before caffeine) for fair " +
                "week-over-week comparisons.",
            methodologyFootnote = "HR delta = standing mean − seated mean across the " +
                "2+2 min protocol. Tier thresholds: Klivington 1995, Plews et al. " +
                "Lower = better autonomic flexibility. NOTE: RMSSD ratio (also " +
                "computed per-test, visible in result screen + launcher card) is " +
                "noisy at low absolute RMSSD — v0.7.x.x will overlay a second line " +
                "once more samples accumulate.",
            loading = loading,
        ),
        onBack = onBack,
    )
}
