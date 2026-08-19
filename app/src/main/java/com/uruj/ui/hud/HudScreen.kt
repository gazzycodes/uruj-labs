package com.uruj.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
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
import com.uruj.ui.branding.UrujLogo
import com.uruj.ui.components.ThemeToggleButton
import com.uruj.ui.theme.UrujOnAccent
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujCadence
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujNeonMagenta
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone1
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone4
import com.uruj.ui.theme.UrujZone5
import com.uruj.ui.theme.UrujZoneBelowZ1
import com.uruj.weather.WeatherStatus
import kotlinx.coroutines.delay

/**
 * v0.9.78 — the HUD, rebuilt around three hero metrics.
 *
 * ## What changed and why
 *
 * **Three heroes, not two.** SPEED · CADENCE · HR now share the top of the
 * screen at equal weight, each with a segmented range bar underneath (see
 * [HudMetric]). Cadence joins as a first-class metric because the bike now has
 * a sensor for it; when no cadence sensor is paired the row falls back to the
 * previous two-up SPEED + HR layout, so nothing is lost for a strap-only ride.
 *
 * **Digits are sized from the device, not from a constant.** Each hero cell
 * measures itself and picks the largest font that fits, so the readout is as
 * big as the phone physically allows rather than a number tuned on one handset.
 *
 * **Nothing scrolls in practice.** Everything fits above the fixed control bar
 * on a normal phone; the scroll container is retained purely as the v0.8.5
 * safety net (content can never push the ride controls off-screen), and the
 * waveform is dropped automatically on short displays.
 *
 * **Ending a ride is a swipe, not a tap.** See [SwipeToEndControl] — rain on
 * the screen was ending rides.
 *
 * **No idle animation anywhere.** The two always-on pulse loops (REC dot, HR
 * glow) are gone. Every remaining animation is driven by a value that actually
 * changed. On a ride the display is forced on for hours, so the HUD's job is to
 * add as close to zero as possible on top of GPS + two BLE links.
 */
@Composable
fun HudScreen(onStopRide: () -> Unit, onTogglePause: () -> Unit = {}) {
    val state by RideStateHolder.state.collectAsStateWithLifecycle()

    // Kept from v0.3.7: a sub-500 m ride is much more likely to be an accident
    // than an intention, so that one case still asks. Every other ride is ended
    // by the swipe alone — a second confirmation in the rain helps nobody.
    var showShortRideConfirm by remember { mutableStateOf(false) }

    // Auto-clear the PR flash 6 seconds after it appears.
    LaunchedEffect(state.prAnnouncedAtMs) {
        if (state.prAnnouncedAtMs != null) {
            delay(6_000)
            RideStateHolder.update { current ->
                current.copy(latestPr = null, prAnnouncedAtMs = null)
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        // Short displays drop the waveform rather than pushing content into a
        // scroll the rider would have to perform one-handed at speed.
        val roomy = maxHeight >= 640.dp
        val controlBarHeight = 82.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = controlBarHeight),
        ) {
            HudTopBar(state)
            if (state.isPaused) {
                Spacer(Modifier.height(6.dp))
                PausedBanner(manual = state.manuallyPaused)
            }
            Spacer(Modifier.height(5.dp))
            SensorStrip(state)
            Spacer(Modifier.height(3.dp))
            WindRow(state)
            Spacer(Modifier.height(3.dp))
            SessionIntentBar(state)
            Spacer(Modifier.height(if (roomy) 12.dp else 8.dp))
            HeroRow(state)
            Spacer(Modifier.height(if (roomy) 12.dp else 8.dp))
            if (roomy && state.bleStrapName != null) {
                HudWaveform()
                Spacer(Modifier.height(12.dp))
            }
            StatsPanel(state)
        }

        // Fixed control bar — never scrolls away (the v0.8.5 guarantee).
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PauseToggleControl(
                    manuallyPaused = state.manuallyPaused,
                    onClick = onTogglePause,
                    modifier = Modifier.weight(0.42f),
                )
                SwipeToEndControl(
                    subtitle = "%.2f km · %s".format(
                        state.totalDistanceMeters / 1000,
                        formatDuration(state.movingTimeMs),
                    ),
                    onConfirmed = {
                        if (state.totalDistanceMeters < SHORT_RIDE_METERS) {
                            showShortRideConfirm = true
                        } else {
                            onStopRide()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (showShortRideConfirm) {
            ShortRideConfirmDialog(
                state = state,
                onConfirm = {
                    showShortRideConfirm = false
                    onStopRide()
                },
                onCancel = { showShortRideConfirm = false },
            )
        }

        val pr = state.latestPr
        val announcedAt = state.prAnnouncedAtMs
        if (pr != null && announcedAt != null) {
            PrFlashOverlay(label = pr.label, watts = pr.watts.toInt())
        }
    }
}

/**
 * The three hero readouts. Falls back to two-up when no cadence sensor is
 * paired, so a strap-only ride gets bigger SPEED and HR digits instead of a
 * permanent dead cell.
 */
@Composable
private fun HeroRow(state: RideState) {
    val context = LocalContext.current
    val profileStore = remember { RiderProfileStore(context) }
    val profile by profileStore.profile.collectAsStateWithLifecycle(
        initialValue = com.uruj.domain.RiderProfile(),
    )

    val hr = state.bleLiveBpm ?: state.latestSample?.hrBpm
    val hrFromBle = state.bleLiveBpm != null
    val restingHr = profile.restingHrBpm.coerceAtLeast(40)
    val maxHr = profile.maxHrBpm
    val zonesValid = maxHr > restingHr

    // Same Karvonen classifier as TIZ / route map / Bio Lab / audio coach —
    // one source of truth for what "zone" means (v0.9.14).
    val zoneIndex = if (hr != null && zonesValid) {
        KarvonenZonesCalculator.classifyKarvonenZone(hr, maxHr, restingHr)
    } else null
    val hrColor = when (zoneIndex) {
        0 -> UrujZoneBelowZ1
        1 -> UrujZone1
        2 -> UrujZone2
        3 -> UrujZone3
        4 -> UrujZone4
        5 -> UrujZone5
        else -> UrujMuted
    }
    val hrProgress = if (hr != null && zonesValid) {
        (hr - restingHr).toFloat() / (maxHr - restingHr).toFloat()
    } else 0f

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val kph = state.currentSpeedKph
        HudMetric(
            value = if (kph < 0.05f) "0" else kph.toInt().toString(),
            suffix = if (kph < 0.05f) null else ".%d".format(((kph * 10f).toInt() % 10)),
            unit = "KPH",
            color = UrujAccent,
            progress = kph / SPEED_SCALE_KPH,
            modifier = Modifier.weight(1f),
        )

        if (state.cadenceSensorName != null) {
            CadenceMetric(state, modifier = Modifier.weight(1f))
        }

        HudMetric(
            value = hr?.toString() ?: PLACEHOLDER,
            suffix = null,
            unit = when {
                hr == null -> "BPM · NO SOURCE"
                hrFromBle -> "BPM · STRAP"
                else -> "BPM · BAND"
            },
            color = hrColor,
            progress = hrProgress,
            // The full Karvonen zone map stays visible in the unlit track, so the
            // rider can see how far Z2 is from where they are without arithmetic.
            bands = if (zonesValid) HR_ZONE_BANDS else emptyList(),
            dim = hr == null,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Cadence hero cell. Three honest states rather than one number:
 *  - connected + crank data → live rpm, green inside the 80-95 target band
 *  - connected but wheel-only → "—" and "RPM · WHEEL MODE" (the dual-mode
 *    sensor is configured as a speed sensor; the app is fine, the mount isn't)
 *  - disconnected → "—" and "RPM · OFFLINE"
 */
@Composable
private fun CadenceMetric(state: RideState, modifier: Modifier = Modifier) {
    val rpm = state.cadenceRpm
    val connected = state.cadenceConnected
    val wheelOnly = connected && !state.cadenceHasCrankData
    val inTarget = rpm != null && rpm >= CADENCE_TARGET_LOW && rpm <= CADENCE_TARGET_HIGH
    HudMetric(
        value = when {
            !connected || wheelOnly -> PLACEHOLDER
            rpm == null -> PLACEHOLDER
            else -> rpm.toString()
        },
        suffix = null,
        unit = when {
            wheelOnly -> "RPM · WHEEL MODE"
            !connected -> "RPM · OFFLINE"
            else -> "RPM · CRANK"
        },
        color = if (inTarget) UrujZone2 else UrujCadence,
        progress = (rpm ?: 0) / CADENCE_SCALE_RPM,
        // The endurance target band stays lit-dim even at 0 rpm, so the rider
        // always knows where they're aiming rather than guessing at a number.
        bands = CADENCE_TARGET_BANDS,
        dim = !connected || wheelOnly,
        modifier = modifier,
    )
}

/**
 * Sensor status line — GPS, chest strap, cadence sensor. One row of chips
 * instead of the previous stack of full-width rows: the same information in a
 * third of the vertical space, which is what buys the third hero metric its
 * room.
 */
@Composable
private fun SensorStrip(state: RideState) {
    val accuracy = state.gpsAccuracyMeters
    val (gpsLabel, gpsColor) = when {
        accuracy <= 0f -> "GPS ACQ…" to UrujMuted
        state.gpsAccurate && accuracy <= 10f -> "GPS ±${accuracy.toInt()}m" to UrujZone2
        state.gpsAccurate -> "GPS ±${accuracy.toInt()}m" to UrujZone2
        accuracy <= 50f -> "GPS POOR ±${accuracy.toInt()}" to UrujZone3
        else -> "GPS ✕ ±${accuracy.toInt()}" to UrujZone5
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SensorChip(label = gpsLabel, color = gpsColor, modifier = Modifier.weight(1f))

        if (state.bleStrapName != null) {
            val (label, color) = when {
                !state.bleConnected -> "STRAP OFF" to UrujZone5
                state.bleContactDetected == false -> "STRAP ⚠ SKIN" to UrujZone3
                else -> {
                    val battery = state.bleBatteryPct
                    val suffix = if (battery != null) " · $battery%" else ""
                    "STRAP ✓$suffix" to UrujZone2
                }
            }
            SensorChip(label = label, color = color, modifier = Modifier.weight(1f))
        }

        if (state.cadenceSensorName != null) {
            val battery = state.cadenceBatteryPct
            val (label, color) = when {
                !state.cadenceConnected -> "CAD OFF" to UrujZone5
                !state.cadenceHasCrankData -> "CAD WHEEL?" to UrujZone3
                // Below 20% the sensor is close enough to dying that the rider
                // should hear about it before the next ride, not during it.
                battery != null && battery < 20 -> "CAD ⚠ $battery%" to UrujZone3
                battery != null -> "CAD ✓ · $battery%" to UrujZone2
                else -> "CAD ✓" to UrujZone2
            }
            SensorChip(label = label, color = color, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SensorChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            color = color,
            fontWeight = FontWeight.Black,
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            maxLines = 1,
        )
    }
}

/**
 * A mis-tapped PAUSE has to be impossible to miss — that's the trade that lets
 * PAUSE stay a plain tap while ending a ride needs a swipe.
 */
@Composable
private fun PausedBanner(manual: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(UrujZone3.copy(alpha = 0.18f))
            .border(1.dp, UrujZone3, RoundedCornerShape(8.dp))
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (manual) "❚❚  PAUSED — TAP RESUME TO CONTINUE" else "❚❚  AUTO-PAUSED — STOPPED",
            color = UrujZone3,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun WindRow(state: RideState) {
    val w = state.weather
    val status = state.weatherStatus
    val now = System.currentTimeMillis()

    val (leftLabel, leftColor) = when (status) {
        is WeatherStatus.WaitingForGps -> "WIND · waiting on GPS lock" to UrujMuted
        is WeatherStatus.Fetching -> "WIND · fetching…" to UrujMuted
        is WeatherStatus.Failed -> {
            val retryIn = ((status.retryAtMs - now) / 1000L).coerceAtLeast(0L)
            "WIND · offline (retry ${retryIn}s)" to UrujZone3
        }
        is WeatherStatus.Ok, WeatherStatus.Idle -> {
            if (w == null) {
                "WIND · —" to UrujMuted
            } else {
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
                    "WIND · $windKph KPH from ${bearingToCardinal(w.windDirectionDeg)}" to UrujZone3
                }
            }
        }
    }

    val rightLabel = buildString {
        if (w != null) {
            append("${w.temperatureCelsius.toInt()}° · ${w.humidityPercent.toInt()}%")
        }
        if (status is WeatherStatus.Ok) {
            val ageMin = ((now - status.fetchedAtMs) / 60_000L).toInt()
            if (isNotEmpty()) append(" · ")
            append(if (ageMin <= 0) "live" else "${ageMin}m old")
        }
    }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = leftLabel,
            color = leftColor,
            fontWeight = FontWeight.Black,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (rightLabel.isNotEmpty()) {
            Text(
                text = rightLabel,
                color = UrujMuted,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
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
                .background(UrujNeonMagenta, shape = RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "🔥 NEW PR",
                color = UrujOnAccent,
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
                letterSpacing = 4.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$label · ${watts}W",
                color = UrujOnAccent,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
            )
        }
    }
}

/**
 * Top bar: service health, branding, elapsed clock.
 *
 * v0.9.78 — the pulsing REC dot is gone. It animated forever regardless of what
 * the recorder was doing, and the checkpoint age it was meant to convey is now
 * simply printed ("REC · 12s"): an honest number the rider can read, instead of
 * a blink they have to interpret. The elapsed clock ticking every second is
 * already all the liveness proof a HUD needs.
 */
@Composable
private fun HudTopBar(state: RideState) {
    val checkpoint = state.lastCheckpointAtMs
    val checkpointAgeMs = checkpoint?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
    val health = serviceHealth(state.isPaused, checkpointAgeMs)
    val ageLabel = checkpointAgeMs?.let { " · ${it / 1000}s" } ?: ""
    val (recColor, recLabel) = when (health) {
        ServiceHealth.PAUSED -> UrujMuted to "PAUSED"
        ServiceHealth.STARTING -> UrujZone3 to "REC · STARTING"
        ServiceHealth.HEALTHY -> UrujZone2 to "REC$ageLabel"
        ServiceHealth.DEGRADED -> UrujZone3 to "REC · SLOW$ageLabel"
        ServiceHealth.STALE -> UrujZone5 to "REC · STALE$ageLabel"
    }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(recColor),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = recLabel,
            color = recColor,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        UrujLogo(size = 18.dp)
        Spacer(Modifier.width(5.dp))
        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            color = UrujMuted,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        // v0.9.79 — reachable mid-ride: the sun comes out, the HUD washes out,
        // one tap fixes it without stopping or leaving the screen.
        ThemeToggleButton()
        Spacer(Modifier.weight(1f))
        Text(
            text = formatDuration(state.totalElapsedMs),
            color = UrujText,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            letterSpacing = 1.sp,
            maxLines = 1,
        )
    }
}

/**
 * Secondary stats, three per row. The list is built from what the ride actually
 * has: the three cadence figures only exist when a cadence sensor is paired,
 * so a strap-only ride shows a tidy 3×3 rather than a grid of dashes.
 */
@Composable
private fun StatsPanel(state: RideState) {
    val elevSourceTag = when (state.elevationSource) {
        ElevationTracker.Source.BAROMETER -> "m · baro"
        ElevationTracker.Source.DEM -> "m · dem"
        ElevationTracker.Source.GPS -> "m · gps"
        ElevationTracker.Source.NONE -> "m"
    }
    val zone = state.currentZone
    val stats = buildList {
        add(HudStatSpec("DISTANCE", "%.2f".format(state.totalDistanceMeters / 1000), "km", UrujText))
        add(HudStatSpec("MOVING", formatDuration(state.movingTimeMs), null, UrujText))
        add(HudStatSpec("AVG SPD", "%.1f".format(state.averageSpeedMovingKph), "kph", UrujAccent))
        add(HudStatSpec("ELEV ↑", state.totalElevGainMeters.toInt().toString(), "m", UrujText))
        add(HudStatSpec("ALT", state.currentAltitudeMeters.toInt().toString(), elevSourceTag, UrujText))
        add(HudStatSpec("GRADE", "%+.1f".format(state.currentGradePercent), "%", UrujText))
        add(
            HudStatSpec(
                label = "≈ POWER",
                value = state.smoothedPower3sWatts.toInt().toString(),
                unit = zone?.let { "W · z${PowerZone.entries.indexOf(it) + 1}" } ?: "W",
                accent = zone?.color() ?: UrujMuted,
            ),
        )
        add(
            HudStatSpec(
                label = "MAX HR",
                value = if (state.maxHrBpmObserved > 0) state.maxHrBpmObserved.toString() else "—",
                unit = if (state.maxHrBpmObserved > 0) "bpm" else null,
                accent = if (state.maxHrBpmObserved > 0) UrujNeonMagenta else UrujMuted,
            ),
        )
        add(HudStatSpec("MAX SPD", "%.1f".format(state.maxSpeedMs * 3.6f), "kph", UrujText))
        if (state.cadenceSensorName != null) {
            add(
                HudStatSpec(
                    label = "AVG CAD",
                    value = if (state.averageCadenceRpm > 0) state.averageCadenceRpm.toString() else "—",
                    unit = if (state.averageCadenceRpm > 0) "rpm" else null,
                    accent = UrujCadence,
                ),
            )
            add(
                HudStatSpec(
                    label = "PEDALLING",
                    value = "${(state.pedalingRatio * 100).toInt()}",
                    unit = "% of moving",
                    accent = UrujCadence,
                ),
            )
            add(
                HudStatSpec(
                    label = "STROKES",
                    value = state.totalCrankRevs.toString(),
                    unit = null,
                    accent = UrujCadence,
                ),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(UrujSurface)
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        stats.chunked(3).forEachIndexed { index, row ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(UrujSurfaceHigh),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEach { stat ->
                    HudStat(
                        label = stat.label,
                        value = stat.value,
                        unit = stat.unit,
                        accent = stat.accent,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep the last row's columns aligned with the rows above it.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private data class HudStatSpec(
    val label: String,
    val value: String,
    val unit: String?,
    val accent: Color,
)

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

/**
 * The one remaining tap-confirm: a ride under [SHORT_RIDE_METERS] is far more
 * likely to be an accidental swipe two minutes in than a deliberate finish.
 */
@Composable
private fun ShortRideConfirmDialog(
    state: RideState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val distanceKm = "%.2f".format(state.totalDistanceMeters / 1000)
    val moving = formatDuration(state.movingTimeMs)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text("End this short ride?", color = UrujText, fontWeight = FontWeight.Black, fontSize = 20.sp)
        },
        text = {
            Column {
                Text(
                    "Only $distanceKm km in $moving so far.",
                    color = UrujText,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "⚠ This ride is very short — confirm you really want to end it.",
                    color = UrujZone3,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text("END RIDE", color = UrujZone5, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text("KEEP GOING", color = UrujAccent, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
        },
        containerColor = UrujSurface,
    )
}

@Composable
@ReadOnlyComposable
private fun PowerZone.color(): Color = when (this) {
    PowerZone.Z1 -> UrujZone1
    PowerZone.Z2 -> UrujZone2
    PowerZone.Z3 -> UrujZone3
    PowerZone.Z4 -> UrujZone4
    PowerZone.Z5 -> UrujZone5
}

private fun bearingToCardinal(deg: Float): String {
    val normalized = ((deg % 360f) + 360f) % 360f
    val sectors = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return sectors[((normalized + 22.5f) / 45f).toInt() % 8]
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%d:%02d:%02d".format(h, m, s)
}

/** Full-scale of the SPEED bar. 50 kph covers everything short of a descent PB. */
private const val SPEED_SCALE_KPH = 50f

/** Full-scale of the CADENCE bar. */
private const val CADENCE_SCALE_RPM = 120f

/** Endurance cadence target band — the range the bar highlights permanently. */
private const val CADENCE_TARGET_LOW = 80
private const val CADENCE_TARGET_HIGH = 95

private val CADENCE_TARGET_BANDS: List<MetricBand>
    @Composable @ReadOnlyComposable get() = listOf(
    MetricBand(
        fromFraction = CADENCE_TARGET_LOW / CADENCE_SCALE_RPM,
        toFraction = CADENCE_TARGET_HIGH / CADENCE_SCALE_RPM,
        color = UrujZone2,
    ),
)

/**
 * Karvonen zones as fractions of the rider's HR reserve — the same 50/60/70/80/90
 * boundaries [KarvonenZonesCalculator.classifyKarvonenZone] uses, so the bar's
 * band map and the digit's colour can never disagree.
 */
private val HR_ZONE_BANDS: List<MetricBand>
    @Composable @ReadOnlyComposable get() = listOf(
    MetricBand(0f, 0.50f, UrujZoneBelowZ1),
    MetricBand(0.50f, 0.60f, UrujZone1),
    MetricBand(0.60f, 0.70f, UrujZone2),
    MetricBand(0.70f, 0.80f, UrujZone3),
    MetricBand(0.80f, 0.90f, UrujZone4),
    MetricBand(0.90f, 1.00f, UrujZone5),
)

/** Below this, ending a ride still asks for a tap confirmation. */
private const val SHORT_RIDE_METERS = 500.0
