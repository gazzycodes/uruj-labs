package com.uruj.ui.branding

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujOnAccent
import com.uruj.ui.theme.UrujAccent

/**
 * URUJ logo — solid cyan "U" tile inside a dim cyan track, with a bright white 60°
 * arc orbiting once every 3 seconds.
 *
 * Deliberately minimal — no multi-hue gradients, no AI-art rainbow. Reads like a
 * lab instrument / radar sweep. Cheap to render (single Canvas, single rotation).
 */
@Composable
fun UrujLogo(modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val infinite = rememberInfiniteTransition(label = "uruj_logo")
    val angle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "uruj_orbit",
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        // Canvas draws in a DrawScope — accent read here, captured below.
        val accentColor = UrujAccent
        // The orbit sweep must contrast with the PAGE, so it tracks the text
        // colour: near-white on black, near-black on white.
        val orbitColor = UrujText
        Canvas(modifier = Modifier.size(size)) {
            val stroke = (size.toPx() * 0.07f).coerceAtLeast(2f)
            // Dim base track — always visible, defines the ring shape.
            drawArc(
                color = accentColor.copy(alpha = 0.20f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke),
            )
            // High-contrast orbit arc — sweeps around the track once per 3s.
            drawArc(
                color = orbitColor,
                startAngle = angle - 30f,
                sweepAngle = 60f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }

        Box(
            modifier = Modifier
                .size(size * 0.60f)
                .clip(RoundedCornerShape(size * 0.14f))
                .background(UrujAccent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "U",
                color = UrujOnAccent,
                // Force the glyph to its true geometric center — Android's default font
                // padding + line-height offsets shift the "U" visibly off-center at small
                // logo sizes. Removing both gives us a precisely centered letter.
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    fontSize = (size.value * 0.42f).sp,
                    lineHeight = (size.value * 0.42f).sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both,
                    ),
                ),
            )
        }
    }
}
