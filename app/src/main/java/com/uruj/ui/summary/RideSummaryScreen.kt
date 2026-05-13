package com.uruj.ui.summary

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
import com.uruj.ui.theme.UrujZone3
import kotlin.math.pow

@Composable
fun RideSummaryScreen(
    state: RideState,
    onDone: () -> Unit,
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
            HrCard(hrState)
            Spacer(Modifier.height(12.dp))
            ClimbCard(state)
            Spacer(Modifier.height(12.dp))
            EffortCard(state)

            Spacer(Modifier.height(28.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val image = captureLayer.toImageBitmap().asAndroidBitmap()
                            val sessionId = state.sessionId ?: "ride"
                            val uri = ShareImage.save(context, image, sessionId)
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
private fun HrCard(hrState: HrEnrichmentState) {
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
                        Text(
                            text = "${hrState.sampleCount} samples from Health Connect",
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
