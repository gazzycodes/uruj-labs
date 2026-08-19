package com.uruj.ui.hud

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurfaceHigh

/**
 * v0.9.78 — the HUD's hero readout primitives.
 *
 * ## Why segmented bars and not gauges / rings / particles
 *
 * Three constraints had to hold at once: the numbers must be as physically
 * large as the phone allows (read through a frame-bag window, at speed, in
 * sunlight), everything must fit on one screen without scrolling, and the whole
 * thing must not cost battery on a ride where the display is already forced on.
 *
 * A ring gauge steals the space the digits need. A segmented bar sits *under*
 * the number in 8 dp, encodes the same information (where you are in the range,
 * and which band you're in) and is legible in peripheral vision without reading
 * the digits at all — which is what a HUD is actually for.
 *
 * ## The battery rule this file follows
 *
 * **Motion only where it encodes data, and only when the data changes.** There
 * are no `rememberInfiniteTransition` loops here. Every animation is an
 * `animateFloatAsState` driven by a value that updates about once a second, so
 * it settles and stops. The pre-v0.9.78 HUD ran two always-on pulse animations
 * regardless of what the sensors were doing; removing those pays for the
 * animated bars several times over.
 */

/** One band to highlight on a metric's bar (target cadence, an HR zone, …). */
data class MetricBand(
    val fromFraction: Float,
    val toFraction: Float,
    val color: Color,
)

/**
 * A hero metric: oversized value, small unit, and a segmented range bar.
 *
 * @param value the big digits (integer part). Kept short on purpose.
 * @param suffix rendered smaller and baseline-aligned — used for the speed
 *   decimal so "28.4" gives the "28" nearly the full cell width instead of
 *   shrinking every glyph to fit a character the rider barely reads.
 * @param progress 0..1 position within the metric's range; drives the lit segments.
 * @param bands range regions drawn into the unlit track, so a target (cadence
 *   80-95, the HR zone map) stays visible even when the value is zero.
 */
@Composable
fun HudMetric(
    value: String,
    suffix: String?,
    unit: String,
    color: Color,
    progress: Float,
    modifier: Modifier = Modifier,
    bands: List<MetricBand> = emptyList(),
    dim: Boolean = false,
) {
    BoxWithConstraints(modifier = modifier) {
        // Size the digits from the cell we actually got, so the readout is as
        // large as the device allows instead of a constant tuned to one phone.
        //
        // The weighting matters: a Black-weight sans digit occupies about
        // GLYPH_ADVANCE_RATIO of its font size in width, and the suffix renders
        // at SUFFIX_SIZE_RATIO — so "28" + ".4" costs 2.84 digit-widths, not 2.
        // Sizing off `value.length` alone overflowed the speed cell.
        val cellWidth: Dp = maxWidth
        // A placeholder dash is measured as 3 digits so the layout doesn't jump
        // the moment a sensor connects.
        val valueGlyphs = if (value == PLACEHOLDER) 3f else value.length.toFloat()
        val glyphWeight =
            (valueGlyphs + (suffix?.length ?: 0) * SUFFIX_SIZE_RATIO).coerceAtLeast(1f)
        val valueSize = (cellWidth.value / (glyphWeight * GLYPH_ADVANCE_RATIO))
            .coerceIn(30f, 78f)
        val animatedProgress by animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 700),
            label = "metric_progress",
        )
        val valueColor = if (dim) UrujMuted else color

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    color = valueColor,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    fontSize = valueSize.sp,
                    letterSpacing = (-valueSize * 0.045f).sp,
                    maxLines = 1,
                )
                if (suffix != null) {
                    Text(
                        text = suffix,
                        color = valueColor.copy(alpha = 0.75f),
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Black,
                        fontSize = (valueSize * SUFFIX_SIZE_RATIO).sp,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = (valueSize * 0.12f).dp),
                    )
                }
            }
            SegmentBar(
                progress = animatedProgress,
                color = if (dim) UrujMuted else color,
                bands = bands,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = unit,
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
                letterSpacing = 2.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The range indicator: [SEGMENT_COUNT] discrete blocks, lit up to [progress].
 *
 * Discrete rather than continuous because a segment count is readable at a
 * glance without focusing — the rider counts blocks in peripheral vision the
 * way a pilot reads a strip gauge. Drawn only when its inputs change, which at
 * 1 Hz sensor rates means a handful of cheap draws per second.
 */
@Composable
fun SegmentBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    bands: List<MetricBand> = emptyList(),
    height: Dp = 7.dp,
) {
    // Hoisted: the draw lambda is a DrawScope, not a composable scope, so the
    // palette has to be read here and captured.
    val unlitColor = UrujSurfaceHigh
    Canvas(modifier = modifier.height(height)) {
        val gap = size.width * SEGMENT_GAP_FRACTION / (SEGMENT_COUNT - 1)
        val segmentWidth = (size.width - gap * (SEGMENT_COUNT - 1)) / SEGMENT_COUNT
        if (segmentWidth <= 0f) return@Canvas
        val litCount = (progress.coerceIn(0f, 1f) * SEGMENT_COUNT).toInt()
        val radius = CornerRadius(size.height / 2f, size.height / 2f)

        for (i in 0 until SEGMENT_COUNT) {
            val fraction = (i + 0.5f) / SEGMENT_COUNT
            val band = bands.firstOrNull { fraction >= it.fromFraction && fraction < it.toFraction }
            val segmentColor = when {
                i < litCount -> color
                band != null -> band.color.copy(alpha = 0.42f)
                else -> unlitColor
            }
            drawRoundRect(
                color = segmentColor,
                topLeft = Offset(i * (segmentWidth + gap), 0f),
                size = Size(segmentWidth, size.height),
                cornerRadius = radius,
            )
        }
    }
}

/** A compact labelled figure for the secondary stats panel. */
@Composable
fun HudStat(
    label: String,
    value: String,
    unit: String?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        Text(
            text = label,
            color = UrujMuted,
            fontWeight = FontWeight.Black,
            fontSize = 8.sp,
            letterSpacing = 1.5.sp,
            maxLines = 1,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = accent,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                fontSize = 19.sp,
                letterSpacing = (-0.5).sp,
                maxLines = 1,
            )
            if (unit != null) {
                Text(
                    text = " $unit",
                    color = UrujMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}

/** Blocks in a [SegmentBar]. 18 reads as a scale without turning into a solid line. */
private const val SEGMENT_COUNT = 18

/** Share of the bar width given over to the gaps between blocks. */
private const val SEGMENT_GAP_FRACTION = 0.22f

/** Width of one Black-weight digit as a fraction of its font size. Measured, not guessed. */
private const val GLYPH_ADVANCE_RATIO = 0.62f

/** The suffix (speed's decimal) renders at this fraction of the main value's size. */
private const val SUFFIX_SIZE_RATIO = 0.42f

/** Shown when a metric has no source. Also the string the sizer measures as 3 digits. */
const val PLACEHOLDER = "—"
