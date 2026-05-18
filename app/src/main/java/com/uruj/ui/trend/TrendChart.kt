package com.uruj.ui.trend

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.uruj.ui.theme.UrujMuted

/**
 * v0.7.3 — minimal lab-grade line chart on Compose Canvas. No external chart
 * library, no animations, no fluff. Just the data + tier-color bands + axis
 * labels. Matches URUJ's transparency aesthetic: every pixel is yours to audit.
 *
 * Designed for ≤90 data points (one per day). For higher density (e.g.
 * per-ride or per-sample), needs sub-sampling first.
 *
 * Tier bands optional — when supplied, the chart draws horizontal color
 * stripes behind the line at each tier's Y range. Helps the rider see at
 * a glance "I'm in the trained tier" without reading axis numbers.
 */
@Composable
fun TrendChart(
    points: List<TrendPoint>,
    yMin: Float,
    yMax: Float,
    yTicks: List<Float>,
    tierBands: List<TierBand> = emptyList(),
    lineColor: Color,
    height: androidx.compose.ui.unit.Dp = 160.dp,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(start = 36.dp, end = 8.dp, top = 8.dp, bottom = 24.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            if (points.isEmpty()) return@Canvas
            val w = size.width
            val h = size.height

            // Draw tier bands first (background)
            for (band in tierBands) {
                val top = yToPixel(band.yMax, yMin, yMax, h)
                val bottom = yToPixel(band.yMin, yMin, yMax, h)
                drawRect(
                    color = band.color.copy(alpha = 0.08f),
                    topLeft = Offset(0f, top),
                    size = Size(w, (bottom - top).coerceAtLeast(0f)),
                )
            }

            // Y-axis tick lines (dashed-style done via short segments — keep simple,
            // just light horizontal guides at each tick)
            for (tick in yTicks) {
                val y = yToPixel(tick, yMin, yMax, h)
                drawLine(
                    color = UrujMuted.copy(alpha = 0.15f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f,
                )
            }

            // Line + dots — connect consecutive points with straight segments
            if (points.size > 1) {
                val path = Path()
                points.forEachIndexed { i, p ->
                    val x = xToPixel(i, points.size, w)
                    val y = yToPixel(p.y, yMin, yMax, h)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = lineColor, style = Stroke(width = 3f))
            }
            points.forEachIndexed { i, p ->
                val x = xToPixel(i, points.size, w)
                val y = yToPixel(p.y, yMin, yMax, h)
                drawCircle(color = lineColor, radius = 5f, center = Offset(x, y))
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

private fun DrawScope.yToPixel(value: Float, yMin: Float, yMax: Float, h: Float): Float {
    val frac = ((value - yMin) / (yMax - yMin)).coerceIn(0f, 1f)
    return h - frac * h
}

private fun DrawScope.xToPixel(index: Int, total: Int, w: Float): Float {
    if (total <= 1) return w / 2f
    return (index.toFloat() / (total - 1)) * w
}
