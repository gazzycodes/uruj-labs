package com.uruj.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uruj.power.KarvonenZonesCalculator
import com.uruj.service.LiveStateHolder
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone1
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone4
import com.uruj.ui.theme.UrujZone5

/**
 * v0.8.1 — beat-by-beat HR waveform on Compose Canvas.
 *
 * Renders the most recent beats' BPM values as a polyline plus filled area
 * underneath (cardiac-strip aesthetic). Optional zone bands draw thin
 * horizontal lines at each Karvonen zone threshold so the rider can see at
 * a glance which zone they're currently in (and trending toward).
 *
 * Designed to be reusable: compact 60-sec window for HUD inline, or wider
 * 5-min window for the dedicated Live tab. Just pass different `windowMs`.
 *
 * @param beats most-recent ring buffer from `LiveStateHolder.State.recentBeats`
 * @param windowMs how far back to plot (e.g. 60_000 = last minute)
 * @param zones optional Karvonen zones — when present, draws zone band lines
 *   and color-shades the line by current zone
 * @param yMinBpm chart Y-axis minimum (default 40)
 * @param yMaxBpm chart Y-axis maximum (default 200)
 */
@Composable
fun HrWaveform(
    beats: List<LiveStateHolder.Beat>,
    windowMs: Long,
    zones: KarvonenZonesCalculator.Result? = null,
    height: Dp = 120.dp,
    yMinBpm: Int = 40,
    yMaxBpm: Int = 200,
    showYAxisLabels: Boolean = true,
    showCurrentBpm: Boolean = true,
) {
    val textMeasurer = rememberTextMeasurer()
    val nowMs = remember(beats.lastOrNull()?.timestampMs) { System.currentTimeMillis() }
    val cutoffMs = (beats.lastOrNull()?.timestampMs ?: nowMs) - windowMs
    val visibleBeats = beats.filter { it.timestampMs >= cutoffMs }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(10.dp))
            .background(UrujSurface),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            val padLeft = if (showYAxisLabels) 32f else 8f
            val padRight = 8f
            val padTop = 12f
            val padBottom = 12f
            val plotLeft = padLeft
            val plotRight = size.width - padRight
            val plotTop = padTop
            val plotBottom = size.height - padBottom
            val plotW = plotRight - plotLeft
            val plotH = plotBottom - plotTop
            if (plotW <= 0f || plotH <= 0f) return@Canvas

            fun yFor(bpm: Int): Float {
                val clamped = bpm.coerceIn(yMinBpm, yMaxBpm).toFloat()
                val frac = (clamped - yMinBpm) / (yMaxBpm - yMinBpm).toFloat()
                return plotBottom - frac * plotH
            }
            fun xFor(t: Long): Float {
                val tMin = (beats.firstOrNull()?.timestampMs ?: t) - 1L
                val tMax = beats.lastOrNull()?.timestampMs ?: t
                val span = (tMax - cutoffMs).coerceAtLeast(1L)
                val pos = (t - cutoffMs).coerceAtLeast(0L)
                return plotLeft + (pos.toFloat() / span.toFloat()) * plotW
            }

            // ── 1) Zone bands (thin tinted horizontal lines) ──
            if (zones != null) {
                for (zone in zones.zones) {
                    val color = zoneColor(zone.number)
                    val yLow = yFor(zone.lowerBpm)
                    val yHigh = yFor(zone.upperBpm)
                    drawRect(
                        color = color.copy(alpha = 0.10f),
                        topLeft = Offset(plotLeft, yHigh.coerceAtMost(yLow)),
                        size = androidx.compose.ui.geometry.Size(
                            plotW,
                            kotlin.math.abs(yLow - yHigh),
                        ),
                    )
                }
            }

            // ── 2) Y-axis labels ──
            if (showYAxisLabels) {
                val ticks = if (zones != null) {
                    listOf(zones.hrRest, zones.zones[1].lowerBpm, zones.zones[3].lowerBpm, zones.hrMax)
                } else {
                    listOf(60, 100, 140, 180)
                }
                for (tick in ticks) {
                    val y = yFor(tick)
                    drawText(
                        textMeasurer = textMeasurer,
                        text = tick.toString(),
                        style = TextStyle(color = UrujMuted, fontSize = 9.sp),
                        topLeft = Offset(2f, y - 6f),
                    )
                }
            }

            // ── 3) Waveform line + filled area below ──
            if (visibleBeats.size >= 2) {
                val accent = currentBeatColor(visibleBeats.last().bpm, zones)
                val areaPath = Path()
                val linePath = Path()
                var first = true
                for (b in visibleBeats) {
                    val x = xFor(b.timestampMs)
                    val y = yFor(b.bpm)
                    if (first) {
                        areaPath.moveTo(x, plotBottom)
                        areaPath.lineTo(x, y)
                        linePath.moveTo(x, y)
                        first = false
                    } else {
                        areaPath.lineTo(x, y)
                        linePath.lineTo(x, y)
                    }
                }
                // Close the area down to the baseline
                val lastX = xFor(visibleBeats.last().timestampMs)
                areaPath.lineTo(lastX, plotBottom)
                areaPath.close()
                // Filled gradient area under the line
                drawPath(
                    path = areaPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(accent.copy(alpha = 0.40f), accent.copy(alpha = 0.0f)),
                        startY = plotTop,
                        endY = plotBottom,
                    ),
                )
                // Crisp line on top
                drawPath(
                    path = linePath,
                    color = accent,
                    style = Stroke(width = 3f),
                )
                // Pulse dot at the most recent beat
                val lastY = yFor(visibleBeats.last().bpm)
                drawCircle(
                    color = accent.copy(alpha = 0.30f),
                    radius = 10f,
                    center = Offset(lastX, lastY),
                )
                drawCircle(color = accent, radius = 5f, center = Offset(lastX, lastY))

                // v0.8.3 — current BPM in-canvas text removed. It wrapped
                // vertically at the right edge ("7" stacked above "3") and
                // is redundant with the Hero BPM card directly above the
                // waveform. The pulse dot already conveys "current beat
                // here." Cleaner without the duplicate.
            } else {
                // No beats yet → placeholder text
                drawText(
                    textMeasurer = textMeasurer,
                    text = "Waiting for live HR…",
                    style = TextStyle(color = UrujMuted, fontSize = 10.sp),
                    topLeft = Offset(plotLeft + 8f, plotTop + plotH / 2f - 6f),
                )
            }
        }
    }
}

private fun zoneColor(zone: Int): Color = when (zone) {
    1 -> UrujZone1
    2 -> UrujZone2
    3 -> UrujZone3
    4 -> UrujZone4
    5 -> UrujZone5
    else -> UrujText
}

private fun currentBeatColor(bpm: Int, zones: KarvonenZonesCalculator.Result?): Color {
    if (zones == null) return UrujZone2
    for (z in zones.zones) {
        if (bpm <= z.upperBpm) return zoneColor(z.number)
    }
    return zoneColor(zones.zones.lastOrNull()?.number ?: 5)
}
