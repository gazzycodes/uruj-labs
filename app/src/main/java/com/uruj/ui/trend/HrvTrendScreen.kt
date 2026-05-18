package com.uruj.ui.trend

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uruj.data.ContinuousBiometricRepository
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone1
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone4
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.7.3 — Overnight RMSSD HRV time-series. Shows how your parasympathetic
 * recovery state evolves day over day. The whole point of URUJ keeping the
 * NDJSON on disk + computing per-night HRV: this trend is what reveals
 * meaningful patterns.
 *
 * Day 1: one dot, "need more nights" message.
 * Day 2-6: line forming, "baseline building" caveat.
 * Day 7+: stable baseline + ratio scoring kicks in.
 *
 * Tier bands behind the line use Plews et al. + Shaffer & Ginsberg 2017
 * athletic norms — visual at-a-glance "where am I sitting" without reading
 * axis numbers.
 */
@Composable
fun HrvTrendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { ContinuousBiometricRepository(context) }
    var range by remember { mutableStateOf(TrendRange.DAYS_30) }
    var rawHistory by remember { mutableStateOf<List<ContinuousBiometricRepository.DailyHrv>?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(range) {
        loading = true
        rawHistory = withContext(Dispatchers.IO) {
            repo.dailyOvernightHrvHistory(range.days)
        }
        loading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Header ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text("← BIO LAB", color = UrujAccent, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
            }
            Text(
                "OVERNIGHT HRV TREND",
                color = UrujAccent,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
            )
            Text(
                "Recovery curve",
                color = UrujText,
                fontWeight = FontWeight.Black,
                fontSize = 28.sp,
            )
            Text(
                "One reading per night. Higher = more parasympathetic recovery. " +
                    "Trend across days matters more than any single value — chronic " +
                    "drops below your baseline = recovery debt, sustained climbs = " +
                    "training adaptation taking hold.",
                color = UrujMuted, fontSize = 12.sp,
            )

            // ── Range filter chips ──
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in TrendRange.entries) {
                    RangeChip(label = r.label, selected = r == range, onClick = { range = r })
                }
            }

            // ── Chart + states ──
            val history = rawHistory
            if (loading) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = UrujAccent) }
            } else if (history.isNullOrEmpty()) {
                EmptyTrendCard()
            } else {
                val points = history
                    .sortedBy { it.date }
                    .map {
                        TrendPoint(
                            labelMs = it.date.atStartOfDay(java.time.ZoneId.systemDefault())
                                .toInstant().toEpochMilli(),
                            y = it.hrv.rmssdMs,
                        )
                    }
                val rmssdMax = (points.maxOfOrNull { it.y } ?: 50f).coerceAtLeast(50f) + 10f
                val ticks = listOf(20f, 30f, 50f, 80f)
                    .filter { it <= rmssdMax }
                TrendChart(
                    points = points,
                    yMin = 0f,
                    yMax = rmssdMax,
                    yTicks = ticks,
                    tierBands = listOf(
                        TierBand(80f, rmssdMax, UrujZone1, "Elite"),
                        TierBand(50f, 80f, UrujZone2, "Trained"),
                        TierBand(30f, 50f, UrujZone3, "Average"),
                        TierBand(20f, 30f, UrujZone4, "Below avg"),
                        TierBand(0f, 20f, UrujZone5, "Suppressed"),
                    ),
                    lineColor = UrujAccent,
                )

                StatsBlock(points = points)
                ReadingsList(history = history.sortedByDescending { it.date })
            }

            // ── Methodology footer ──
            Spacer(Modifier.height(8.dp))
            Text(
                "Each point = one night's median-of-5-min-windows RMSSD from the " +
                    "BLE chest strap, natural breathing during sleep (NOT a paced " +
                    "morning reading — see Bio Lab Autonomic card for the paced-vs-" +
                    "natural breathing distinction). Tier bands from Plews et al. + " +
                    "Shaffer & Ginsberg 2017 athletic norms.",
                color = UrujMuted, fontSize = 10.sp,
            )
        }
    }
}

private enum class TrendRange(val label: String, val days: Int) {
    DAYS_7("7d", 7),
    DAYS_30("30d", 30),
    DAYS_90("90d", 90),
}

@Composable
private fun RangeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) UrujAccent else UrujSurfaceHigh.copy(alpha = 0.5f)
    val fg = if (selected) androidx.compose.ui.graphics.Color.Black else UrujText
    TextButton(
        onClick = onClick,
        modifier = Modifier.background(bg, RoundedCornerShape(8.dp)),
    ) {
        Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

@Composable
private fun EmptyTrendCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UrujSurfaceHigh.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(20.dp),
    ) {
        Text(
            "No overnight readings yet",
            color = UrujText, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Wear the strap to sleep and 24/7 monitoring will capture an overnight " +
                "HRV reading by morning. Day 1 will show one dot here. After 7 nights, " +
                "Readiness switches from absolute-tier to ratio-vs-YOUR-baseline scoring " +
                "for catching subtle changes.",
            color = UrujMuted, fontSize = 12.sp,
        )
    }
}

@Composable
private fun StatsBlock(points: List<TrendPoint>) {
    if (points.isEmpty()) return
    val current = points.last().y
    val best = points.maxByOrNull { it.y }?.y ?: current
    val avg7d = points.takeLast(7).map { it.y }.average().toFloat()
    val avg30d = points.takeLast(30).map { it.y }.average().toFloat()
    val delta7d = current - avg7d
    val delta30d = current - avg30d
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UrujSurfaceHigh.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "STATS",
            color = UrujMuted, fontWeight = FontWeight.Black,
            fontSize = 9.sp, letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell("CURRENT", "${"%.1f".format(current)} ms")
            StatCell("BEST", "${"%.1f".format(best)} ms")
        }
        Spacer(Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell("7D AVG", "${"%.1f".format(avg7d)} ms", delta = delta7d)
            StatCell("30D AVG", "${"%.1f".format(avg30d)} ms", delta = delta30d)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StatCell(
    label: String,
    value: String,
    delta: Float? = null,
) {
    Column(modifier = Modifier.weight(1f)) {
        Text(label, color = UrujMuted, fontSize = 9.sp, letterSpacing = 1.sp)
        Text(value, color = UrujText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        if (delta != null) {
            val sign = if (delta >= 0) "+" else ""
            val color = if (delta >= 0) UrujZone2 else UrujZone4
            Text(
                "$sign${"%.1f".format(delta)} ms today",
                color = color, fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun ReadingsList(history: List<ContinuousBiometricRepository.DailyHrv>) {
    if (history.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UrujSurfaceHigh.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "READINGS",
            color = UrujMuted, fontWeight = FontWeight.Black,
            fontSize = 9.sp, letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(2.dp))
        for (entry in history.take(14)) {
            val dateStr = SimpleDateFormat("EEE MMM d", Locale.getDefault())
                .format(Date(
                    entry.date.atStartOfDay(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli(),
                ))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(dateStr, color = UrujText, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(
                    "${"%.1f".format(entry.hrv.rmssdMs)} ms",
                    color = UrujText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${entry.hrv.windowCount} windows",
                    color = UrujMuted, fontSize = 11.sp,
                )
            }
        }
        if (history.size > 14) {
            Text(
                "+ ${history.size - 14} more on disk",
                color = UrujMuted, fontSize = 10.sp,
            )
        }
    }
}
