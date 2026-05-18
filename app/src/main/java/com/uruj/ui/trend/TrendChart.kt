package com.uruj.ui.trend

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
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
            // Reserve space: left for Y-axis labels, bottom for X-axis labels,
            // right for tier-band labels.
            val padLeft = 40f
            val padRight = if (tierBands.isNotEmpty()) 64f else 12f
            val padTop = 8f
            val padBottom = 28f
            val plotLeft = padLeft
            val plotRight = size.width - padRight
            val plotTop = padTop
            val plotBottom = size.height - padBottom
            val plotW = plotRight - plotLeft
            val plotH = plotBottom - plotTop

            if (plotW <= 0f || plotH <= 0f) return@Canvas

            // ── 1) Tier bands (background) ──────────────────────────────────
            for (band in tierBands) {
                val top = yToPixel(band.yMax, yMin, yMax, plotTop, plotH)
                val bottom = yToPixel(band.yMin, yMin, yMax, plotTop, plotH)
                drawRect(
                    color = band.color.copy(alpha = 0.15f),
                    topLeft = Offset(plotLeft, top),
                    size = Size(plotW, (bottom - top).coerceAtLeast(0f)),
                )
                // Tier label on right edge
                drawText(
                    textMeasurer = textMeasurer,
                    text = band.label,
                    style = TextStyle(
                        color = band.color.copy(alpha = 0.9f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    topLeft = Offset(
                        x = plotRight + 4f,
                        y = ((top + bottom) / 2f - 6f).coerceIn(plotTop, plotBottom - 12f),
                    ),
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
                // Outer glow ring
                drawCircle(
                    color = lineColor.copy(alpha = 0.25f),
                    radius = 9f,
                    center = Offset(x, y),
                )
                // Inner solid dot
                drawCircle(color = lineColor, radius = 5f, center = Offset(x, y))
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
)

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
