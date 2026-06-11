package com.uruj.ui.live

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uruj.service.LiveStateHolder
import com.uruj.ui.theme.UrujMuted
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.9.70 / v0.9.71 — WIND-DOWN: immersive guided-breathing biofeedback.
 *
 * A living breathing orb you sink into. Pace = 4s in / 1s hold / 6s out
 * (~5 breaths/min — the coherence band that maximises the vagal brake). The
 * orb's size is driven FRAME-BY-FRAME (withFrameNanos) with a sine ease, so it
 * tracks the breath exactly — you can match it. It tints COOLER as your live HR
 * (from [LiveStateHolder]) settles, WARMER when it's up — direct biofeedback.
 *
 * Audio is fully synthesised by [WindDownAudio] — a soft ambient drone + gentle
 * inhale/exhale tones. No TTS, no robotic voice. SOUND ON/OFF toggle.
 *
 * VIEW-ONLY by design (rider spec): writes nothing to disk / snapshots /
 * ReadinessContext / NDJSON. Keeps the screen on. Works as a pure pacer with no
 * strap connected.
 */
@Composable
fun WindDownScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val live by LiveStateHolder.state.collectAsStateWithLifecycle()

    var running by remember { mutableStateOf(true) }
    var soundOn by remember { mutableStateOf(true) }
    var phaseLabel by remember { mutableStateOf("Breathe in") }
    val breath = remember { mutableFloatStateOf(0f) } // 0 = fully exhaled, 1 = fully inhaled

    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(1000L); tick = System.currentTimeMillis() } }
    val fresh = remember(live.lastUpdatedMs, tick) { live.isFresh() }
    val rmssd = remember(live.recentBeats.lastOrNull()?.timestampMs, tick) { live.rollingRmssdMs() }
    val bpm = live.latestBpm

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val audio = remember { WindDownAudio() }
    DisposableEffect(Unit) { audio.start(); onDispose { audio.release() } }
    LaunchedEffect(soundOn) { audio.setEnabled(soundOn) }

    // Frame-accurate breath driver — follows 4s in / 1s hold / 6s out exactly.
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            phaseLabel = "Breathe in"; audio.breathTone(inhale = true)
            animateBreath(INHALE_MS) { p -> breath.floatValue = ease(p) }
            phaseLabel = "Hold"; delay(HOLD_MS.toLong())
            phaseLabel = "Breathe out"; audio.breathTone(inhale = false)
            animateBreath(EXHALE_MS) { p -> breath.floatValue = ease(1f - p) }
            phaseLabel = "Rest"; delay(REST_MS.toLong())
        }
    }

    val rot by rememberInfiniteTransition(label = "rot").animateFloat(
        0f, 360f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "rot",
    )
    val shimmer by rememberInfiniteTransition(label = "sh").animateFloat(
        0.4f, 0.85f,
        infiniteRepeatable(tween(4200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sh",
    )

    val warmth = (((bpm ?: 62) - 55f).coerceIn(0f, 35f)) / 35f
    val orbColor = lerp(CALM_TEAL, WARM_CORAL, warmth)

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF05070A))) {

        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = (minOf(size.width, size.height) * 0.30f) * (MIN_SCALE + (1f - MIN_SCALE) * breath.floatValue)

            // Deep-space vignette.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF0B131C), Color(0xFF05070A)),
                    center = Offset(cx, cy), radius = size.maxDimension * 0.75f,
                ),
                radius = size.maxDimension, center = Offset(cx, cy),
            )
            // Expanding aura rings.
            for (i in 1..4) {
                drawCircle(
                    color = orbColor.copy(alpha = (0.14f / i) * shimmer),
                    radius = r * (1f + i * 0.30f), center = Offset(cx, cy),
                    style = Stroke(width = 1.5f),
                )
            }
            // Counter-rotating particle halos.
            drawParticles(cx, cy, r * 1.16f, rot, 30, orbColor.copy(alpha = 0.40f * shimmer))
            drawParticles(cx, cy, r * 1.42f, -rot * 0.6f, 18, orbColor.copy(alpha = 0.22f * shimmer))
            // Soft orb glow — kept gentle so overlaid text stays clean.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        orbColor.copy(alpha = 0.55f),
                        orbColor.copy(alpha = 0.18f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy), radius = r,
                ),
                radius = r, center = Offset(cx, cy),
            )
            drawCircle(orbColor.copy(alpha = 0.30f), radius = r * 0.14f, center = Offset(cx, cy))
        }

        // Top: chrome + the breath cue word (kept clear of the orb core).
        Column(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "← CLOSE", color = Color.White.copy(alpha = 0.6f),
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.sp,
                    modifier = Modifier.clickable { onBack() },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (soundOn) "SOUND ON" else "SOUND OFF",
                    color = if (soundOn) Color.White.copy(alpha = 0.7f) else UrujMuted,
                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.sp,
                    modifier = Modifier.clickable { soundOn = !soundOn },
                )
            }
            Spacer(Modifier.height(54.dp))
            Text(
                phaseLabel.uppercase(),
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Light, fontSize = 26.sp, letterSpacing = 8.sp,
            )
        }

        // Bottom: HR + HRV + pause + pace — one clean cluster, off the orb.
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 46.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                bpm?.toString() ?: "—",
                color = orbColor, fontWeight = FontWeight.Light, fontSize = 50.sp,
            )
            Text(
                if (fresh) "bpm · live" else "bpm · strap offline",
                color = UrujMuted, fontSize = 11.sp, letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                rmssd?.let { "live HRV  ${"%.0f".format(it)} ms" } ?: "settle in — breathe with the orb",
                color = UrujMuted, fontSize = 11.sp, letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                if (running) "❙❙  PAUSE" else "▶  RESUME",
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 2.sp,
                modifier = Modifier.clickable { running = !running },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "in 4 · hold · out 6   ·   ~5 breaths / min",
                color = UrujMuted.copy(alpha = 0.6f), fontSize = 10.sp, letterSpacing = 0.5.sp,
            )
        }
    }
}

/** Drives [onProgress] 0→1 over exactly [durationMs] using real frame time. */
private suspend fun animateBreath(durationMs: Int, onProgress: (Float) -> Unit) {
    var start = 0L
    while (true) {
        val now = withFrameNanos { it }
        if (start == 0L) start = now
        val p = ((now - start) / 1_000_000f / durationMs).coerceIn(0f, 1f)
        onProgress(p)
        if (p >= 1f) break
    }
}

/** Smooth sine ease (slow at the turn-arounds) — feels like a real breath. */
private fun ease(x: Float): Float = ((1.0 - cos(PI * x)) / 2.0).toFloat()

private fun DrawScope.drawParticles(
    cx: Float, cy: Float, radius: Float, angleDeg: Float, count: Int, color: Color,
) {
    for (i in 0 until count) {
        val ang = Math.toRadians((angleDeg + i * (360f / count)).toDouble())
        drawCircle(
            color = color, radius = 2.4f,
            center = Offset(cx + cos(ang).toFloat() * radius, cy + sin(ang).toFloat() * radius),
        )
    }
}

// ── Breath pacing (rider spec: in 4 / out 6) ──
private const val INHALE_MS = 4000
private const val HOLD_MS = 1000
private const val EXHALE_MS = 6000
private const val REST_MS = 700
private const val MIN_SCALE = 0.45f

// ── Palette ──
private val CALM_TEAL = Color(0xFF3FE0C8)
private val WARM_CORAL = Color(0xFFFF7A59)
