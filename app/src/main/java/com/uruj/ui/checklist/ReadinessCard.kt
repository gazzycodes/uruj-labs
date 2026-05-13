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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
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
    // Live-ticking age — uses produceState to recompose every second.
    // Keyed on computedAtMs so the ticker restarts whenever a fresh sync lands.
    val nowMs by produceState(
        initialValue = System.currentTimeMillis(),
        snapshot.computedAtMs,
    ) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val ageSec = ((nowMs - snapshot.computedAtMs) / 1000L).coerceAtLeast(0)
    val ageLabel = when {
        ageSec < 5 -> "just now"
        ageSec < 60 -> "${ageSec}s ago"
        ageSec < 3600 -> "${ageSec / 60}m ago"
        else -> "${ageSec / 3600}h ago"
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
        // RHR label depends on where the score actually pulled its value from.
        // "0 RHR direct records" is true but misleading when SleepingRhrCalculator
        // derived the value from sleep + HR samples — the readiness score IS
        // using a derived RHR even though the direct-record count is zero.
        val rhrLabel = when (diag.rhrSourceLabel) {
            "direct" -> "${diag.rhrRecords7d} RHR(direct)"
            "sleep" -> "RHR(sleep) ✓"
            "proxy" -> "RHR(HR-proxy)"
            else -> "${diag.rhrRecords7d} RHR"
        }
        val hrvLabel = when (diag.hrvSourceLabel) {
            "direct" -> "${diag.hrvRecords7d} HRV(direct)"
            "sleep" -> "HRV(sleep) ✓"
            "proxy" -> "HRV(HR-proxy)"
            else -> "${diag.hrvRecords7d} HRV"
        }
        Text(
            text = "Records (7d): ${diag.sleepRecords7d} sleep · " +
                "$hrvLabel · " +
                "$rhrLabel · " +
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
        // Data-window footer — explicit about what time ranges feed the score.
        // Surfaces methodology so the rider knows whether "today" means
        // calendar midnight or rolling 24h, and which baselines are rolling.
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Windows: today = local-calendar midnight → now · " +
                "HRV/RHR baseline = rolling 7 days · Training load = rolling 42d EWMA",
            color = UrujMuted,
            fontWeight = FontWeight.Medium,
            fontSize = 9.sp,
        )
    }
}

@Composable
private fun ComponentRow(component: ReadinessComponent) {
    val score = component.score
    // Verbose 2-line layout (v0.3.2). Top line: existing bar + value snapshot.
    // Bottom line: score out of 100 + one-line "why" tagline. Lets the rider
    // see WHICH component is dragging the score and WHY, not just an opaque
    // mood bar. Blummenfelt-grade transparency without a separate detail page.
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
        // Second line — score + reason. Indent to align under the value column.
        if (score != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 90.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$score/100",
                    color = scoreColor(score),
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(56.dp),
                )
                Text(
                    text = reasonFor(component.label, score, component.detail),
                    color = UrujMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * Human-readable explanation per component based on score bucket + value.
 * Surfaces methodology without requiring a dedicated detail screen. Reads
 * like a coach's note: short, specific, actionable when relevant.
 */
private fun reasonFor(label: String, score: Int, detail: String): String = when (label) {
    "Sleep" -> when {
        score >= 100 -> "optimal range 7-9h ✓"
        score >= 90 -> "slightly over 7-9h optimal"
        score >= 80 -> "below 7h target — still ok"
        score >= 60 -> "under-slept — recovery limited"
        else -> "severely under-slept"
    }
    "HRV" -> when {
        score >= 90 -> "autonomic system primed ✓"
        score >= 70 -> "near baseline — normal variance"
        score >= 40 -> "below baseline — watch fatigue"
        else -> "proxy noisier than RMSSD; chest strap (v1.5) unlocks real HRV"
    }
    "Resting HR" -> when {
        score >= 100 -> "RHR below baseline → strong recovery"
        score >= 75 -> "RHR near baseline"
        else -> "RHR elevated — recovery limited or stress signal"
    }
    "Training load" -> when {
        score >= 95 -> "fresh — peak race window"
        score >= 90 -> "balanced load"
        score >= 75 -> "mild fatigue — still trainable"
        score >= 55 -> "fatigued from recent hard rides"
        else -> "over-trained — rest before pushing"
    }
    else -> ""
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
