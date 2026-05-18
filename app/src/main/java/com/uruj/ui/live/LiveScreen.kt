package com.uruj.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uruj.data.RiderProfileStore
import com.uruj.power.KarvonenZonesCalculator
import com.uruj.service.LiveStateHolder
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone1
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone4
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.delay

/**
 * v0.8.1 — full-screen Live View. Real-time cardiac data flowing.
 *
 * Reads from LiveStateHolder which is populated by whichever BLE owner is
 * active (BiometricService 24/7 OR RideRecorderService during a ride).
 *
 * Sections:
 *   1. Hero BPM number + freshness indicator
 *   2. HR waveform (last 90 sec, tier-shaded)
 *   3. Per-beat RR interval bars (last 30 beats) — real-time HRV viz
 *   4. Rolling 60-sec RMSSD computation (updates every beat)
 *   5. Current zone + zone target labels
 *
 * Why this is useful, not just decorative:
 *   - HR creep on a Z2 ride = aerobic decoupling early warning
 *   - RMSSD recovering between intervals = ready for next effort
 *   - RR bar variance vs flat = strap data quality + HRV state at a glance
 *   - At rest = confirm strap is clean before the morning HRV reading
 */
@Composable
fun LiveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val live by LiveStateHolder.state.collectAsStateWithLifecycle()
    val profileStore = remember { RiderProfileStore(context) }
    val profile by profileStore.profile.collectAsStateWithLifecycle(initialValue = com.uruj.domain.RiderProfile())
    val zones = remember(profile.maxHrBpm, profile.restingHrBpm) {
        KarvonenZonesCalculator().compute(profile.maxHrBpm, profile.restingHrBpm.coerceAtLeast(40))
    }

    // Periodic re-tick so rolling RMSSD + freshness label refresh even when
    // no new beat arrives (e.g. strap dropped).
    var refreshTick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000L); refreshTick = System.currentTimeMillis() }
    }

    val rollingRmssd = remember(live.recentBeats.lastOrNull()?.timestampMs, refreshTick) {
        live.rollingRmssdMs()
    }
    val isFresh = live.isFresh(refreshTick.coerceAtLeast(System.currentTimeMillis()))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text("← BACK", color = UrujAccent, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                StreamingDot(isFresh)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isFresh) "STREAMING" else "OFFLINE",
                    color = if (isFresh) UrujZone2 else UrujMuted,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                )
            }
            Text(
                "LIVE CARDIAC DATA",
                color = UrujAccent,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
            )
            Text(
                "Beat-by-beat from your chest strap. Useful for catching " +
                    "aerobic decoupling on rides, HRV state between intervals, " +
                    "and confirming clean signal at rest before morning HRV.",
                color = UrujMuted, fontSize = 12.sp,
            )

            // ── Hero BPM ──
            HeroBpm(live = live, zones = zones)

            // ── Waveform (last 90s) ──
            Text(
                "HEART RATE · LAST 90s",
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
            )
            HrWaveform(
                beats = live.recentBeats,
                windowMs = 90_000L,
                zones = zones,
                height = 160.dp,
            )

            // ── RR interval bars (last 30 beats) ──
            Text(
                "RR INTERVALS · LAST 30 BEATS",
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
            )
            Text(
                "Each bar = one heartbeat. Bar HEIGHT = RR interval length. " +
                    "Visible variance = real HRV; flat = noise or autonomic " +
                    "suppression.",
                color = UrujMuted.copy(alpha = 0.8f), fontSize = 10.sp,
            )
            RrIntervalBars(beats = live.recentBeats.takeLast(30))

            // ── Rolling RMSSD + zone block ──
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                StatBlock(
                    label = "ROLLING RMSSD · 60s",
                    value = rollingRmssd?.let { "${"%.0f".format(it)} ms" } ?: "—",
                    detail = "live HRV proxy",
                    accent = if (rollingRmssd != null && rollingRmssd > 25f) UrujZone2 else UrujMuted,
                    modifier = Modifier.weight(1f),
                )
                StatBlock(
                    label = "CURRENT ZONE",
                    value = currentZoneLabel(live.latestBpm, zones),
                    detail = live.latestBpm?.let { "$it bpm" } ?: "no live data",
                    accent = currentZoneColor(live.latestBpm, zones),
                    modifier = Modifier.weight(1f),
                )
            }

            // ── Methodology footer ──
            Spacer(Modifier.height(4.dp))
            Text(
                "Source: BLE chest strap (24/7 service when idle, RideRecorder " +
                    "during rides). Data is in-memory only on this screen — your " +
                    "trends + readings still come from the persisted NDJSON. " +
                    "Stale label fires after 5 sec without a new beat.",
                color = UrujMuted, fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun StreamingDot(isFresh: Boolean) {
    val color = if (isFresh) UrujZone2 else UrujMuted
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun HeroBpm(live: LiveStateHolder.State, zones: KarvonenZonesCalculator.Result?) {
    val bpm = live.latestBpm
    val accent = currentZoneColor(bpm, zones)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(UrujSurface)
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(14.dp))
            .padding(18.dp),
    ) {
        Column {
            Text(
                "HEART RATE",
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    bpm?.toString() ?: "—",
                    color = accent,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    fontSize = 64.sp,
                    letterSpacing = (-2).sp,
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                    Text("bpm", color = UrujMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    val rr = live.latestRrMs
                    Text(
                        if (rr != null) "${rr} ms RR" else "—",
                        color = UrujMuted, fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun RrIntervalBars(beats: List<LiveStateHolder.Beat>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(UrujSurface),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (beats.isEmpty()) return@Canvas
            val padTop = 8f
            val padBottom = 14f
            val padX = 8f
            val plotH = size.height - padTop - padBottom
            val plotW = size.width - 2 * padX
            // Y-axis: 300ms → bottom, 1500ms → top (typical RR range at rest/effort)
            val yMin = 300f
            val yMax = 1500f
            fun yFor(rr: Int): Float {
                val clamped = rr.toFloat().coerceIn(yMin, yMax)
                val frac = (clamped - yMin) / (yMax - yMin)
                return padTop + (1f - frac) * plotH
            }
            val barWidth = (plotW / beats.size).coerceAtLeast(2f) - 2f
            beats.forEachIndexed { i, beat ->
                val x = padX + (i.toFloat() / beats.size.coerceAtLeast(1)) * plotW
                val y = yFor(beat.rrMs)
                val color = barColorForRr(beat.rrMs)
                drawRect(
                    color = color,
                    topLeft = Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(
                        barWidth,
                        (size.height - padBottom - y).coerceAtLeast(2f),
                    ),
                )
            }
        }
    }
}

private fun barColorForRr(rrMs: Int): Color = when {
    rrMs > 1200 -> UrujZone1     // very slow / recovery
    rrMs > 900 -> UrujZone2      // easy
    rrMs > 700 -> UrujZone3      // moderate
    rrMs > 550 -> UrujZone4      // hard
    else -> UrujZone5             // very fast / max
}

@Composable
private fun StatBlock(
    label: String,
    value: String,
    detail: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(UrujSurfaceHigh.copy(alpha = 0.4f))
            .padding(12.dp),
    ) {
        Text(label, color = UrujMuted, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            color = accent,
            fontWeight = FontWeight.Black,
            fontSize = 22.sp,
        )
        Text(detail, color = UrujMuted, fontSize = 10.sp)
    }
}

private fun currentZoneLabel(bpm: Int?, zones: KarvonenZonesCalculator.Result?): String {
    if (bpm == null) return "—"
    if (zones == null) return "Z?"
    for (z in zones.zones) {
        if (bpm <= z.upperBpm) return "Z${z.number} · ${z.name}"
    }
    return "Z${zones.zones.lastOrNull()?.number ?: 5}+"
}

private fun currentZoneColor(bpm: Int?, zones: KarvonenZonesCalculator.Result?): Color {
    if (bpm == null) return UrujMuted
    if (zones == null) return UrujText
    for (z in zones.zones) {
        if (bpm <= z.upperBpm) return when (z.number) {
            1 -> UrujZone1
            2 -> UrujZone2
            3 -> UrujZone3
            4 -> UrujZone4
            5 -> UrujZone5
            else -> UrujText
        }
    }
    return UrujZone5
}
