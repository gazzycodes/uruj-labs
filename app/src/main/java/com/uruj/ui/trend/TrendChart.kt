package com.uruj.ui.trend

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.7.5 — minimal lab-grade line chart on Compose Canvas, now with axis
 * labels and time-positioned points.
 *
 * Improvements over v0.7.3 first version:
 *   - Y-axis labels drawn IN the canvas via TextMeasurer (was 36dp empty space)
 *   - X-axis labels (date range) drawn at bottom
 *   - Tier band labels on the right edge (e.g. "Trained", "Elite")
 *   - Dots positioned by TIMESTAMP not index — same-day readings cluster
 *     correctly, sparse weeks spread out correctly
 *   - Tier band alpha 0.08 → 0.15 (more visible without overwhelming the line)
 *   - Subtle 1px floor line at the chart baseline
 *
 * Designed for ≤90 data points (one per day). For higher density,
 * caller should sub-sample first.
 */
@Composable
fun TrendChart(
    points: List<TrendPoint>,
    yMin: Float,
    yMax: Float,
    yTicks: List<Float>,
    tierBands: List<TierBand> = emptyList(),
    lineColor: Color,
    yLabelFormatter: (Float) -> String = { "%.0f".format(it) },
    height: androidx.compose.ui.unit.Dp = 200.dp,
) {
    val textMeasurer = rememberTextMeasurer()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            // Reserve space: left for Y-axis labels, bottom for X-axis labels.
            // Right side now flush — tier labels moved to a legend BELOW the
            // chart (v0.7.6 — the squeezed right-margin labels were wrapping
            // mid-word with longer tier names like "Average" → "Aver" + "age").
            val padLeft = 40f
            val padRight = 12f
            val padTop = 8f
            val padBottom = 28f
            val plotLeft = padLeft
            val plotRight = size.width - padRight
            val plotTop = padTop
            val plotBottom = size.height - padBottom
            val plotW = plotRight - plotLeft
            val plotH = plotBottom - plotTop

            if (plotW <= 0f || plotH <= 0f) return@Canvas

            // ── 1) Tier bands (background only — labels in TierLegend below) ──
            for (band in tierBands) {
                val top = yToPixel(band.yMax, yMin, yMax, plotTop, plotH)
                val bottom = yToPixel(band.yMin, yMin, yMax, plotTop, plotH)
                drawRect(
                    color = band.color.copy(alpha = 0.15f),
                    topLeft = Offset(plotLeft, top),
                    size = Size(plotW, (bottom - top).coerceAtLeast(0f)),
                )
            }

            // ── 2) Y-axis tick guide lines + labels ────────────────────────
            for (tick in yTicks) {
                val y = yToPixel(tick, yMin, yMax, plotTop, plotH)
                drawLine(
                    color = UrujMuted.copy(alpha = 0.20f),
                    start = Offset(plotLeft, y),
                    end = Offset(plotRight, y),
                    strokeWidth = 1f,
                )
                drawText(
                    textMeasurer = textMeasurer,
                    text = yLabelFormatter(tick),
                    style = TextStyle(
                        color = UrujMuted,
                        fontSize = 9.sp,
                    ),
                    topLeft = Offset(x = 2f, y = y - 6f),
                )
            }

            // ── 3) X-axis baseline ─────────────────────────────────────────
            drawLine(
                color = UrujMuted.copy(alpha = 0.4f),
                start = Offset(plotLeft, plotBottom),
                end = Offset(plotRight, plotBottom),
                strokeWidth = 1f,
            )

            if (points.isEmpty()) return@Canvas

            // ── 4) Time-positioned X mapping ──────────────────────────────
            val sortedPoints = points.sortedBy { it.labelMs }
            val tMin = sortedPoints.first().labelMs
            val tMax = sortedPoints.last().labelMs
            fun xFor(t: Long): Float = if (tMax == tMin) {
                plotLeft + plotW / 2f
            } else {
                plotLeft + ((t - tMin).toFloat() / (tMax - tMin).toFloat()) * plotW
            }

            // ── 5) Line + dots ──────────────────────────────────────────────
            if (sortedPoints.size > 1) {
                val path = Path()
                sortedPoints.forEachIndexed { i, p ->
                    val x = xFor(p.labelMs)
                    val y = yToPixel(p.y, yMin, yMax, plotTop, plotH)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = lineColor, style = Stroke(width = 3f))
            }
            sortedPoints.forEach { p ->
                val x = xFor(p.labelMs)
                val y = yToPixel(p.y, yMin, yMax, plotTop, plotH)
                // v0.9.6 — saved-vs-fresh visual. Today's freshly-computed
                // reading gets a brighter ring + bigger inner dot. Historical
                // dots (disk-persisted from prior days) render at standard
                // density. Conveys "this dot is TODAY, the rest are saved
                // history" without UI clutter.
                val outerAlpha = if (p.isToday) 0.55f else 0.25f
                val outerRadius = if (p.isToday) 12f else 9f
                val innerRadius = if (p.isToday) 6.5f else 5f
                // Outer glow ring
                drawCircle(
                    color = lineColor.copy(alpha = outerAlpha),
                    radius = outerRadius,
                    center = Offset(x, y),
                )
                // Inner solid dot
                drawCircle(color = lineColor, radius = innerRadius, center = Offset(x, y))
            }

            // ── 6) X-axis labels (date range at endpoints) ─────────────────
            val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())
            val firstLabel = dateFmt.format(Date(tMin))
            val lastLabel = dateFmt.format(Date(tMax))
            // Left label (first date)
            drawText(
                textMeasurer = textMeasurer,
                text = firstLabel,
                style = TextStyle(
                    color = UrujText.copy(alpha = 0.7f),
                    fontSize = 9.sp,
                ),
                topLeft = Offset(x = plotLeft, y = plotBottom + 6f),
            )
            // Right label (last date), right-aligned approx
            if (tMin != tMax) {
                val rightLabelWidth = lastLabel.length * 5.5f
                drawText(
                    textMeasurer = textMeasurer,
                    text = lastLabel,
                    style = TextStyle(
                        color = UrujText.copy(alpha = 0.7f),
                        fontSize = 9.sp,
                    ),
                    topLeft = Offset(x = plotRight - rightLabelWidth, y = plotBottom + 6f),
                )
            }
        }
    }
}

data class TrendPoint(
    val labelMs: Long,
    val y: Float,
    /**
     * v0.9.6 — saved-vs-fresh visual indicator. When true the chart renders
     * a brighter outline + bigger inner dot so today's freshly-computed
     * reading visually stands out from disk-persisted historical readings.
     * Per [[reference_snapshot_persistence_architecture]] every dot here is
     * persisted to disk daily — this flag distinguishes "today's value just
     * landed" from "previously-saved historical value." Default false keeps
     * old call sites working.
     */
    val isToday: Boolean = false,
)

/**
 * v0.9.10 — date-anchor helper for daily-snapshot trend charts. Backfilled
 * snapshots all share near-identical `computedAtMs` (written within ms of
 * each other). Using computedAtMs for chart positioning clusters all
 * backfilled dots at "now" — wrong. Anchor to local-noon of the snapshot's
 * dateIsoLocal so each dot positions correctly on the chart x-axis +
 * readings list date label.
 */
fun dateAnchorMs(dateIsoLocal: String, zone: java.time.ZoneId): Long {
    return runCatching {
        java.time.LocalDate.parse(dateIsoLocal)
            .atTime(12, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }.getOrElse { System.currentTimeMillis() }
}

data class TierBand(
    val yMin: Float,
    val yMax: Float,
    val color: Color,
    val label: String,
)

private fun DrawScope.yToPixel(
    value: Float,
    yMin: Float,
    yMax: Float,
    plotTop: Float,
    plotH: Float,
): Float {
    val frac = ((value - yMin) / (yMax - yMin)).coerceIn(0f, 1f)
    return plotTop + (1f - frac) * plotH
}

/**
 * v0.7.6 — tier legend rendered below the chart. Color dot + full label
 * per tier. Wraps gracefully across two rows if the labels are too long
 * to fit a single row. Replaces v0.7.5's cramped right-edge-of-canvas
 * labels which were wrapping mid-word.
 *
 * Two-row chunking is intentional + deterministic: tiers in the first
 * half (top of chart) get row 1, second half get row 2. Keeps the natural
 * top-to-bottom reading order intact.
 */
@Composable
fun TierLegend(tiers: List<TierBand>) {
    if (tiers.isEmpty()) return
    val half = (tiers.size + 1) / 2
    val firstRow = tiers.take(half)
    val secondRow = tiers.drop(half)
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LegendRow(firstRow)
        if (secondRow.isNotEmpty()) LegendRow(secondRow)
    }
}

@Composable
private fun LegendRow(tiers: List<TierBand>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tiers.forEach { tier ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(tier.color, CircleShape),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    tier.label,
                    color = UrujText.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
