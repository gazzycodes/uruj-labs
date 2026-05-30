package com.uruj.ui.trend

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.uruj.data.HrvSnapshot
import com.uruj.data.HrvSnapshotRepository
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone1
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * v0.9.66 — Per-stage RMSSD trend over time (#229).
 *
 * Visualizes deep / REM / light sleep RMSSD as three overlaid line series
 * on a shared axis. The killer feature for chronic-recovery riders:
 * surfaces the REM-vs-DEEP relationship as a TIME SERIES rather than a
 * single-night snapshot. Healthy adult pattern is DEEP > REM > LIGHT.
 * Chronic non-functional overreach typically INVERTS this (REM > DEEP);
 * recovery is signaled by the inversion resolving back to the healthy
 * order. Without a multi-line trend, the rider sees today's three numbers
 * but can't see the relationship evolving.
 *
 * Data path: reads HrvSnapshot.deepRmssdMs / remRmssdMs / lightRmssdMs
 * (persisted since v0.9.48 stage-aware-sliding methodology). Disk-only
 * read at render — no HC reads, no NDJSON walk, no compute. Trivial perf.
 *
 * Color language:
 *   - DEEP  → UrujZone1 (blue, deep) — should be the highest of the three
 *   - REM   → UrujZone3 (amber) — should be MIDDLE; if it's highest, that's
 *             the chronic-overreach inversion marker
 *   - LIGHT → UrujZone2 (green) — usually the lowest, occasional middle
 *
 * Future: v0.9.70+ overlay sleep hours per night underneath to correlate
 * stage variability with total sleep duration (long-arc marker tier-5).
 */
@Composable
fun PerStageRmssdTrendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { HrvSnapshotRepository(context) }
    var snapshots by remember { mutableStateOf<List<HrvSnapshot>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        snapshots = withContext(Dispatchers.IO) {
            repo.listAll()
                // Keep only snapshots with at least one stage value present.
                // Older snapshots (pre-v0.9.48) had no stage breakdown so they
                // contribute nothing meaningful and would just create gaps in
                // the chart's date axis.
                .filter { it.deepRmssdMs != null || it.remRmssdMs != null || it.lightRmssdMs != null }
                .sortedBy { it.dateIsoLocal }
        }
        loading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "PER-STAGE RMSSD TREND",
                        color = UrujMuted,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 3.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onBack) {
                        Text(
                            "CLOSE",
                            color = UrujAccent,
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            letterSpacing = 1.5.sp,
                        )
                    }
                }
                Text(
                    "Deep · REM · Light",
                    color = UrujText,
                    fontWeight = FontWeight.Black,
                    fontSize = 32.sp,
                )
                Text(
                    "Three-line view of how each sleep stage contributes to overnight " +
                        "parasympathetic tone. The killer chronic-recovery marker is the " +
                        "DEEP vs REM relationship: healthy pattern is DEEP > REM > LIGHT. " +
                        "Inversion (REM > DEEP) is a chronic-overreach signal. Recovery is " +
                        "signaled when the order returns to DEEP > REM.",
                    color = UrujMuted, fontSize = 12.sp,
                )
            }

            if (loading) {
                item("loading") {
                    Text("Loading...", color = UrujMuted, fontSize = 13.sp)
                }
                return@LazyColumn
            }

            if (snapshots.isEmpty()) {
                item("empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(UrujSurfaceHigh.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(16.dp),
                    ) {
                        Column {
                            Text(
                                "No per-stage data yet",
                                color = UrujText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Per-stage RMSSD requires v0.9.48 stage-aware methodology " +
                                    "AND a sleep window with deep + REM + light data from " +
                                    "Samsung Health. Wear band + strap overnight, then open " +
                                    "Bio Lab tomorrow to populate. Disk-persisted forever.",
                                color = UrujMuted, fontSize = 12.sp,
                            )
                        }
                    }
                }
                return@LazyColumn
            }

            item("legend") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StageLegendDot("Deep", UrujZone1)
                    StageLegendDot("REM", UrujZone3)
                    StageLegendDot("Light", UrujZone2)
                }
            }

            item("today") {
                val today = snapshots.lastOrNull()
                if (today != null) {
                    val deep = today.deepRmssdMs
                    val rem = today.remRmssdMs
                    val light = today.lightRmssdMs
                    val inverted = deep != null && rem != null && rem > deep
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(UrujSurfaceHigh.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(14.dp),
                    ) {
                        Column {
                            Text(
                                "TODAY · ${today.dateIsoLocal}",
                                color = UrujMuted,
                                fontWeight = FontWeight.Black,
                                fontSize = 9.sp,
                                letterSpacing = 1.5.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            StageRow("DEEP", deep, UrujZone1)
                            StageRow("REM", rem, UrujZone3)
                            StageRow("LIGHT", light, UrujZone2)
                            if (inverted) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "REM > DEEP · chronic-overreach inversion present",
                                    color = UrujZone3,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            } else if (deep != null && rem != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "DEEP > REM · healthy order ✓",
                                    color = UrujZone2,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }

            item("chart") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .background(UrujSurfaceHigh.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    MultiLineChart(snapshots)
                }
            }

            item("readings_header") {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "READINGS",
                        color = UrujMuted,
                        fontWeight = FontWeight.Black,
                        fontSize = 9.sp,
                        letterSpacing = 1.5.sp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("Date", modifier = Modifier.weight(1.4f), color = UrujMuted, fontSize = 10.sp)
                    Text("Deep", modifier = Modifier.weight(1f), color = UrujZone1, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("REM", modifier = Modifier.weight(1f), color = UrujZone3, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("Light", modifier = Modifier.weight(1f), color = UrujZone2, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            items(snapshots.size) { idx ->
                // Render newest-first so the most recent night sits at the top.
                val snap = snapshots[snapshots.size - 1 - idx]
                ReadingRow(snap)
            }

            item("methodology") {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Per-stage RMSSD = RMSSD computed only on 5-min windows " +
                        "dominated by that stage (≥50% sleep-stage segment overlap). " +
                        "Requires ≥3 valid windows per stage (~15 min, Plews convention). " +
                        "Stages from Samsung Health SleepStageRecord. Methodology " +
                        "${HrvSnapshotRepository.METHODOLOGY_VERSION}. Reference: " +
                        "deep sleep should have HIGHEST RMSSD (parasympathetic floor); " +
                        "REM tends to lower due to phasic sympathetic bursts; light is " +
                        "usually lowest. Chronic non-functional overreach inverts the " +
                        "DEEP-REM order — when DEEP returns higher than REM, the body's " +
                        "vagal tone restoration is on track.",
                    color = UrujMuted, fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun StageLegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = UrujText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StageRow(label: String, value: Float?, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = UrujMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            value?.let { "%.1f ms".format(it) } ?: "—",
            color = if (value == null) UrujMuted else UrujText,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun ReadingRow(snap: HrvSnapshot) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            snap.dateIsoLocal,
            modifier = Modifier.weight(1.4f),
            color = UrujText,
            fontSize = 12.sp,
        )
        Text(
            snap.deepRmssdMs?.let { "%.1f".format(it) } ?: "—",
            modifier = Modifier.weight(1f),
            color = if (snap.deepRmssdMs == null) UrujMuted else UrujText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            snap.remRmssdMs?.let { "%.1f".format(it) } ?: "—",
            modifier = Modifier.weight(1f),
            color = if (snap.remRmssdMs == null) UrujMuted else UrujText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            snap.lightRmssdMs?.let { "%.1f".format(it) } ?: "—",
            modifier = Modifier.weight(1f),
            color = if (snap.lightRmssdMs == null) UrujMuted else UrujText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Three-line Canvas chart. X axis is index-based (one slot per snapshot,
 * evenly spaced — date gaps are not preserved to keep the chart readable
 * even after a missing night). Y axis is RMSSD in ms, range derived from
 * actual min/max across all three series with 2ms padding.
 */
@Composable
private fun MultiLineChart(snapshots: List<HrvSnapshot>) {
    val all = snapshots.flatMap {
        listOfNotNull(it.deepRmssdMs, it.remRmssdMs, it.lightRmssdMs)
    }
    if (all.isEmpty()) return
    val yMin = ((all.minOrNull() ?: 0f) - 2f).coerceAtLeast(0f)
    val yMax = (all.maxOrNull() ?: 50f) + 2f
    val yRange = (yMax - yMin).coerceAtLeast(1f)
    val n = snapshots.size

    val deepColor = UrujZone1
    val remColor = UrujZone3
    val lightColor = UrujZone2
    val gridColor = UrujMuted.copy(alpha = 0.3f)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val left = 28f
        val right = w - 8f
        val top = 8f
        val bottom = h - 16f
        val plotW = right - left
        val plotH = bottom - top

        fun xAt(idx: Int): Float =
            if (n <= 1) left + plotW / 2f
            else left + (idx.toFloat() / (n - 1).toFloat()) * plotW

        fun yAt(v: Float): Float = bottom - ((v - yMin) / yRange) * plotH

        // Background grid (3 horizontal lines at quartiles).
        for (i in 1..3) {
            val gy = top + (i / 4f) * plotH
            drawLine(
                color = gridColor,
                start = Offset(left, gy),
                end = Offset(right, gy),
                strokeWidth = 1f,
            )
        }
        // Axis baseline
        drawLine(
            color = UrujMuted,
            start = Offset(left, bottom),
            end = Offset(right, bottom),
            strokeWidth = 1.5f,
        )

        fun drawSeries(picker: (HrvSnapshot) -> Float?, color: Color) {
            var prev: Offset? = null
            for ((idx, snap) in snapshots.withIndex()) {
                val v = picker(snap)
                if (v == null) {
                    // Break the line on missing data — start a fresh segment.
                    prev = null
                    continue
                }
                val curr = Offset(xAt(idx), yAt(v))
                prev?.let { p ->
                    drawLine(
                        color = color,
                        start = p,
                        end = curr,
                        strokeWidth = 3f,
                    )
                }
                // Dot for each data point
                drawCircle(
                    color = color,
                    radius = 4f,
                    center = curr,
                )
                prev = curr
            }
        }

        // Order: Light at back, REM middle, Deep on top (most-important visible)
        drawSeries({ it.lightRmssdMs }, lightColor)
        drawSeries({ it.remRmssdMs }, remColor)
        drawSeries({ it.deepRmssdMs }, deepColor)
    }
}
