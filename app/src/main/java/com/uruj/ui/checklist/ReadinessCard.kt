package com.uruj.ui.checklist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.uruj.data.ReadinessSnapshot
import com.uruj.domain.ReadinessComponent
import com.uruj.domain.ReadinessGrade
import com.uruj.domain.ReadinessResult
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone1
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone5

@Composable
fun ReadinessCard(
    result: ReadinessResult?,
    snapshot: ReadinessSnapshot? = null,
    syncing: Boolean = false,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (result == null) return
    val color = result.grade.color()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(UrujSurface, RoundedCornerShape(16.dp))
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "READINESS",
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.width(8.dp))
            val confidencePct = (result.dataConfidence * 100).roundToInt()
            if (confidencePct > 0 && confidencePct < 100) {
                Text(
                    text = "· $confidencePct% data",
                    color = UrujMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    letterSpacing = 0.5.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRefresh, enabled = !syncing) {
                if (syncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(12.dp).width(12.dp),
                        color = UrujAccent,
                        strokeWidth = 1.5.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "SYNCING",
                        color = UrujAccent,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp,
                        letterSpacing = 1.5.sp,
                    )
                } else {
                    Text(
                        "↻ SYNC",
                        color = UrujAccent,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp,
                        letterSpacing = 1.5.sp,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = result.grade.label,
                color = color,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 3.sp,
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            // Only show a real score when data confidence is sufficient. Showing a big
            // "90/100" when only one input is real misleads the reader — better to show
            // "—" and let them know the score isn't computable yet.
            val showRealScore = result.grade !in setOf(
                ReadinessGrade.Unknown, ReadinessGrade.LimitedData,
            )
            if (showRealScore) {
                Text(
                    text = result.score.toString(),
                    color = color,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    fontSize = 56.sp,
                    letterSpacing = (-2).sp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "/ 100",
                    color = UrujMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            } else {
                Text(
                    text = "—",
                    color = UrujMuted,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    fontSize = 56.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "score appears\nwhen we have data",
                    color = UrujMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        result.components.forEach { component ->
            ComponentRow(component)
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = result.recommendation,
            color = UrujText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        if (snapshot != null) {
            Spacer(Modifier.height(8.dp))
            DiagnosticsLine(snapshot = snapshot)
        }
    }
}

@Composable
private fun DiagnosticsLine(snapshot: ReadinessSnapshot) {
    val diag = snapshot.diagnostics
    val ageSec = ((System.currentTimeMillis() - snapshot.computedAtMs) / 1000L).coerceAtLeast(0)
    val ageLabel = when {
        ageSec < 5 -> "just now"
        ageSec < 60 -> "${ageSec}s ago"
        else -> "${ageSec / 60}m ago"
    }
    val anyData = diag.sleepRecords7d + diag.hrvRecords7d + diag.rhrRecords7d > 0
    val hcOk = diag.healthConnectInstalled && diag.permissionsGranted == diag.permissionsExpected
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UrujSurfaceHigh.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "HC:",
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (hcOk) "${diag.permissionsGranted}/${diag.permissionsExpected} perms ✓"
                else "${diag.permissionsGranted}/${diag.permissionsExpected} perms ✗",
                color = if (hcOk) UrujZone2 else UrujZone5,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "synced $ageLabel",
                color = UrujMuted,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Records (7d): ${diag.sleepRecords7d} sleep · " +
                "${diag.hrvRecords7d} HRV · " +
                "${diag.rhrRecords7d} RHR · " +
                "${diag.hrRecords7d} HR · " +
                "${diag.rideSummariesAll} URUJ rides",
            color = if (anyData) UrujText else UrujMuted,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
        )
        if (hcOk) {
            val hrFlowing = diag.hrRecords7d > 0
            val dedicatedReadinessMissing = diag.sleepRecords7d == 0 &&
                diag.hrvRecords7d == 0 && diag.rhrRecords7d == 0
            Spacer(Modifier.height(2.dp))
            val hint = when {
                !hrFlowing && !anyData ->
                    "Samsung Health hasn't pushed any data yet. Wear band → workout → end workout to trigger first sync."
                hrFlowing && dedicatedReadinessMissing ->
                    "HR flowing ✓ — using HR-proxy for HRV/RHR (Garmin/Fitbit method). Wear band overnight for sleep + direct values."
                hrFlowing && !dedicatedReadinessMissing ->
                    "Full pipeline alive ✓"
                else -> ""
            }
            if (hint.isNotEmpty()) {
                Text(
                    text = hint,
                    color = if (hrFlowing) UrujZone2 else UrujZone3,
                    fontWeight = FontWeight.Medium,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun ComponentRow(component: ReadinessComponent) {
    val score = component.score
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = component.label.uppercase(),
            color = UrujMuted,
            fontWeight = FontWeight.Black,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
            modifier = Modifier.width(90.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .background(UrujSurfaceHigh, RoundedCornerShape(3.dp)),
        ) {
            if (score != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(score / 100f)
                        .height(6.dp)
                        .background(scoreColor(score), RoundedCornerShape(3.dp)),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = component.detail,
            color = if (score == null) UrujMuted else UrujText,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            modifier = Modifier.width(110.dp),
        )
    }
}

private fun scoreColor(score: Int): Color = when {
    score >= 80 -> UrujZone2
    score >= 60 -> UrujZone3
    score >= 40 -> UrujZone3
    else -> UrujZone5
}

private fun ReadinessGrade.color(): Color = when (this) {
    ReadinessGrade.GoHard -> UrujZone2
    ReadinessGrade.Moderate -> UrujZone3
    ReadinessGrade.Easy -> UrujZone3
    ReadinessGrade.Rest -> UrujZone5
    ReadinessGrade.LimitedData -> UrujZone1
    ReadinessGrade.Unknown -> UrujMuted
}
