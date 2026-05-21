package com.uruj.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh

/**
 * v0.9.23 — shared loading-state placeholder card. Eliminates the
 * "card vanishes and reappears" effect when async data is being fetched
 * (was the issue on Readiness card + Weekly Polarized card pre-fix —
 * rider had no idea if data was coming or broken).
 *
 * Pattern:
 *   - Card outline matches the real card (same UrujSurface + border + radius)
 *     so layout doesn't reflow when real content takes over
 *   - Header row: accent label (the section name, e.g. "POLARIZED — THIS WEEK")
 *     + pulsing "ANALYZING…" indicator on the right
 *   - Optional: 3 placeholder bars of decreasing width (shimmer effect)
 *   - Subtle alpha pulse to signal liveness
 *
 * Why not a CircularProgressIndicator: the user is on a Pre-ride / Rides
 * screen with the whole UI loading at once; a spinner would compete with
 * other UI noise. A subtly-pulsing skeleton signals "computing" without
 * grabbing attention.
 *
 * Reusable across screens — keep the surface treatment consistent so the
 * load state is recognizable.
 */
@Composable
fun LoadingCardSkeleton(
    label: String,
    modifier: Modifier = Modifier,
    statusText: String = "ANALYZING…",
    showBars: Boolean = true,
    barCount: Int = 3,
    cardHeight: Dp = 110.dp,
) {
    // Slow gentle pulse — 0.45 → 0.85 over 900ms each way. Slow enough to
    // not feel frantic; visible enough to confirm "still working."
    val infinite = rememberInfiniteTransition(label = "skeleton_pulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton_pulse_alpha",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(UrujSurface)
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // Header — section label + animated status
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = statusText,
                color = UrujAccent.copy(alpha = pulseAlpha),
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
            )
        }
        if (showBars) {
            // Placeholder bars — decreasing width (mimics title + subtitle + caption).
            val widthFractions = listOf(0.85f, 0.65f, 0.50f, 0.40f, 0.35f).take(barCount.coerceAtMost(5))
            val barHeight = 12.dp
            Spacer(Modifier.height(14.dp))
            widthFractions.forEachIndexed { idx, frac ->
                // Each bar gets a slightly different pulse phase so the whole
                // card breathes instead of blinking in unison — feels less
                // mechanical.
                val phaseOffset = (idx * 100).toFloat()
                val barAlpha = (pulseAlpha + phaseOffset / 1000f).coerceIn(0.3f, 0.85f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(frac)
                        .height(barHeight)
                        .clip(RoundedCornerShape(3.dp))
                        .background(UrujSurfaceHigh.copy(alpha = barAlpha)),
                )
                if (idx < widthFractions.size - 1) {
                    Spacer(Modifier.height(8.dp))
                }
            }
        } else {
            // Bars hidden: pad to maintain the cardHeight footprint so the
            // real content slot doesn't jump when content lands.
            Spacer(Modifier.height(cardHeight - 30.dp))
        }
    }
}

/**
 * v0.9.23 — variant tuned for the Weekly Polarized card. Shows the same
 * header pattern + a placeholder for the daily-bars row (7 ghost bars,
 * varying heights, pulsing alpha) so the card footprint mirrors the real
 * thing while loading.
 */
@Composable
fun WeeklyPolarizedSkeleton(modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "wpolar_skeleton")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "wpolar_pulse_alpha",
    )
    // Pre-computed ghost-bar heights so the skeleton looks like a real week
    // (not all bars equal). Caller doesn't pass real data — these are static.
    val ghostHeights = listOf(0.7f, 0.85f, 0.4f, 0.0f, 0.55f, 0.0f, 0.3f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(UrujSurface)
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // Header — "POLARIZED — THIS WEEK" + pulsing status
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "POLARIZED — THIS WEEK",
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "ANALYZING WEEK…",
                color = UrujAccent.copy(alpha = pulseAlpha),
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
            )
        }
        // Placeholder summary line + verdict line
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(UrujSurfaceHigh.copy(alpha = pulseAlpha)),
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .height(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(UrujSurfaceHigh.copy(alpha = (pulseAlpha * 0.7f).coerceAtLeast(0.25f))),
        )
        // Ghost daily-bars — 7 columns, varying heights
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ghostHeights.forEach { frac ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val barHeight = 48.dp
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(barHeight)
                            .padding(top = (barHeight.value * (1f - frac)).dp),
                    ) {
                        if (frac > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(UrujSurfaceHigh.copy(alpha = pulseAlpha)),
                            )
                        } else {
                            // Empty-day strip even in skeleton — shows the
                            // shape of the real "no ride that day" cell.
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .align(Alignment.BottomCenter)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(UrujSurfaceHigh.copy(alpha = 0.35f)),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(UrujSurfaceHigh.copy(alpha = (pulseAlpha * 0.6f).coerceAtLeast(0.25f))),
                    )
                }
            }
        }
        // Legend line placeholder
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .height(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(UrujSurfaceHigh.copy(alpha = (pulseAlpha * 0.6f).coerceAtLeast(0.25f))),
        )
    }
}

/**
 * v0.9.23 — variant tuned for the Readiness card on the Pre-ride checklist
 * screen. Mimics the layout: big score placeholder + 4 metric rows + a
 * recommendation block.
 */
@Composable
fun ReadinessSkeleton(modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "readiness_skeleton")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "readiness_pulse_alpha",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(UrujSurface)
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        // Header — "READINESS" + pulsing status
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "READINESS",
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "ANALYZING…",
                color = UrujAccent.copy(alpha = pulseAlpha),
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
            )
        }
        // Big "score" placeholder (where 66/100 + grade would appear)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Box(
                modifier = Modifier
                    .width(90.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(UrujSurfaceHigh.copy(alpha = pulseAlpha)),
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(UrujSurfaceHigh.copy(alpha = (pulseAlpha * 0.7f).coerceAtLeast(0.25f))),
            )
        }
        // 4 metric rows — Sleep / HRV / RHR / Training Load
        repeat(4) { idx ->
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(70.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(UrujSurfaceHigh.copy(alpha = (pulseAlpha * 0.7f).coerceAtLeast(0.25f))),
                )
                Spacer(Modifier.width(14.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(UrujSurfaceHigh.copy(alpha = pulseAlpha)),
                )
                Spacer(Modifier.width(14.dp))
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(UrujSurfaceHigh.copy(alpha = (pulseAlpha * 0.7f).coerceAtLeast(0.25f))),
                )
            }
        }
        // Recommendation placeholder
        Spacer(Modifier.height(18.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(UrujSurfaceHigh.copy(alpha = (pulseAlpha * 0.6f).coerceAtLeast(0.25f))),
        )
    }
}
