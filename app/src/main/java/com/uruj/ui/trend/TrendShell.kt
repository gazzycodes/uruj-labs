package com.uruj.ui.trend

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone4
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.7.4 — generic shell for any metric trend screen. The five v0.7.x metrics
 * (HRV, HRR1, CAR amplitude, Orthostatic HR delta, Orthostatic RMSSD ratio)
 * all share the same UI skeleton: header → range chips → chart → stats →
 * readings → methodology. Only the data + tier bands + chart accent differ.
 *
 * Per-metric screens (e.g. HrvTrendScreen) provide the TrendSpec; this shell
 * draws everything.
 */
@Composable
fun TrendShell(
    spec: TrendSpec,
    onBack: () -> Unit,
) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text("← BIO LAB", color = UrujAccent, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
            }
            Text(
                spec.eyebrow,
                color = UrujAccent,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
            )
            Text(
                spec.title,
                color = UrujText,
                fontWeight = FontWeight.Black,
                fontSize = 28.sp,
            )
            Text(spec.intro, color = UrujMuted, fontSize = 12.sp)

            if (spec.loading) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = UrujAccent) }
            } else if (spec.points.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(UrujSurfaceHigh.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .padding(20.dp),
                ) {
                    Text(spec.emptyTitle, color = UrujText, fontWeight = FontWeight.Bold,
                        fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(spec.emptyBody, color = UrujMuted, fontSize = 12.sp)
                }
            } else {
                TrendChart(
                    points = spec.points,
                    yMin = spec.yMin,
                    yMax = spec.yMax,
                    yTicks = spec.yTicks,
                    tierBands = spec.tierBands,
                    lineColor = spec.lineColor,
                )
                // v0.7.6 — tier legend lives BELOW the chart instead of being
                // squeezed inside the right edge.
                if (spec.tierBands.isNotEmpty()) {
                    TierLegend(spec.tierBands)
                }
                StatsBlock(
                    points = spec.points,
                    valueFormatter = spec.valueFormatter,
                    higherIsBetter = spec.higherIsBetter,
                )
                ReadingsList(
                    points = spec.points,
                    valueFormatter = spec.valueFormatter,
                    detailFormatter = spec.detailFormatter,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(spec.methodologyFootnote, color = UrujMuted, fontSize = 10.sp)
        }
    }
}

/** Everything needed to render a metric's trend screen. Per-metric ViewModels
 *  build this and hand it to TrendShell. */
data class TrendSpec(
    val eyebrow: String,
    val title: String,
    val intro: String,
    val points: List<TrendPoint>,
    val yMin: Float,
    val yMax: Float,
    val yTicks: List<Float>,
    val tierBands: List<TierBand>,
    val lineColor: androidx.compose.ui.graphics.Color,
    val valueFormatter: (Float) -> String,
    val detailFormatter: ((TrendPoint) -> String)? = null,
    val higherIsBetter: Boolean = true,
    val emptyTitle: String,
    val emptyBody: String,
    val methodologyFootnote: String,
    val loading: Boolean = false,
)

@Composable
private fun StatsBlock(
    points: List<TrendPoint>,
    valueFormatter: (Float) -> String,
    higherIsBetter: Boolean,
) {
    if (points.isEmpty()) return
    val sorted = points.sortedBy { it.labelMs }
    val current = sorted.last().y
    val best = if (higherIsBetter) sorted.maxByOrNull { it.y }?.y ?: current
               else sorted.minByOrNull { it.y }?.y ?: current
    val avg7d = sorted.takeLast(7).map { it.y }.average().toFloat()
    val avg30d = sorted.takeLast(30).map { it.y }.average().toFloat()
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
            StatCell("CURRENT", valueFormatter(current))
            StatCell(if (higherIsBetter) "BEST" else "LOWEST", valueFormatter(best))
        }
        Spacer(Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell("7D AVG", valueFormatter(avg7d),
                delta = delta7d, formatter = valueFormatter, higherIsBetter = higherIsBetter)
            StatCell("30D AVG", valueFormatter(avg30d),
                delta = delta30d, formatter = valueFormatter, higherIsBetter = higherIsBetter)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StatCell(
    label: String,
    value: String,
    delta: Float? = null,
    formatter: ((Float) -> String)? = null,
    higherIsBetter: Boolean = true,
) {
    Column(modifier = Modifier.weight(1f)) {
        Text(label, color = UrujMuted, fontSize = 9.sp, letterSpacing = 1.sp)
        Text(value, color = UrujText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        if (delta != null && formatter != null) {
            val sign = if (delta >= 0) "+" else ""
            val isGood = if (higherIsBetter) delta >= 0 else delta <= 0
            val color = if (isGood) UrujZone2 else UrujZone4
            Text(
                "$sign${formatter(delta).removeSuffix(" ms").removeSuffix(" bpm")} today",
                color = color, fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun ReadingsList(
    points: List<TrendPoint>,
    valueFormatter: (Float) -> String,
    detailFormatter: ((TrendPoint) -> String)?,
) {
    if (points.isEmpty()) return
    val sorted = points.sortedByDescending { it.labelMs }
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
        val fmt = SimpleDateFormat("EEE MMM d", Locale.getDefault())
        for (p in sorted.take(14)) {
            Row(modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically) {
                Text(fmt.format(Date(p.labelMs)), color = UrujText, fontSize = 12.sp,
                    modifier = Modifier.weight(1f))
                Text(valueFormatter(p.y), color = UrujText, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
                if (detailFormatter != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(detailFormatter(p), color = UrujMuted, fontSize = 11.sp)
                }
            }
        }
        if (sorted.size > 14) {
            Text(
                "+ ${sorted.size - 14} more on disk",
                color = UrujMuted, fontSize = 10.sp,
            )
        }
    }
}
