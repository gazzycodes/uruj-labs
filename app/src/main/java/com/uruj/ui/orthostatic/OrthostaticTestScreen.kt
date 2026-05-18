package com.uruj.ui.orthostatic

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uruj.domain.AutonomicTier
import com.uruj.domain.OrthostaticInterpretation
import com.uruj.domain.OrthostaticTestResult
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone1
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone4
import com.uruj.ui.theme.UrujZone5

@Composable
fun OrthostaticTestScreen(
    onBack: () -> Unit,
    viewModel: OrthostaticTestViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(20.dp),
    ) {
        when (val s = state) {
            is OrthostaticTestViewModel.UiState.Idle -> IdleView(onStart = viewModel::startTest, onBack = onBack)
            is OrthostaticTestViewModel.UiState.Instructions -> PhaseView(
                title = "ORTHOSTATIC AUTONOMIC TEST",
                primary = "Get ready",
                secondary = "Sit comfortably with the strap on. Breathe naturally.",
                accent = UrujAccent,
            )
            is OrthostaticTestViewModel.UiState.PrepCountdown -> PhaseView(
                title = "PREP",
                primary = "${s.secondsRemaining}",
                secondary = "Sit still. Test starts in ${s.secondsRemaining}s. Breathe normally.",
                accent = UrujAccent,
            )
            is OrthostaticTestViewModel.UiState.Seated -> PhaseView(
                title = "SEATED",
                primary = formatMmSs(s.secondsRemaining),
                secondary = "Stay seated. Relaxed posture. Don't talk. Don't move.",
                accent = UrujZone2,
            )
            is OrthostaticTestViewModel.UiState.Transition -> PhaseView(
                title = "STAND UP NOW",
                primary = "${s.secondsRemaining}",
                secondary = "Stand up and stay still. Don't shift your weight.",
                accent = UrujZone4,
            )
            is OrthostaticTestViewModel.UiState.Standing -> PhaseView(
                title = "STANDING",
                primary = formatMmSs(s.secondsRemaining),
                secondary = "Stand still. Look straight ahead. Don't lean on anything.",
                accent = UrujZone3,
            )
            is OrthostaticTestViewModel.UiState.Computing -> PhaseView(
                title = "COMPUTING",
                primary = "···",
                secondary = "Reading RR data from the 24/7 NDJSON. A few seconds.",
                accent = UrujMuted,
            )
            is OrthostaticTestViewModel.UiState.Result -> ResultView(
                result = s.result,
                interpretation = s.interpretation,
                onDone = onBack,
                onRetake = viewModel::reset,
            )
            is OrthostaticTestViewModel.UiState.Error -> ErrorView(
                message = s.message,
                onRetry = viewModel::reset,
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun IdleView(onStart: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) {
            Text("← BACK", color = UrujAccent, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "ORTHOSTATIC TEST",
            color = UrujAccent,
            fontWeight = FontWeight.Black,
            fontSize = 24.sp,
            letterSpacing = 2.sp,
        )
        Text(
            "What it measures",
            color = UrujText,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        Text(
            "How your autonomic nervous system responds to a sharp posture change. " +
                "When you stand, parasympathetic tone drops and sympathetic tone " +
                "rises — your HR climbs, your HRV falls. The SIZE of those shifts " +
                "tells you how fatigued / recovered you actually are.\n\n" +
                "A well-rested body absorbs the stand cleanly (small HR climb, " +
                "preserved HRV ratio). An over-reached body over-reacts " +
                "(big HR climb, collapsed HRV ratio).",
            color = UrujText.copy(alpha = 0.85f),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Protocol",
            color = UrujText,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        Text(
            "• 30s prep — sit still, breathe normally\n" +
                "• 2 min SEATED capture\n" +
                "• 10s transition cue — sharp stand\n" +
                "• 2 min STANDING capture\n" +
                "• Total: ~5 minutes\n\n" +
                "Strap stays on, 24/7 service writes RR data to NDJSON, we slice " +
                "and compute after. Same lab-grade math the overnight HRV uses.",
            color = UrujText.copy(alpha = 0.85f),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = UrujAccent),
        ) {
            Text(
                "START TEST",
                color = Color.Black,
                fontWeight = FontWeight.Black,
                fontSize = 16.sp,
                letterSpacing = 1.5.sp,
            )
        }
    }
}

@Composable
private fun PhaseView(
    title: String,
    primary: String,
    secondary: String,
    accent: Color,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            color = accent,
            fontWeight = FontWeight.Black,
            fontSize = 18.sp,
            letterSpacing = 4.sp,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            primary,
            color = accent,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Black,
            fontSize = 120.sp,
            letterSpacing = (-4).sp,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            secondary,
            color = UrujText.copy(alpha = 0.85f),
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 20.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ResultView(
    result: OrthostaticTestResult,
    interpretation: OrthostaticInterpretation,
    onDone: () -> Unit,
    onRetake: () -> Unit,
) {
    val accent = tierColor(interpretation.overallTier)
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("ORTHOSTATIC RESULT", color = UrujAccent, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 2.sp)
        Text(interpretation.summary, color = accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        ResultBlock(label = "HR DELTA (stand − sit)", primary = "${"%.0f".format(result.hrDeltaBpm)} bpm", color = tierColor(interpretation.hrDeltaTier))
        ResultBlock(label = "RMSSD RATIO (stand / sit)", primary = "%.2f".format(result.rmssdRatio), color = tierColor(interpretation.rmssdRatioTier))
        Spacer(Modifier.height(8.dp))
        BreakdownBlock("SEATED", result.seatedMeanHrBpm, result.seatedRmssdMs, result.seatedSampleCount)
        BreakdownBlock("STANDING", result.standingMeanHrBpm, result.standingRmssdMs, result.standingSampleCount)
        Spacer(Modifier.height(8.dp))
        Text(
            "HR delta tiers: <10 elite · 10-15 healthy · 15-25 moderate · 25-35 significant · 35+ severe (Klivington 1995, Plews et al.).\n" +
                "RMSSD ratio tiers: ≥0.4 healthy · 0.3-0.4 moderate · 0.2-0.3 significant · <0.2 severe parasympathetic suppression on stand.\n\n" +
                "One reading is a snapshot. The trend across days is what matters — track over the next week and watch how the numbers move with your training load.",
            color = UrujMuted,
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onRetake) {
                Text("RETAKE", color = UrujMuted, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = UrujAccent),
            ) {
                Text("DONE", color = Color.Black, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            }
        }
    }
}

@Composable
private fun ResultBlock(label: String, primary: String, color: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UrujSurfaceHigh.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(label, color = UrujMuted, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(4.dp))
        Text(primary, color = color, fontWeight = FontWeight.Black, fontSize = 32.sp, letterSpacing = (-1).sp)
    }
}

@Composable
private fun BreakdownBlock(label: String, meanHr: Float, rmssd: Float, samples: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UrujSurfaceHigh.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(label, color = UrujMuted, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            "${"%.0f".format(meanHr)} bpm mean · ${"%.1f".format(rmssd)} ms RMSSD · $samples clean beats",
            color = UrujText, fontSize = 12.sp,
        )
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("TEST FAILED", color = UrujZone5, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            color = UrujText,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 20.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = UrujAccent),
        ) {
            Text("TRY AGAIN", color = Color.Black, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack) {
            Text("BACK", color = UrujMuted, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun tierColor(tier: AutonomicTier): Color = when (tier) {
    AutonomicTier.ELITE -> UrujZone1
    AutonomicTier.HEALTHY -> UrujZone2
    AutonomicTier.MODERATE_STRAIN -> UrujZone3
    AutonomicTier.SIGNIFICANT_STRAIN -> UrujZone4
    AutonomicTier.SEVERE_STRAIN -> UrujZone5
}

private fun formatMmSs(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%d:%02d".format(m, s)
}
