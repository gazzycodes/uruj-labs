package com.uruj.ui.checklist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
    // v0.4.2: per-component info dialog. Tapping the ⓘ icon next to a label
    // opens an ELI10 explanation of what the metric means, where the rider
    // should live as an athlete, and the honesty caveats. Captures the
    // coaching context that used to require a separate conversation.
    var infoFor by remember { mutableStateOf<String?>(null) }
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
            ComponentRow(component, onInfo = { infoFor = component.label })
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        // v0.9.3 — recommendation block split into headline + duration + rationale
        // by ReadinessRecommendationEngine. UI renders 3 lines so the rider sees
        // the call, the duration cap, and the WHY in plain language. Falls back
        // to single-line when duration/rationale are null (limited-data path).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(UrujSurfaceHigh.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = result.recommendation,
                color = UrujText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.3.sp,
            )
            val duration = result.recommendationDuration
            if (!duration.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = duration.uppercase(),
                    color = UrujAccent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                )
            }
            val rationale = result.recommendationRationale
            if (!rationale.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = rationale,
                    color = UrujMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            // v0.9.4 — cross-metric insights bullets. Surfaced separately
            // from the rationale so trend + multi-day patterns get visual
            // weight. Empty when nothing notable today.
            if (result.recommendationInsights.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                result.recommendationInsights.forEach { insight ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = "• ",
                            color = UrujAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = insight,
                            color = UrujText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
            // v0.9.4 — missing-data callout. Surfaces what the engine isn't
            // seeing today so the rider knows the recommendation's blind spots.
            val missingCallout = result.recommendationMissingSignals
            if (!missingCallout.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = missingCallout,
                    color = UrujZone3,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.3.sp,
                )
            }
        }
        if (snapshot != null) {
            Spacer(Modifier.height(8.dp))
            DiagnosticsLine(snapshot = snapshot)
        }
    }

    // Info dialog — outside the card so it overlays everything.
    val infoLabel = infoFor
    if (infoLabel != null) {
        InfoDialog(
            label = infoLabel,
            result = result,
            onDismiss = { infoFor = null },
        )
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
        // v0.9.3 — source-aware labels. Pre-v0.9.3 the rhrSource string could be
        // "sleep:band" / "sleep:strap" / "sleep:mixed" (v0.7.7 added the sensor
        // suffix), but the UI only matched the bare "sleep" → fell to the else
        // branch → showed misleading "0 RHR" when the readiness score WAS using
        // a derived value. Now startsWith("sleep") catches all variants.
        val rhrLabel = when {
            diag.rhrSourceLabel == "direct" -> "${diag.rhrRecords7d} RHR(direct)"
            diag.rhrSourceLabel?.startsWith("sleep") == true -> {
                val sensor = diag.rhrSourceLabel.substringAfter(":", "")
                if (sensor.isNotEmpty()) "RHR(sleep · $sensor) ✓" else "RHR(sleep) ✓"
            }
            diag.rhrSourceLabel == "proxy" -> "RHR(HR-proxy)"
            // Null source = no derived RHR. Be explicit that the count refers to
            // HC direct writes so the rider knows where the 0 comes from.
            else -> "${diag.rhrRecords7d} RHR(HC direct)"
        }
        // v0.9.3 — HRV(strap) gets the URUJ NDJSON nights count so the rider
        // sees the actual baseline-building progress ("HRV(strap · 2n) ✓" =
        // 2 of 7 nights captured). HC's RmssdRecord count is meaningless when
        // URUJ owns the HRV path (Magene H613 doesn't write to HC).
        val hrvLabel = when (diag.hrvSourceLabel) {
            "direct" -> "${diag.hrvRecords7d} HRV(direct)"
            "ble_strap" -> {
                if (diag.urujHrvNights7d > 0) "HRV(strap · ${diag.urujHrvNights7d}n) ✓"
                else "HRV(strap) ✓"
            }
            "sleep" -> "HRV(sleep) ✓"
            "proxy" -> "HRV(HR-proxy)"
            // Null source = HC has no direct record AND no NDJSON yet. Be explicit
            // that the count refers to HC direct writes (always 0 with Fit Band 3 /
            // Magene H613 — neither writes RmssdRecord to HC).
            else -> "${diag.hrvRecords7d} HRV(HC direct)"
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
private fun ComponentRow(component: ReadinessComponent, onInfo: () -> Unit) {
    val score = component.score
    // v0.4.2: dim the whole row when score is null (HRV on Fit Band 3, RHR
    // without sleep). Looks like a disabled state — clean for screenshots.
    val rowAlpha = if (score == null) 0.45f else 1f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .alpha(rowAlpha),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Label + info button. The ⓘ opens an ELI10 detail dialog so
            // the rider doesn't have to memorize what each metric means.
            Row(
                modifier = Modifier.width(98.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = component.label.uppercase(),
                    color = UrujMuted,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.sp,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.width(4.dp))
                // Larger tap target than the visible icon — 20dp box for finger-
                // size hit area, but only the ⓘ glyph is visible.
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(onClick = onInfo),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "ⓘ",
                        color = UrujAccent,
                        fontSize = 12.sp,
                    )
                }
            }
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
        // Second line — score + reason. Only when we have a real score.
        if (score != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 98.dp, top = 2.dp),
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
    "Sleep" -> {
        // v0.4.1 fix: previous version mapped score → label, which collided
        // 5-6h (score 60) with >10h (score 70) — both showed "under-slept" text.
        // Now branches on actual hours from the detail string (e.g. "11.1h").
        val hours = detail.removeSuffix("h").toFloatOrNull() ?: 0f
        when {
            hours >= 7f && hours <= 9f -> "optimal range 7-9h ✓"
            hours > 12f -> "over-slept >12h — recovery flagging fatigue/illness"
            hours > 10f -> "over-slept — catching up after deep fatigue"
            hours > 9f -> "slightly over 7-9h optimal — still well-rested"
            hours >= 6f -> "below 7h target — still ok"
            hours >= 5f -> "under-slept — recovery limited"
            hours >= 4f -> "severely under-slept"
            hours > 0f -> "severely under-slept — rest day"
            else -> ""
        }
    }
    "HRV" -> {
        // v0.7.0 follow-up — detail strings now include the actual RMSSD value
        // when we're in absolute-tier mode (days 1-6). reasonFor adapts:
        // - "first reading" or "baseline building (N/7)" → use tier language
        // - "+X% vs 7d avg" → use ratio language
        val isAbsoluteMode = detail.contains("first reading") ||
            detail.contains("baseline building")
        when {
            isAbsoluteMode -> when {
                score >= 100 -> "elite parasympathetic dominance ✓"
                score >= 90 -> "trained athlete range ✓"
                score >= 75 -> "average healthy adult range"
                score >= 50 -> "below athletic average"
                else -> "below athletic average — check trend after a week"
            }
            else -> when {
                score >= 90 -> "autonomic system primed ✓"
                score >= 70 -> "near baseline — normal variance"
                score >= 40 -> "below baseline — watch fatigue"
                else -> "well below baseline — significant recovery deficit"
            }
        }
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
        score >= 65 -> "productive fatigue — adaptation territory"
        score >= 50 -> "significant fatigue from recent hard rides"
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

/**
 * ELI10 explanation dialog. Pulls the rider's current value from the result
 * so the explanation can say "for YOU specifically" instead of abstract ranges.
 */
@Composable
private fun InfoDialog(
    label: String,
    result: ReadinessResult,
    onDismiss: () -> Unit,
) {
    val component = result.components.firstOrNull { it.label == label }
    val title = label.uppercase()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                title,
                color = UrujText,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                letterSpacing = 2.sp,
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column {
                    InfoContent(label = label, component = component, result = result)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "GOT IT",
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
private fun InfoContent(label: String, component: ReadinessComponent?, result: ReadinessResult) {
    when (label) {
        "Sleep" -> SleepInfo(component)
        "HRV" -> HrvInfo(component)
        "Resting HR" -> RestingHrInfo(component)
        "Training load" -> TrainingLoadInfo(component, result)
        else -> Text(label, color = UrujText)
    }
}

@Composable
private fun InfoSection(heading: String, body: String) {
    Text(
        heading.uppercase(),
        color = UrujAccent,
        fontWeight = FontWeight.Black,
        fontSize = 10.sp,
        letterSpacing = 1.5.sp,
    )
    Spacer(Modifier.height(4.dp))
    Text(body, color = UrujText, fontSize = 13.sp, lineHeight = 18.sp)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun YouSection(body: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(UrujSurfaceHigh.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Column {
            Text(
                "FOR YOU RIGHT NOW",
                color = UrujAccent,
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(body, color = UrujText, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun SleepInfo(component: ReadinessComponent?) {
    InfoSection(
        "What it is",
        "Hours you spent in bed last night, pulled from Samsung Fit Band 3 " +
            "via Health Connect.",
    )
    InfoSection(
        "Why cyclists care",
        "Sleep is when hormones repair muscle damage and refill glycogen. " +
            "Bad sleep = bad ride. There's no supplement that beats 8h.",
    )
    InfoSection(
        "Where you want to be",
        "7-9h consistent every night.\n\n" +
            "• Under 6h → recovery limited, take it easy\n" +
            "• 7-9h → optimal\n" +
            "• 10-12h → catching up after deep fatigue (fine occasionally)\n" +
            "• Over 12h → body may be fighting illness",
    )
    InfoSection(
        "Honest caveat",
        "\"Hours\" here = time in bed (Samsung's \"Sleep Time\"). Actual asleep " +
            "time is slightly less. Sleep stages (REM/Deep/Light) live in Samsung " +
            "Health — tap the Samsung Health deep-link in Bio Lab to see them.",
    )
    if (component?.detail != null && component.score != null) {
        YouSection("Last night: ${component.detail} → ${component.score}/100")
    }
}

@Composable
private fun HrvInfo(component: ReadinessComponent?) {
    InfoSection(
        "What it is",
        "RMSSD — Root Mean Square of Successive Differences. Measures the " +
            "beat-to-beat variation in your heart rate. When well-recovered, " +
            "gaps between beats vary more (high RMSSD). When stressed or " +
            "sympathetic-dominant, they become more uniform (low RMSSD).",
    )
    InfoSection(
        "Why it matters",
        "The single best non-invasive marker of parasympathetic (vagal) tone. " +
            "Predicts how you'll respond to training BEFORE you ride, catches " +
            "illness onset 1-3 days early, quantifies overtraining patterns.",
    )
    InfoSection(
        "How URUJ computes it (methodology)",
        "Real ECG-precision RR intervals from your Magene H613 chest strap " +
            "(via 24/7 monitoring). The math:\n\n" +
            "1. Reconstruct beat timestamps from each BLE notification\n" +
            "2. Split data into 5-minute windows (Task Force 1996 standard)\n" +
            "3. Per window: filter physiological 300-2000 ms RR, drop pairs " +
            "with >20% delta (ectopic), require ≥30 clean consecutive-pair diffs\n" +
            "4. Compute RMSSD per window, take median across valid windows\n\n" +
            "Same methodology Polar / Kubios / EliteHRV / HRV4Training use. " +
            "NOT a PPG proxy. NOT a std-dev hack. Natural overnight breathing — " +
            "not a paced-breathing peak measurement.",
    )
    InfoSection(
        "Reference ranges (athletic norms)",
        "80+ ms — Elite parasympathetic dominance\n" +
            "50-80 ms — Trained athlete range\n" +
            "30-50 ms — Average healthy adult\n" +
            "20-30 ms — Below athletic average\n" +
            "<20 ms — Below athletic average — check trend",
    )
    InfoSection(
        "Why this differs from Elite HRV / morning readings",
        "Apps that measure morning seated HRV (Elite HRV, HRV4Training, Whoop " +
            "spot reads) use PACED BREATHING — they tell you 'breathe in / breathe " +
            "out' at ~5 breaths per minute. That maximizes Respiratory Sinus " +
            "Arrhythmia and inflates RMSSD 1.5-3× over natural breathing for the " +
            "SAME person.\n\n" +
            "URUJ measures your actual overnight autonomic state with natural " +
            "breathing during sleep. If Elite HRV gives you 25 ms and URUJ gives " +
            "you 12 ms on the same body — neither is wrong. They answer different " +
            "questions: 'maximum vagal capacity when forced' vs 'autonomic state " +
            "while you sleep.'",
    )
    InfoSection(
        "Baseline building period",
        "First 1-6 nights: we score against ABSOLUTE tier ranges (above). " +
            "A real 60 ms scores well even without your personal baseline.\n\n" +
            "Day 7+: we switch to RATIO vs your personal 7-day median. " +
            "Catches subtle changes vs YOUR normal (a 50 ms HRV that's normal " +
            "for you can be a warning sign for someone whose baseline is 80 ms).",
    )
    if (component?.detail != null && component.score != null) {
        YouSection(
            "Reading: ${component.detail}\n" +
                "Score: ${component.score}/100"
        )
    }
}

@Composable
private fun RestingHrInfo(component: ReadinessComponent?) {
    InfoSection(
        "What it is",
        "Your lowest stable heart rate. URUJ uses the median of your sleep " +
            "nights over the last 7 days (sleep-window RHR — more accurate than " +
            "Samsung's daytime daily-RHR).",
    )
    InfoSection(
        "Why cyclists care",
        "Athletic RHR drops as you get fitter. It's a free fitness marker.\n\n" +
            "RHR spiking 5+ bpm above baseline = early sign of:\n" +
            "• Illness onset (catch it before you feel sick)\n" +
            "• Poor recovery from yesterday\n" +
            "• Dehydration / under-fueled\n" +
            "• Alcohol the night before",
    )
    InfoSection(
        "Where you want to be",
        "At or below your 7-day baseline.\n\n" +
            "Endurance-athlete RHR norms:\n" +
            "• 40-50 bpm → elite endurance\n" +
            "• 50-60 bpm → trained\n" +
            "• 60-70 bpm → recreational fit\n" +
            "• 70+ bpm → untrained",
    )
    if (component?.detail != null && component.score != null) {
        YouSection("Today: ${component.detail} → ${component.score}/100")
    }
}

@Composable
private fun TrainingLoadInfo(component: ReadinessComponent?, result: ReadinessResult) {
    InfoSection(
        "What TSB is — explained simply",
        "Imagine TWO batteries in your body:\n\n" +
            "🔋 FITNESS battery — fills slowly when you train consistently. " +
            "Fully charges in ~42 days of regular riding. This is what makes you fast.\n\n" +
            "⚡ FATIGUE battery — fills fast when you ride hard. Drains in ~7 " +
            "days of rest. This is what makes you feel tired.\n\n" +
            "TSB = Fitness − Fatigue. It's just the balance between the two.",
    )
    InfoSection(
        "What the number means",
        "+5 or higher → FRESH (race day energy)\n\n" +
            "-5 to +5 → BALANCED (normal training week)\n\n" +
            "-10 to -15 → PRODUCTIVE FATIGUE (adaptation territory)\n" +
            "    Pros LIVE here. You're tired but getting stronger.\n" +
            "    This is GOOD if you sleep + eat well.\n\n" +
            "-15 to -25 → SIGNIFICANT FATIGUE\n" +
            "    Heavy training block. Watch RHR + sleep.\n\n" +
            "Below -25 → OVERREACH — take rest days.",
    )
    InfoSection(
        "About the \"42 days\" thing",
        "42 days is NOT how long it takes to recover from a ride.\n\n" +
            "42 days is the time scale for BUILDING fitness (the slow battery). " +
            "Your fatigue (fast battery) recovers in 1-7 days.\n\n" +
            "After a hard ride:\n" +
            "• Day 1: TSB drops 10-15 points\n" +
            "• Day 2-3: recovers ~14% per day naturally\n" +
            "• Day 7: nearly back to where you started\n\n" +
            "So a hard ride costs you 2-7 days, not 42.",
    )
    InfoSection(
        "Where to live as a cyclist/biohacker",
        "In a training BLOCK: TSB -10 to -20 (productive fatigue)\n\n" +
            "Race week (taper): TSB -5 to +5 (sharpening)\n\n" +
            "Race day: TSB +5 to +15 (peak fresh)\n\n" +
            "Off-season: 0 to +10 (maintain only)\n\n" +
            "TSB stuck above +15 for weeks → detraining\n" +
            "TSB stuck below -25 for weeks → overtraining",
    )
    InfoSection(
        "How it auto-updates (multi-sport)",
        "• Every URUJ ride adds TSS = IF² × hours × 100, with IF = avgPower/FTP " +
            "(power-based, the precise version).\n\n" +
            "• Every Samsung-tracked run / HIIT / strength session also adds " +
            "hrTSS based on HR Reserve fraction during the session (Banister-" +
            "style, normalized to threshold = IF 1.0 at 87% HR Reserve). So " +
            "your runs count toward TSB even though URUJ doesn't record runs.\n\n" +
            "• Cycling sessions in Samsung that overlap a URUJ ride are skipped " +
            "(no double-counting).\n\n" +
            "• Every calendar day at midnight, both batteries leak — fatigue " +
            "drains ~14% per rest day, fitness drains ~2.4% per rest day. So " +
            "TSB naturally drifts back toward 0 if you rest.\n\n" +
            "• No manual refresh needed — math runs on calendar dates.",
    )
    // v0.9.21 — mirror Bio Lab Training State ⓘ depth here. Content shared
    // via com.uruj.ui.biolab.TsbInfoContent so both surfaces stay in sync.
    InfoSection(
        "The real signal — ATL:CTL ratio",
        com.uruj.ui.biolab.tsbAtlCtlRatioBody(),
    )
    InfoSection(
        "Build CTL first — the prescription",
        com.uruj.ui.biolab.tsbBuildCtlPrescriptionBody(),
    )
    val ctl = result.tsbCtl
    val atl = result.tsbAtl
    val total = result.tsbTotalLoad42d
    if (ctl != null && atl != null && total != null && component?.detail != null) {
        // Personalized YouSection with full ATL:CTL breakdown — matches the
        // Bio Lab Training State ⓘ shape. tierLabel uses the existing
        // component.detail (e.g. "fatigued (TSB -24)") + score so the line
        // matches what the rider sees on the card itself.
        val tierLabel = "Today: ${component.detail}" +
            if (component.score != null) " → ${component.score}/100" else ""
        YouSection(
            com.uruj.ui.biolab.tsbYouSectionBody(
                tierLabel = tierLabel,
                ctl = ctl,
                atl = atl,
                totalLoad42d = total,
                action = null,  // action lives on Readiness recommendation rationale, not duplicated here
            )
        )
    } else if (component?.detail != null && component.score != null) {
        // Limited-data path — old shape preserved.
        YouSection("Today: ${component.detail} → ${component.score}/100")
    }
}
