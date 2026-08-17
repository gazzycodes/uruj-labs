package com.uruj.ui.hud

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * v0.9.78 — slide-to-end. Replaces the tap-STOP button on the HUD.
 *
 * ## The bug this exists for
 *
 * Riding in the rain, water on the screen registers as touches. The rider
 * reported rides being ENDED mid-ride by phantom presses — and ending a ride is
 * the one irreversible control on the HUD. A confirmation dialog does not fix
 * this: the dialog's own buttons are equally tappable by a droplet, and a wet
 * screen can produce two touches in sequence.
 *
 * A capacitive false-touch is a **point event**. It does not travel 85% of the
 * screen's width while maintaining contact. So the gesture required here is a
 * sustained horizontal drag — physically unavailable to rain, achievable with
 * one gloved thumb, and impossible to trigger by accident in a jersey pocket.
 *
 * PAUSE deliberately stays a plain tap: a phantom pause is visible (the HUD
 * shows a PAUSED banner) and undone with one more tap. Friction belongs on the
 * control you cannot take back, not on the one you can.
 *
 * ## Behaviour
 *
 * - Drag the thumb right; the track fills red behind it.
 * - Cross [CONFIRM_FRACTION] and the phone gives a haptic thump — confirmable
 *   without looking at the screen, which matters at 30 kph.
 * - Release past the threshold → [onConfirmed]. Release short → springs back.
 */
@Composable
fun SwipeToEndControl(
    /** Ride stats shown inside the track, so the rider sees what they're ending. */
    subtitle: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT)
            .clip(RoundedCornerShape(18.dp))
            .background(UrujSurface)
            .border(1.dp, UrujZone5.copy(alpha = 0.55f), RoundedCornerShape(18.dp)),
    ) {
        val maxOffsetPx = with(density) {
            (maxWidth - THUMB_WIDTH - TRACK_INSET * 2).toPx().coerceAtLeast(1f)
        }
        val offset = remember { Animatable(0f) }
        var armed by remember { mutableStateOf(false) }
        val progress = (offset.value / maxOffsetPx).coerceIn(0f, 1f)
        val past = progress >= CONFIRM_FRACTION

        // A layout change (rotation, a shorter screen) must not leave the thumb
        // stranded outside its new track.
        LaunchedEffect(maxOffsetPx) {
            if (offset.value > maxOffsetPx) offset.snapTo(maxOffsetPx)
        }

        // Filled portion behind the thumb — the progress IS the affordance.
        // Skipped entirely at rest: no drag, no fill, no draw.
        if (progress > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = (progress * 0.98f).coerceIn(0.01f, 1f))
                    .background(
                        UrujZone5.copy(alpha = if (past) 0.85f else 0.18f + progress * 0.4f),
                    ),
            )
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (past) "RELEASE TO END" else "SLIDE TO END RIDE",
                color = if (past) Color.White else UrujText.copy(alpha = 1f - progress * 0.5f),
                fontWeight = FontWeight.Black,
                fontSize = 13.sp,
                letterSpacing = 3.sp,
                maxLines = 1,
            )
            Text(
                text = subtitle,
                color = if (past) Color.White.copy(alpha = 0.85f) else UrujMuted,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 7.dp),
            )
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .padding(TRACK_INSET)
                .width(THUMB_WIDTH - TRACK_INSET * 2)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(if (past) Color.White else UrujZone5)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            val next = (offset.value + delta).coerceIn(0f, maxOffsetPx)
                            offset.snapTo(next)
                            // Haptic exactly once per threshold crossing, so the
                            // rider can confirm by feel without looking down.
                            val nowPast = next / maxOffsetPx >= CONFIRM_FRACTION
                            if (nowPast && !armed) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                armed = true
                            } else if (!nowPast) {
                                armed = false
                            }
                        }
                    },
                    onDragStopped = {
                        if (offset.value / maxOffsetPx >= CONFIRM_FRACTION) {
                            offset.snapTo(0f)
                            armed = false
                            onConfirmed()
                        } else {
                            armed = false
                            offset.animateTo(0f, spring())
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "▶▶",
                color = if (past) UrujZone5 else Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 15.sp,
            )
        }
    }
}

/**
 * v0.9.78 — manual PAUSE / RESUME. Stays a tap (see [SwipeToEndControl] for why)
 * but is now a bordered outline rather than a filled block: on an AMOLED panel
 * held at full brightness for a whole ride, unlit pixels are the cheapest pixels,
 * and the HUD has a lot of hours to fill.
 */
@Composable
fun PauseToggleControl(
    manuallyPaused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (manuallyPaused) com.uruj.ui.theme.UrujZone2 else com.uruj.ui.theme.UrujZone3
    Box(
        modifier = modifier
            .height(TRACK_HEIGHT)
            .clip(RoundedCornerShape(18.dp))
            .background(if (manuallyPaused) accent else UrujSurface)
            .border(
                width = 1.dp,
                color = if (manuallyPaused) accent else UrujSurfaceHigh,
                shape = RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Sized to survive the narrow (~30% width) column without clipping —
        // maxLines=1 would silently cut "PAUSE" in half on a small screen.
        Text(
            text = if (manuallyPaused) "▶ RESUME" else "❚❚ PAUSE",
            color = if (manuallyPaused) Color.Black else accent,
            fontWeight = FontWeight.Black,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Height shared by both bottom controls so they line up exactly. */
private val TRACK_HEIGHT = 58.dp

/** Inset of the thumb within the track. */
private val TRACK_INSET = 4.dp

/** Thumb footprint — wide enough for a gloved thumb. */
private val THUMB_WIDTH = 66.dp

/**
 * How far the thumb must travel to arm the confirm. 0.85 means the gesture
 * spans nearly the whole screen width: unreachable by a raindrop, trivially
 * reachable by a deliberate thumb.
 */
private const val CONFIRM_FRACTION = 0.85f
