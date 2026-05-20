package com.uruj.ui.hud

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uruj.BuildConfig
import com.uruj.data.RiderProfileStore
import com.uruj.domain.PowerZone
import com.uruj.power.ElevationTracker
import com.uruj.power.KarvonenZonesCalculator
import com.uruj.service.RideState
import com.uruj.service.RideStateHolder
import com.uruj.weather.WeatherStatus
import com.uruj.ui.branding.UrujLogo
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujNeonMagenta
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone1
import com.uruj.ui.theme.UrujZoneBelowZ1
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone4
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.delay

@Composable
fun HudScreen(onStopRide: () -> Unit) {
    val state by RideStateHolder.state.collectAsStateWithLifecycle()

    // Stop-ride confirmation dialog state. v0.3.7 fix — user reported
    // pocket-touching the STOP button ended a ride accidentally. OpenTracks-
    // style confirmation guards against this.
    var showStopConfirm by remember { mutableStateOf(false) }

    // Auto-clear the PR flash 6 seconds after it appears so the HUD returns to normal.
    LaunchedEffect(state.prAnnouncedAtMs) {
        if (state.prAnnouncedAtMs != null) {
            delay(6_000)
            RideStateHolder.update { current ->
                current.copy(latestPr = null, prAnnouncedAtMs = null)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        // v0.8.5 — STOP button MUST always be visible. v0.8.0 SessionIntentBar
        // + v0.8.1 HudWaveform added content above; on a OnePlus-class display
        // total content exceeded screen height and pushed STOP offscreen.
        // Rider had no way to end the ride from the HUD on today's 37.78km ride.
        //
        // Architecture: scrollable upper content + FIXED STOP at BottomCenter.
        // STOP is never hidden regardless of scroll position or screen size,
        // and future additions to the gauge stack can grow without re-pushing
        // STOP offscreen. Stop button gets a solid-background container so
        // content scrolling underneath doesn't visually collide.
        val stopButtonHeightWithPadding = 82.dp  // 58dp button + 12dp top/bottom padding
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = stopButtonHeightWithPadding),
        ) {
            HudTopBar(state)
            // v0.8.0 — session intent indicator + CHANGE button (override mid-ride).
            Spacer(Modifier.height(2.dp))
            SessionIntentBar(state)
            // v0.8.1 — inline live HR waveform (last 30s). Only when paired
            // strap is supplying beats. Saves a glance: see HR trend instead
            // of just the current number.
            if (state.bleStrapName != null) {
                Spacer(Modifier.height(4.dp))
                HudWaveform()
            }
            // v0.5.1 — BLE chest strap row, only shown when a paired strap exists
            // for this ride. Off-screen otherwise so non-strap users don't see clutter.
            if (state.bleStrapName != null) {
                Spacer(Modifier.height(2.dp))
                StrapRow(state)
            }
            Spacer(Modifier.height(4.dp))
            GpsRow(state)
            Spacer(Modifier.height(2.dp))
            WindRow(state)
            Spacer(Modifier.height(4.dp))
            // v0.9.16 — twin heroes (HR + Speed) at the top so HR never
            // scrolls offscreen. HR uses Karvonen zone color from the rider's
            // profile (same source-of-truth as TIZ/Route map/Audio coach).
            TwinHero(state)
            Spacer(Modifier.height(12.dp))
            PowerBlock(state, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            StatsGrid(state, modifier = Modifier.fillMaxWidth())
        }

        // Fixed STOP button — always visible at bottom regardless of scroll
        // position or screen size. Solid background hides any content scrolling
        // underneath so the button is always clearly readable.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            StopButton(onClick = { showStopConfirm = true })
        }

        if (showStopConfirm) {
            StopConfirmDialog(
                state = state,
                onConfirm = {
                    showStopConfirm = false
                    onStopRide()
                },
                onCancel = { showStopConfirm = false },
            )
        }

        // PR flash overlay on top of everything — only when fresh.
        val pr = state.latestPr
        val announcedAt = state.prAnnouncedAtMs
        if (pr != null && announcedAt != null) {
            PrFlashOverlay(label = pr.label, watts = pr.watts.toInt())
        }
    }
}

@Composable
private fun StrapRow(state: RideState) {
    // v0.5.1 — BLE chest strap status. Shown only when a paired strap is
    // configured. Three visual states:
    //   CONNECTED + contact ✓ → green, shows BPM + battery
    //   CONNECTED but no contact → amber, prompts "wet electrodes"
    //   DISCONNECTED (paired but no live samples) → red, "OFFLINE"
    val connected = state.bleConnected
    val contact = state.bleContactDetected
    val (label, color) = when {
        !connected -> "STRAP · OFFLINE" to UrujZone5
        contact == false -> "STRAP · NO CONTACT" to UrujZone3
        else -> "STRAP" to UrujZone2
    }
    // v0.5.2 — read the beat-by-beat field updated on every BLE notification
    // (not the 1Hz-throttled latestSample). HUD now reflects strap pulse in
    // real time.
    val bpm = if (connected) state.bleLiveBpm?.toString() ?: "—" else null
    val battery = state.bleBatteryPct
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = color,
            fontWeight = FontWeight.Black,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
            maxLines = 1,
        )
        if (bpm != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$bpm bpm",
                color = UrujText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        state.bleStrapName?.let {
            Text(
                text = it,
                color = UrujMuted,
                fontWeight = FontWeight.Medium,
                fontSize = 9.sp,
                maxLines = 1,
            )
        }
        if (battery != null) {
            Spacer(Modifier.width(6.dp))
            val batColor = when {
                battery >= 30 -> UrujMuted
                battery >= 15 -> UrujZone3
                else -> UrujZone5
            }
            Text(
                text = "${battery}%",
                color = batColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun GpsRow(state: RideState) {
    val accuracy = state.gpsAccuracyMeters
    val (gpsLabel, gpsColor) = when {
        accuracy <= 0f -> "GPS · acquiring lock…" to UrujMuted
        state.gpsAccurate && accuracy <= 10f ->
            "GPS · LOCKED ±${accuracy.toInt()}m" to UrujZone2
        state.gpsAccurate ->
            "GPS · ±${accuracy.toInt()}m" to UrujZone2
        accuracy <= 50f ->
            "GPS · POOR ±${accuracy.toInt()}m" to UrujZone3
        else ->
            "GPS · UNUSABLE ±${accuracy.toInt()}m" to UrujZone5
    }
    val (elevLabel, elevColor) = when (state.elevationSource) {
        ElevationTracker.Source.BAROMETER -> "ALT · BAROMETER" to UrujZone2
        ElevationTracker.Source.DEM -> "ALT · DEM" to UrujZone2
        ElevationTracker.Source.GPS -> "ALT · GPS (noisy)" to UrujZone3
        ElevationTracker.Source.NONE -> "ALT · —" to UrujMuted
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = gpsLabel,
            color = gpsColor,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = elevLabel,
            color = elevColor,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun WindRow(state: RideState) {
    val w = state.weather
    val status = state.weatherStatus
    val now = System.currentTimeMillis()

    // Left side: wind component label OR a state-machine status (loading / failed / waiting).
    val (leftLabel, leftColor) = when (status) {
        is WeatherStatus.WaitingForGps ->
            "WIND · waiting on GPS lock" to UrujMuted
        is WeatherStatus.Fetching ->
            "WIND · fetching…" to UrujMuted
        is WeatherStatus.Failed -> {
            val retryIn = ((status.retryAtMs - now) / 1000L).coerceAtLeast(0L)
            "WIND · offline (retry ${retryIn}s)" to UrujZone3
        }
        is WeatherStatus.Ok, WeatherStatus.Idle -> {
            if (w == null) {
                "WIND · —" to UrujMuted
            } else {
                // When moving with good GPS, show the headwind/tailwind COMPONENT relative
                // to your direction of travel. Otherwise show raw wind speed + cardinal
                // direction so you always have actionable wind info even at a stop.
                val movingEnough = state.gpsAccurate && state.currentSpeedKph > 3.6f
                if (movingEnough) {
                    val headwind = state.headwindMs
                    val absKph = (kotlin.math.abs(headwind) * 3.6f).toInt()
                    when {
                        headwind > 0.5f -> "↓ $absKph KPH HEADWIND" to UrujZone5
                        headwind < -0.5f -> "↑ $absKph KPH TAILWIND" to UrujZone2
                        else -> "→ CROSSWIND $absKph KPH" to UrujZone3
                    }
                } else {
                    val windKph = (w.windSpeedMs * 3.6f).toInt()
                    val cardinal = bearingToCardinal(w.windDirectionDeg)
                    "WIND · $windKph KPH from $cardinal" to UrujZone3
                }
            }
        }
    }

    // Right side: temp + humidity + freshness/next-refresh.
    val rightLabel = buildString {
        if (w != null) {
            append("${w.temperatureCelsius.toInt()}° · ${w.humidityPercent.toInt()}%")
        }
        if (status is WeatherStatus.Ok) {
            val ageMin = ((now - status.fetchedAtMs) / 60_000L).toInt()
            val nextMin = ((status.nextFetchAtMs - now) / 60_000L).coerceAtLeast(0L).toInt()
            if (isNotEmpty()) append(" · ")
            append(if (ageMin <= 0) "live" else "${ageMin}m old")
            append(" · next ${nextMin}m")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = leftLabel,
            color = leftColor,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (rightLabel.isNotEmpty()) {
            Text(
                text = rightLabel,
                color = UrujMuted,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PrFlashOverlay(label: String, watts: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .background(UrujNeonMagenta, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "🔥 NEW PR",
                color = Color.Black,
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
                letterSpacing = 4.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$label · ${watts}W",
                color = Color.Black,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
            )
        }
    }
}

@Composable
private fun HudTopBar(state: RideState) {
    val infinite = rememberInfiniteTransition(label = "rec")
    val pulse by infinite.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "rec_pulse",
    )
    // v0.3.8 service-health: REC dot is now a live health indicator.
    // Green = checkpoint within 40s (healthy), amber = 40-90s (degraded),
    // red = >90s or never (likely dead). Pulse animation only applies when
    // healthy — a frozen-coloured dot is a clearer "something is wrong" signal.
    val checkpoint = state.lastCheckpointAtMs
    val checkpointAgeMs = checkpoint?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
    val health = serviceHealth(state.isPaused, checkpointAgeMs)
    val (recColor, recLabel) = when (health) {
        ServiceHealth.PAUSED -> UrujMuted to "PAUSED"
        ServiceHealth.STARTING -> UrujZone3 to "REC · STARTING"
        ServiceHealth.HEALTHY -> UrujZone2 to "REC"
        ServiceHealth.DEGRADED -> UrujZone3 to "REC · DEGRADED"
        ServiceHealth.STALE -> UrujZone5 to "REC · STALE"
    }
    val dotAlpha = when (health) {
        ServiceHealth.PAUSED -> 0.6f
        ServiceHealth.HEALTHY, ServiceHealth.STARTING -> pulse
        ServiceHealth.DEGRADED, ServiceHealth.STALE -> 1f
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(recColor.copy(alpha = dotAlpha)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = recLabel,
            color = recColor,
            fontWeight = FontWeight.Black,
            fontSize = 12.sp,
            letterSpacing = 3.sp,
        )

        Spacer(Modifier.weight(1f))

        Row(verticalAlignment = Alignment.CenterVertically) {
            UrujLogo(size = 22.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = "URUJ LABS",
                color = UrujText,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                color = UrujMuted,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                maxLines = 1,
            )
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = formatDuration(state.totalElapsedMs),
            color = UrujText,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun SpeedHero(state: RideState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatSpeed(state.currentSpeedKph),
            color = UrujAccent,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Black,
            fontSize = 120.sp,
            letterSpacing = (-4).sp,
        )
        Text(
            text = "KPH",
            color = UrujMuted,
            fontWeight = FontWeight.Black,
            fontSize = 12.sp,
            letterSpacing = 6.sp,
        )
    }
}

/**
 * v0.9.16 — twin heroes. HR (left) + Speed (right), both hero-sized so they
 * compete for the rider's glance equally. HR is Karvonen zone-colored using
 * the rider's profile maxHR + restingHR (single source of truth shared with
 * TIZ, Route map, Audio coach — see [KarvonenZonesCalculator]).
 *
 * Why this exists: pre-v0.9.16 the HEART RATE row sat inside StatsGrid at
 * 22sp and scrolled offscreen on shorter phones once the v0.8.0/0.8.1 stack
 * (SessionIntentBar + Waveform + StrapRow) grew the upper section. Rider
 * had to scroll mid-ride to see live HR — defeats the lab-grade live HR
 * promise. Twin heroes pin HR + Speed above the scroll seam.
 *
 * BLE-first HR (sub-100ms latency on strap), HC-batched fallback. Same
 * cascade as the old StatsGrid HEART RATE cell — no data-flow change.
 *
 * Subtle pulse only when HR is present + above Z3 (effort signal — the
 * rider's eye gets drawn to high-effort moments, not idle baseline).
 */
@Composable
private fun TwinHero(state: RideState) {
    val context = LocalContext.current
    val profileStore = remember { RiderProfileStore(context) }
    val profile by profileStore.profile.collectAsStateWithLifecycle(
        initialValue = com.uruj.domain.RiderProfile(),
    )

    val hr = state.bleLiveBpm ?: state.latestSample?.hrBpm
    val hrFromBle = state.bleLiveBpm != null

    // v0.9.17 — Karvonen classifier returns 0..5 (0 = sub-Z1 below 50% HRR,
    // 1-5 = Z1-Z5). HUD now distinguishes sub-Z1 with its own muted slate
    // color so the rider can tell at a glance "below recovery" vs "in Z1."
    // Same classifier as TIZ, Route map, Bio Lab, Audio coach — single
    // source of truth (v0.9.14 + v0.9.17).
    val zoneColor = if (hr != null && profile.maxHrBpm > profile.restingHrBpm) {
        val z = KarvonenZonesCalculator.classifyKarvonenZone(
            hrBpm = hr,
            hrMax = profile.maxHrBpm,
            hrRest = profile.restingHrBpm.coerceAtLeast(40),
        )
        when (z) {
            0 -> UrujZoneBelowZ1
            1 -> UrujZone1
            2 -> UrujZone2
            3 -> UrujZone3
            4 -> UrujZone4
            else -> UrujZone5
        }
    } else UrujMuted

    // Pulse only at Z3+ (tempo / threshold / VO2) — calm baseline reads steady.
    // Sub-Z1, Z1, Z2 all stay still so the rider's eye isn't drawn to
    // sub-threshold intensity (where they should already be unhurried).
    val infinite = rememberInfiniteTransition(label = "hr_pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.65f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "hr_pulse_alpha",
    )
    val pulseAlpha = if (
        hr != null &&
        zoneColor != UrujMuted &&
        zoneColor != UrujZoneBelowZ1 &&
        zoneColor != UrujZone1 &&
        zoneColor != UrujZone2
    ) pulse else 1f

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        // HR — left
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = hr?.toString() ?: "—",
                color = zoneColor.copy(alpha = pulseAlpha),
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                fontSize = 84.sp,
                letterSpacing = (-3).sp,
                maxLines = 1,
            )
            Text(
                text = when {
                    hr == null -> "BPM · no source"
                    hrFromBle -> "BPM · strap"
                    else -> "BPM · band"
                },
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 3.sp,
                maxLines = 1,
            )
        }
        // Speed — right
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formatSpeed(state.currentSpeedKph),
                color = UrujAccent,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                fontSize = 84.sp,
                letterSpacing = (-3).sp,
                maxLines = 1,
            )
            Text(
                text = "KPH",
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 6.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PowerBlock(state: RideState, modifier: Modifier = Modifier) {
    val zone = state.currentZone
    val zoneColor = zone?.color() ?: UrujMuted
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(UrujSurface)
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "≈",
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 22.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = state.smoothedPower3sWatts.toInt().toString(),
                color = zoneColor,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                fontSize = 56.sp,
                letterSpacing = (-2).sp,
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = "WATTS",
                    color = UrujMuted,
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    letterSpacing = 3.sp,
                )
                Text(
                    text = zone?.let { "ZONE ${zoneEnumIndex(it)} · ${it.label.uppercase()}" }
                        ?: "—",
                    color = zoneColor,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "30s",
                    color = UrujMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                )
                Text(
                    text = state.smoothedPower30sWatts.toInt().toString(),
                    color = UrujText,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        ZoneBar(currentZone = zone)
    }
}

@Composable
private fun ZoneBar(currentZone: PowerZone?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp)),
    ) {
        PowerZone.entries.forEach { zone ->
            val isActive = zone == currentZone
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(if (isActive) zone.color() else zone.color().copy(alpha = 0.18f)),
            )
        }
    }
}

@Composable
private fun StatsGrid(state: RideState, modifier: Modifier = Modifier) {
    // v0.9.16 — HEART RATE row removed. HR now lives in [TwinHero] at the
    // top of the HUD as a hero readout (was 22sp inside this grid; scrolled
    // offscreen on shorter phones). MAX HR (in-ride peak observed) takes
    // its slot here so the rider still has the post-ride number in view.
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(UrujSurface)
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(16.dp))
            .padding(8.dp),
    ) {
        StatRow(
            left = Stat("DISTANCE", "%.2f".format(state.totalDistanceMeters / 1000), "km"),
            right = Stat("MOVING", formatDuration(state.movingTimeMs), null),
        )
        Divider()
        StatRow(
            left = Stat("AVG SPEED", "%.1f".format(state.averageSpeedMovingKph), "kph"),
            right = Stat("GRADE", "%+.1f".format(state.currentGradePercent), "%"),
        )
        Divider()
        StatRow(
            // v0.3.7: surface CURRENT altitude (ticks every sample) alongside
            // total gain. Rider can see live altitude feedback during climbs
            // instead of only the slow-accumulating total.
            left = Stat("ALT", state.currentAltitudeMeters.toInt().toString(), "m"),
            right = Stat("ELEV ↑", state.totalElevGainMeters.toInt().toString(), "m"),
        )
        Divider()
        StatRow(
            // v0.9.16: peak HR seen in-ride. Previously had no visible home
            // (only surfaced post-ride). Useful live to know whether today's
            // peak crossed personal milestones.
            left = Stat(
                label = "MAX HR",
                value = if (state.maxHrBpmObserved > 0) state.maxHrBpmObserved.toString() else "—",
                unit = if (state.maxHrBpmObserved > 0) "bpm" else null,
                accent = if (state.maxHrBpmObserved > 0) UrujNeonMagenta else UrujMuted,
            ),
            // v0.3.7: 30s-checkpoint heartbeat. Lets the rider verify the
            // service is alive even when the phone is backgrounded (the
            // foreground notification can be hidden by aggressive OEM ROMs).
            right = Stat(
                label = "LAST SAVE",
                value = state.lastCheckpointAtMs?.let { lastSavedSecondsAgo(it).toString() } ?: "—",
                unit = if (state.lastCheckpointAtMs != null) "s ago" else "no save yet",
                accent = UrujAccent,
            ),
        )
    }
}

private fun lastSavedSecondsAgo(lastCheckpointMs: Long): Long {
    return ((System.currentTimeMillis() - lastCheckpointMs) / 1000L).coerceAtLeast(0L)
}

private enum class ServiceHealth { PAUSED, STARTING, HEALTHY, DEGRADED, STALE }

private fun serviceHealth(isPaused: Boolean, checkpointAgeMs: Long?): ServiceHealth {
    if (isPaused) return ServiceHealth.PAUSED
    if (checkpointAgeMs == null) return ServiceHealth.STARTING
    return when {
        checkpointAgeMs < 40_000L -> ServiceHealth.HEALTHY
        checkpointAgeMs < 90_000L -> ServiceHealth.DEGRADED
        else -> ServiceHealth.STALE
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(UrujSurfaceHigh),
    )
}

private data class Stat(
    val label: String,
    val value: String,
    val unit: String?,
    val accent: Color = UrujText,
)

@Composable
private fun StatRow(left: Stat, right: Stat) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatCell(stat = left, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(38.dp)
                .background(UrujSurfaceHigh),
        )
        StatCell(stat = right, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(stat: Stat, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = stat.label,
            color = UrujMuted,
            fontWeight = FontWeight.Black,
            fontSize = 9.sp,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = stat.value,
                color = stat.accent,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                fontSize = 22.sp,
                letterSpacing = (-0.5).sp,
            )
            if (stat.unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stat.unit,
                    color = UrujMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StopConfirmDialog(
    state: RideState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val distanceKm = "%.2f".format(state.totalDistanceMeters / 1000)
    val moving = formatDuration(state.movingTimeMs)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                "End ride?",
                color = UrujText,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
            )
        },
        text = {
            Column {
                Text(
                    "You've ridden $distanceKm km in $moving.",
                    color = UrujText,
                    fontSize = 14.sp,
                )
                if (state.totalDistanceMeters < 500) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠ This ride is very short — confirm you really want to end it.",
                        color = UrujZone3,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text(
                    "END RIDE",
                    color = UrujZone5,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text(
                    "KEEP GOING",
                    color = UrujAccent,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
            }
        },
        containerColor = UrujSurface,
    )
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = UrujZone5,
            contentColor = Color.White,
        ),
    ) {
        Text(
            text = "■  STOP RIDE",
            fontWeight = FontWeight.Black,
            fontSize = 16.sp,
            letterSpacing = 4.sp,
        )
    }
}

private fun PowerZone.color(): Color = when (this) {
    PowerZone.Z1 -> UrujZone1
    PowerZone.Z2 -> UrujZone2
    PowerZone.Z3 -> UrujZone3
    PowerZone.Z4 -> UrujZone4
    PowerZone.Z5 -> UrujZone5
}

private fun zoneEnumIndex(zone: PowerZone): Int = PowerZone.entries.indexOf(zone) + 1

private fun formatSpeed(kph: Float): String =
    if (kph < 0.05f) "0" else "%.1f".format(kph)

private fun bearingToCardinal(deg: Float): String {
    val normalized = ((deg % 360f) + 360f) % 360f
    val sectors = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val idx = ((normalized + 22.5f) / 45f).toInt() % 8
    return sectors[idx]
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%d:%02d:%02d".format(h, m, s)
}

