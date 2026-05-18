package com.uruj.ui.trend

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.uruj.data.ContinuousBiometricRepository
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujZone1
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone4
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HrvTrendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { ContinuousBiometricRepository(context) }
    var history by remember {
        mutableStateOf<List<ContinuousBiometricRepository.DailyHrv>?>(null)
    }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        history = withContext(Dispatchers.IO) { repo.dailyOvernightHrvHistory(90) }
        loading = false
    }

    val points = history?.sortedBy { it.date }?.map {
        TrendPoint(
            labelMs = it.date.atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli(),
            y = it.hrv.rmssdMs,
        )
    } ?: emptyList()
    val rmssdMax = (points.maxOfOrNull { it.y } ?: 50f).coerceAtLeast(50f) + 10f

    TrendShell(
        spec = TrendSpec(
            eyebrow = "OVERNIGHT HRV TREND",
            title = "Recovery curve",
            intro = "One reading per night. Higher = more parasympathetic recovery. " +
                "Trend across days matters more than any single value — chronic " +
                "drops below your baseline = recovery debt, sustained climbs = " +
                "training adaptation taking hold.",
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
                val entry = history?.firstOrNull { d ->
                    d.date.atStartOfDay(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli() == p.labelMs
                }
                "${entry?.hrv?.windowCount ?: 0} windows"
            },
            higherIsBetter = true,
            emptyTitle = "No overnight readings yet",
            emptyBody = "Wear the strap to sleep and 24/7 monitoring will capture " +
                "an overnight HRV reading by morning. Day 1 will show one dot here. " +
                "After 7 nights, Readiness switches from absolute-tier to " +
                "ratio-vs-YOUR-baseline scoring for catching subtle changes.",
            methodologyFootnote = "Each point = one night's median-of-5-min-windows " +
                "RMSSD from the BLE chest strap, natural breathing during sleep " +
                "(NOT a paced morning reading — see Bio Lab Autonomic card for the " +
                "paced-vs-natural breathing distinction). Tier bands from Plews et " +
                "al. + Shaffer & Ginsberg 2017 athletic norms.",
            loading = loading,
        ),
        onBack = onBack,
    )
}
