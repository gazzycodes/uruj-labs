package com.uruj.ui.live

import android.app.Activity
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.9.70 — WIND-DOWN: immersive guided-breathing biofeedback.
 *
 * A living, art-style breathing orb you sink into. Pace = 4s in / 1s hold /
 * 6s out (~5 breaths/min — the coherence band that maximises the vagal brake).
 * Reads live HR from [LiveStateHolder] (the same beat-by-beat strap stream the
 * Live tab uses) and tints the orb COOLER as your heart rate settles — direct
 * biofeedback: you literally watch yourself calm down. Optional calming voice
 * (low pitch, slow rate) with a mute toggle.
 *
 * VIEW-ONLY by design (rider's spec): nothing is written to disk, snapshots,
 * ReadinessContext, or NDJSON. Pure real-time experience. Keeps the screen on
 * while open. If the strap is offline it still works as a pure visual pacer.
 */
@Composable
fun WindDownScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val live by LiveStateHolder.state.collectAsStateWithLifecycle()

    var running by remember { mutableStateOf(true) }
    var muted by remember { mutableStateOf(false) }
    var phaseLabel by remember { mutableStateOf("Breathe in") }
    val scale = remember { Animatable(MIN_SCALE) }

    // 1s tick so freshness + rolling HRV refresh even when no new beat arrives.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(1000L); tick = System.currentTimeMillis() } }
    val fresh = remember(live.lastUpdatedMs, tick) { live.isFresh() }
    val rmssd = remember(live.recentBeats.lastOrNull()?.timestampMs, tick) { live.rollingRmssdMs() }
    val bpm = live.latestBpm

    // Keep the screen awake while winding down.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Calm TTS engine — lifecycle-bound, shut down on leave.
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.US
                engine?.setPitch(0.82f)
                engine?.setSpeechRate(0.78f)
                tts = engine
            }
        }
        onDispose { engine?.stop(); engine?.shutdown(); tts = null }
    }

    fun speak(text: String) {
        if (muted) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "winddown")
    }

    // Breath driver — cancels cleanly on pause (running=false relaunches effect).
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            phaseLabel = "Breathe in"; speak("Breathe in")
            scale.animateTo(1f, tween(INHALE_MS, easing = FastOutSlowInEasing))
            phaseLabel = "Hold"
            delay(HOLD_MS.toLong())
            phaseLabel = "Breathe out"; speak("Breathe out")
            scale.animateTo(MIN_SCALE, tween(EXHALE_MS, easing = FastOutSlowInEasing))
            delay(REST_MS.toLong())
        }
    }

    // Slow rotation + breathing shimmer for the living-art feel.
    val rot by rememberInfiniteTransition(label = "rot").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(48_000, easing = LinearEasing)), label = "rot",
    )
    val shimmer by rememberInfiniteTransition(label = "sh").animateFloat(
        initialValue = 0.35f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sh",
    )

    // Orb colour cools as HR settles: ~90 bpm warm coral → ~55 bpm calm teal.
    val warmth = (((bpm ?: 62) - 55f).coerceIn(0f, 35f)) / 35f
    val orbColor = lerp(CALM_TEAL, WARM_CORAL, warmth)

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF05070A))) {

        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val base = minOf(size.width, size.height) * 0.30f
            val r = base * scale.value

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
                    color = orbColor.copy(alpha = (0.18f / i) * shimmer),
                    radius = r * (1f + i * 0.30f), center = Offset(cx, cy),
                    style = Stroke(width = 2f),
                )
            }

            // Orbiting particles — two counter-rotating rings.
            drawParticles(cx, cy, r * 1.16f, rot, 30, orbColor.copy(alpha = 0.55f * shimmer))
            drawParticles(cx, cy, r * 1.42f, -rot * 0.6f, 18, orbColor.copy(alpha = 0.30f * shimmer))

            // Core orb glow.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        orbColor.copy(alpha = 0.85f),
                        orbColor.copy(alpha = 0.30f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy), radius = r,
                ),
                radius = r, center = Offset(cx, cy),
            )
            drawCircle(orbColor.copy(alpha = 0.9f), radius = r * 0.16f, center = Offset(cx, cy))
        }

        // Center: phase word + live HR.
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                phaseLabel.uppercase(),
                color = Color.White.copy(alpha = 0.88f),
                fontWeight = FontWeight.Light, fontSize = 22.sp, letterSpacing = 5.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                bpm?.toString() ?: "—",
                color = orbColor, fontWeight = FontWeight.Thin, fontSize = 76.sp, letterSpacing = (-3).sp,
            )
            Text(
                if (fresh) "bpm · live" else "bpm · strap offline",
                color = UrujMuted, fontSize = 11.sp, letterSpacing = 2.sp,
            )
        }

        // Top chrome.
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "← CLOSE", color = Color.White.copy(alpha = 0.6f),
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.sp,
                modifier = Modifier.clickable { onBack() },
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (muted) "VOICE OFF" else "VOICE ON",
                color = if (muted) UrujMuted else Color.White.copy(alpha = 0.7f),
                fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.sp,
                modifier = Modifier.clickable { muted = !muted; if (muted) tts?.stop() },
            )
        }

        // Bottom chrome: live HRV + pause + pace caption.
        Column(
            modifier = Modifier.fillMaxSize().padding(bottom = 44.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                rmssd?.let { "live HRV  ${"%.0f".format(it)} ms" } ?: "settle in — breathe with the orb",
                color = UrujMuted, fontSize = 11.sp, letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                if (running) "❙❙  PAUSE" else "▶  RESUME",
                color = Color.White.copy(alpha = 0.85f),
                fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 2.sp,
                modifier = Modifier.clickable { running = !running; if (!running) tts?.stop() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "in 4 · hold · out 6   —   ~5 breaths/min   ·   longer exhale = vagal brake",
                color = UrujMuted.copy(alpha = 0.7f), fontSize = 10.sp, letterSpacing = 0.5.sp,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawParticles(
    cx: Float, cy: Float, radius: Float, angleDeg: Float, count: Int, color: Color,
) {
    for (i in 0 until count) {
        val ang = Math.toRadians((angleDeg + i * (360f / count)).toDouble())
        val px = cx + cos(ang).toFloat() * radius
        val py = cy + sin(ang).toFloat() * radius
        drawCircle(color = color, radius = 2.6f, center = Offset(px, py))
    }
}

// ── Breath pacing (rider spec: in 4 / out 6) ──
private const val INHALE_MS = 4000
private const val HOLD_MS = 1000
private const val EXHALE_MS = 6000
private const val REST_MS = 600
private const val MIN_SCALE = 0.5f

// ── Palette ──
private val CALM_TEAL = Color(0xFF3FE0C8)
private val WARM_CORAL = Color(0xFFFF7A59)
