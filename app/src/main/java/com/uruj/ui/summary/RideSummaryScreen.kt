package com.uruj.ui.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uruj.BuildConfig
import com.uruj.service.RideState
import com.uruj.ui.branding.UrujLogo
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujNeonMagenta
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone1
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone4
import com.uruj.ui.theme.UrujZone5
import kotlin.math.pow

@Composable
fun RideSummaryScreen(
    state: RideState,
    onDone: () -> Unit,
    onOpenMap: (sessionId: String) -> Unit = {},
    viewModel: RideSummaryViewModel = viewModel(),
) {
    // Kick off Health Connect HR enrichment if we have a valid ride to enrich. The VM
    // short-circuits if the summary already has HR data from a prior poll, so this is
    // safe whether the user just finished a ride or is viewing one from history.
    LaunchedEffect(state.sessionId) {
        val sessionId = state.sessionId
        val startMs = state.startedAtMs
        if (sessionId != null && startMs != null) {
            val endMs = startMs + state.totalElapsedMs
            viewModel.startHrEnrichment(sessionId, startMs, endMs)
        }
    }
    val hrState by viewModel.hrEnrichment.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // GraphicsLayer captures whatever's drawn inside it — we wrap the summary content
    // and on SHARE tap, snapshot it to a JPG + launch the system share chooser.
    val captureLayer = rememberGraphicsLayer()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .drawWithContent {
                    captureLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(captureLayer)
                },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UrujLogo(size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "URUJ LABS",
                        color = UrujText,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        letterSpacing = 4.sp,
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        color = UrujAccent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        letterSpacing = 1.5.sp,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("RIDE COMPLETE", color = UrujAccent, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(4.dp))
            Text("Session Summary", color = UrujText, fontWeight = FontWeight.Black, fontSize = 28.sp)

            Spacer(Modifier.height(20.dp))

            HeroCard(state)
            Spacer(Modifier.height(12.dp))
            PowerCard(state)
            Spacer(Modifier.height(12.dp))
            // Time-in-zone breakdown — only shown when HR samples exist for the ride.
            // v0.8.2 — reads ride NDJSON (strap-first via BLE merger) when available,
            // falling back to HC's post-batch sync. hrSourceBreakdown surfaces which
            // sensor(s) produced the data so the rider can see "from chest strap ✓"
            // vs "from band (batched)" vs "78% strap + 22% band" etc.
            val tiz by viewModel.timeInZone.collectAsStateWithLifecycle()
            val hrSourceBreakdown by viewModel.hrSourceBreakdown.collectAsStateWithLifecycle()
            // v0.8.5 — HR card label now reflects the actual source. Hardcoded
            // "from Health Connect" was lying when data came from strap NDJSON
            // (the typical case for paired-strap rides since v0.8.2).
            HrCard(hrState, sourceBreakdown = hrSourceBreakdown)
            if (tiz != null) {
                Spacer(Modifier.height(12.dp))
                TimeInZoneCard(tiz!!, sourceBreakdown = hrSourceBreakdown)
            }
            Spacer(Modifier.height(12.dp))
            ClimbCard(state)
            Spacer(Modifier.height(12.dp))
            EffortCard(state)

            Spacer(Modifier.height(28.dp))

            // VIEW MAP — only when we have a session ID to look up the NDJSON.
            // Placed above SHARE/DONE because the map is the primary post-ride
            // exploration once the rider's actually curious about their route.
            val sessionId = state.sessionId
            if (sessionId != null) {
                Button(
                    onClick = { onOpenMap(sessionId) },
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UrujSurfaceHigh,
                        contentColor = UrujAccent,
                    ),
                ) {
                    Text(
                        "▶ VIEW MAP",
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        letterSpacing = 4.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val image = captureLayer.toImageBitmap().asAndroidBitmap()
                            val sid = state.sessionId ?: "ride"
                            val uri = ShareImage.save(context, image, sid)
                            ShareImage.launchShareIntent(context, uri)
                        }
                    },
                    modifier = Modifier.weight(1f).height(58.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UrujNeonMagenta,
                        contentColor = Color.Black,
                    ),
                ) {
                    Text(
                        "↗ SHARE",
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        letterSpacing = 4.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = onDone,
                    modifier = Modifier.weight(1f).height(58.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UrujAccent,
                        contentColor = Color.Black,
                    ),
                ) {
                    Text("DONE", fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 4.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Your full ride data is saved at \nAndroid/data/com.uruj/files/rides/${state.sessionId}.ndjson",
                color = UrujMuted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HeroCard(state: RideState) {
    Card("RIDE") {
        BigStatRow(
            left = "%.2f km".format(state.totalDistanceMeters / 1000),
            leftLabel = "DISTANCE",
            right = formatDuration(state.movingTimeMs),
            rightLabel = "MOVING TIME",
        )
        Divider()
        BigStatRow(
            left = "%.1f kph".format(state.averageSpeedMovingKph),
            leftLabel = "AVG SPEED",
            right = "%.1f kph".format(state.maxSpeedMs * 3.6f),
            rightLabel = "MAX SPEED",
        )
    }
}

@Composable
private fun PowerCard(state: RideState) {
    Card("POWER (estimated)", accent = UrujZone3) {
        BigStatRow(
            left = "≈ ${state.averagePowerWatts.toInt()} W",
            leftLabel = "AVERAGE",
            right = "${state.maxPowerWatts.toInt()} W",
            rightLabel = "PEAK",
        )
        Divider()
        BigStatRow(
            left = "%.1f kJ".format(state.totalWorkKj),
            leftLabel = "TOTAL WORK",
            right = "${state.totalWorkKj.toInt()} kcal",
            rightLabel = "CALORIES (≈)",
        )
    }
}

@Composable
private fun HrCard(
    hrState: HrEnrichmentState,
    sourceBreakdown: Map<com.uruj.domain.SensorSource, Int> = emptyMap(),
) {
    when (hrState) {
        is HrEnrichmentState.Done -> {
            Card("HEART RATE", accent = UrujNeonMagenta) {
                if (hrState.avgHrBpm == null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "No HR samples found in Health Connect for this ride.",
                            color = UrujMuted,
                            fontSize = 12.sp,
                        )
                    }
                } else {
                    BigStatRow(
                        left = "${hrState.avgHrBpm} bpm",
                        leftLabel = "AVERAGE",
                        right = "${hrState.maxHrBpm ?: "—"} bpm",
                        rightLabel = "MAX",
                    )
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // v0.8.5 — source-aware label. NDJSON-first path (since v0.8.2)
                        // means data usually comes from strap, not HC. Pre-v0.8.5 this
                        // string was hardcoded "from Health Connect" regardless of
                        // actual provenance.
                        val sourceLabel = formatHrSampleSource(hrState.sampleCount, sourceBreakdown)
                        Text(
                            text = sourceLabel,
                            color = UrujMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (hrState.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(10.dp),
                                color = UrujNeonMagenta,
                                strokeWidth = 1.5.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "↻ checking Samsung…",
                                color = UrujNeonMagenta,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
        is HrEnrichmentState.Polling -> {
            Card("HEART RATE", accent = UrujNeonMagenta) {
                Text(
                    text = "Syncing from Health Connect… (${hrState.secondsElapsed}s — usually 30–90s)",
                    color = UrujMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        HrEnrichmentState.TimedOut -> {
            Card("HEART RATE", accent = UrujMuted) {
                Text(
                    text = "No HR data after 5 min. Check Samsung Health → Health Connect sharing, or wear your band next ride.",
                    color = UrujMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        HrEnrichmentState.NotAvailable, HrEnrichmentState.Idle -> Unit // hide card entirely
    }
}

@Composable
private fun TimeInZoneCard(
    result: com.uruj.power.TimeInZoneCalculator.Result,
    sourceBreakdown: Map<com.uruj.domain.SensorSource, Int> = emptyMap(),
) {
    Card("TIME IN ZONE", accent = UrujNeonMagenta) {
        // v0.8.2 — source breakdown badge above the bar so rider knows which
        // sensor(s) produced the HR data feeding this analysis. Strap-sourced
        // TIZ is more accurate than HC-batched (per-second cadence vs ~30s).
        if (sourceBreakdown.isNotEmpty()) {
            val total = sourceBreakdown.values.sum()
            val badge = if (sourceBreakdown.size == 1) {
                val only = sourceBreakdown.keys.first()
                when (only) {
                    com.uruj.domain.SensorSource.STRAP -> "from chest strap ✓"
                    com.uruj.domain.SensorSource.BAND -> "from band (batched)"
                    com.uruj.domain.SensorSource.MIXED -> "mixed sources"
                    com.uruj.domain.SensorSource.UNKNOWN_LEGACY -> "legacy (pre-v0.8.2)"
                }
            } else {
                sourceBreakdown.entries.sortedByDescending { it.value }.joinToString(" + ") { e ->
                    val pct = (e.value * 100 / total).coerceAtLeast(1)
                    "$pct% ${e.key.displayShort()}"
                }
            }
            androidx.compose.material3.Text(
                badge,
                color = UrujMuted,
                fontSize = 10.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        // Horizontal stacked bar — each zone takes a slice proportional to its
        // share of the total. Visual at-a-glance "where did I spend the ride."
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            val totalMs = result.totalMs.coerceAtLeast(1L)
            val zoneColors = listOf(
                UrujZone1, UrujZone2, UrujZone3, UrujZone4, UrujZone5,
            )
            for (i in 0..4) {
                val pct = result.timeInZoneMs[i].toFloat() / totalMs
                if (pct > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(pct)
                            .fillMaxHeight()
                            .background(zoneColors[i]),
                    )
                }
            }
        }
        // Per-zone row with minutes + percent
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            val labels = listOf("Z1 Recovery", "Z2 Endurance", "Z3 Tempo", "Z4 Threshold", "Z5 VO2/Sprint")
            val zoneColors = listOf(UrujZone1, UrujZone2, UrujZone3, UrujZone4, UrujZone5)
            for (i in 0..4) {
                val zoneMs = result.timeInZoneMs[i]
                val pct = if (result.totalMs > 0) zoneMs.toFloat() / result.totalMs * 100f else 0f
                if (zoneMs > 0L || i in 1..3) {  // always show Z2-Z4 even if 0
                    Row(
                        modifier = Modifier.padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(8.dp)
                                .height(8.dp)
                                .background(zoneColors[i], RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            labels[i],
                            color = UrujText,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${zoneMs / 60_000}m  ${"%.0f".format(pct)}%",
                            color = UrujMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
        // v0.8.5 — zone-system disclosure. TIZ uses %max-HR thresholds
        // (Z1<60% / Z2<70% / Z3<80% / Z4<90% / Z5≥90%) — matches the route
        // map polyline coloring for consistency. Bio Lab's HR card displays
        // Karvonen zones (HR-Reserve-based, personalized via sleeping RHR)
        // which use slightly different boundaries. Honest disclosure now;
        // full unification on Karvonen across TIZ + map + audio coach is
        // tracked as task #147 for a future PR.
        Spacer(Modifier.height(4.dp))
        Text(
            "Zones above use %max-HR thresholds (industry standard for map " +
                "coloring). Bio Lab uses Karvonen zones (personalized via " +
                "sleeping RHR) — boundaries differ by ~3-5 bpm.",
            color = UrujMuted,
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        // Polarized 80/20 compliance line — Blummenfelt's discipline benchmark.
        Spacer(Modifier.height(6.dp))
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            Text(
                "POLARIZED COMPLIANCE",
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(2.dp))
            val easyPct = (result.easyPct * 100).toInt()
            val grayPct = (result.grayPct * 100).toInt()
            val hardPct = (result.hardPct * 100).toInt()
            Text(
                "$easyPct% easy (Z1-Z2) · $grayPct% gray (Z3) · $hardPct% hard (Z4-Z5)",
                color = UrujText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = polarizedFeedback(result.easyPct, result.grayPct, result.hardPct),
                color = UrujMuted,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun polarizedFeedback(easy: Float, gray: Float, hard: Float): String = when {
    easy >= 0.75f && hard >= 0.10f && gray < 0.15f ->
        "Polarized ✓ — Blummenfelt-style 80/20 distribution. Aerobic base + threshold work both hit."
    easy >= 0.85f && hard < 0.05f ->
        "Endurance day — pure aerobic base, no threshold stimulus. Good for recovery weeks."
    easy < 0.50f && gray >= 0.25f ->
        "Gray-zone trap — too much Z3. Either drop to Z2 or push to Z4. Blummenfelt avoids the middle."
    hard >= 0.30f ->
        "Hard day — high threshold/VO2 stimulus. Recovery important tomorrow."
    else ->
        "Mixed distribution — context-dependent. Compare against weekly target."
}

@Composable
private fun ClimbCard(state: RideState) {
    Card("CLIMBING") {
        BigStatRow(
            left = "${state.totalElevGainMeters.toInt()} m",
            leftLabel = "ELEV GAIN",
            right = "${state.totalElevLossMeters.toInt()} m",
            rightLabel = "ELEV LOSS",
        )
    }
}

@Composable
private fun EffortCard(state: RideState) {
    val intensityFactor = if (state.ftpWatts > 0) state.averagePowerWatts / state.ftpWatts else 0f
    val hours = state.movingTimeMs / 3_600_000f
    val tss = (intensityFactor.toDouble().pow(2) * hours * 100).toFloat()

    Card("TRAINING LOAD", accent = UrujNeonMagenta) {
        BigStatRow(
            left = "%.2f".format(intensityFactor),
            leftLabel = "IF (avg/FTP)",
            right = "${tss.toInt()}",
            rightLabel = "TSS (≈)",
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "IF: 0.55=recovery, 0.75=endurance, 0.85=tempo, 0.95+=race. TSS: 100=1hr at threshold.",
            color = UrujMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Card(title: String, accent: Color = UrujAccent, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UrujSurface, RoundedCornerShape(16.dp))
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = title,
            color = accent,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun BigStatRow(left: String, leftLabel: String, right: String, rightLabel: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(left, color = UrujText, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text(leftLabel, color = UrujMuted, fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 2.sp)
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(right, color = UrujText, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text(rightLabel, color = UrujMuted, fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 2.sp)
        }
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(horizontal = 16.dp)
            .background(UrujSurfaceHigh),
    )
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%d:%02d:%02d".format(h, m, s)
}

/**
 * v0.8.5 — HR card source label, mirrors the TIZ badge format. Returns
 * "9998 samples from chest strap ✓" / "from band (batched)" / "78% strap +
 * 22% band" / "from Health Connect" (fallback when breakdown is empty).
 *
 * Breakdown is populated by RideSummaryViewModel whenever the NDJSON-first
 * path produced the data. Empty breakdown = HC-polled fallback path —
 * preserve the original "from Health Connect" wording in that case so the
 * label remains accurate.
 */
private fun formatHrSampleSource(
    sampleCount: Int,
    breakdown: Map<com.uruj.domain.SensorSource, Int>,
): String {
    if (breakdown.isEmpty()) {
        return "$sampleCount samples from Health Connect"
    }
    val total = breakdown.values.sum().coerceAtLeast(1)
    if (breakdown.size == 1) {
        val only = breakdown.keys.first()
        val source = when (only) {
            com.uruj.domain.SensorSource.STRAP -> "from chest strap ✓"
            com.uruj.domain.SensorSource.BAND -> "from band (batched)"
            com.uruj.domain.SensorSource.MIXED -> "mixed sources"
            com.uruj.domain.SensorSource.UNKNOWN_LEGACY -> "legacy (pre-v0.8.2)"
        }
        return "$sampleCount samples $source"
    }
    val mix = breakdown.entries.sortedByDescending { it.value }.joinToString(" + ") { e ->
        val pct = (e.value * 100 / total).coerceAtLeast(1)
        "$pct% ${e.key.displayShort()}"
    }
    return "$sampleCount samples — $mix"
}
