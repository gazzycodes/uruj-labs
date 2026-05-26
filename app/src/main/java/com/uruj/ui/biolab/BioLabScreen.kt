package com.uruj.ui.biolab

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uruj.data.BioLabSnapshot
import com.uruj.data.HrrSample
import com.uruj.power.KarvonenZonesCalculator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone1
import com.uruj.ui.theme.UrujZoneBelowZ1
import kotlin.math.roundToInt
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone4
import com.uruj.ui.theme.UrujZone5

@Composable
fun BioLabScreen(
    onBack: () -> Unit,
    onOpenOrthostatic: () -> Unit = {},
    onOpenHrvTrend: () -> Unit = {},
    onOpenHrr1Trend: () -> Unit = {},
    onOpenCarTrend: () -> Unit = {},
    onOpenOrthostaticTrend: () -> Unit = {},
    // v0.9.6 — disk-snapshot trend screens for RHR / VO2 / TSB
    onOpenRhrTrend: () -> Unit = {},
    onOpenVo2Trend: () -> Unit = {},
    onOpenTsbTrend: () -> Unit = {},
    // v0.9.8 — sleep-hours trend (new SleepSnapshotRepository)
    onOpenSleepTrend: () -> Unit = {},
    // v0.9.28 — freq-domain HRV trends (LF/HF + DFA α1) from HrvSnapshotRepository
    onOpenLfHfTrend: () -> Unit = {},
    onOpenDfaAlpha1Trend: () -> Unit = {},
    // v0.9.34 — postprandial HRV response trend from PostprandialSnapshotRepository
    onOpenPostprandialTrend: () -> Unit = {},
    viewModel: BioLabViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    // v0.9.31 — meal-mark confirmation snackbar
    val markMealMessage by viewModel.markMealMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(markMealMessage) {
        markMealMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            kotlinx.coroutines.delay(3000)
            viewModel.clearMarkMealMessage()
        }
    }
    // v0.9.32 — backdate picker for late taps + long-press delete confirmation
    var showMealMarkPicker by remember { mutableStateOf(false) }
    var deleteMarkConfirmId by remember { mutableStateOf<String?>(null) }
    // v0.9.39 — tracker sheet (#111 in-app tracker layer)
    var showTrackerSheet by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "BIO LAB",
                        color = UrujMuted,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 3.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    // v0.9.39 — TRACK button opens the in-app tracker sheet
                    // (#111) for mood / energy / hydration / caffeine entries.
                    // Same UX pattern as the MEAL button — quick log + save.
                    TextButton(onClick = { showTrackerSheet = true }) {
                        Text(
                            "+ TRACK",
                            color = UrujAccent,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.sp,
                            letterSpacing = 1.5.sp,
                        )
                    }
                    // v0.9.31 → v0.9.32 — Tier B postprandial test trigger.
                    // Now opens a backdate picker (rider can choose "Now",
                    // "5 min ago", etc.) so a late tap can be timestamped
                    // accurately. Pre-window is anchored to the adjusted
                    // timestamp so picking the correct meal-start gives
                    // a clean pre-meal baseline.
                    TextButton(onClick = { showMealMarkPicker = true }) {
                        Text(
                            "🍽 MEAL",
                            color = UrujAccent,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.sp,
                            letterSpacing = 1.5.sp,
                        )
                    }
                    // v0.8.5 — explicit REFRESH tap bypasses sticky cache; user
                    // asked for fresh, give it to them verbatim even if HC blips.
                    TextButton(onClick = { viewModel.refresh(force = true) }, enabled = !isLoading) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                color = UrujAccent,
                                strokeWidth = 1.5.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            if (isLoading) "REFRESHING" else "↻ REFRESH",
                            color = UrujAccent,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.sp,
                            letterSpacing = 1.5.sp,
                        )
                    }
                    TextButton(onClick = onBack) {
                        Text(
                            "CLOSE",
                            color = UrujAccent,
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            letterSpacing = 1.5.sp,
                        )
                    }
                }
                Text(
                    "Training Lab",
                    color = UrujText,
                    fontWeight = FontWeight.Black,
                    fontSize = 32.sp,
                )
                Text(
                    "Multi-sport training metrics — power-based load for cycling, " +
                        "HR-based load (Banister-style) for runs + other sessions tracked " +
                        "by your Samsung Fit Band 3. Wellness data (sleep staging, activity, " +
                        "body comp, live stress) stays in Samsung Health.",
                    color = UrujMuted,
                    fontSize = 12.sp,
                )
                snapshot?.let { snap ->
                    Spacer(Modifier.height(4.dp))
                    LiveAgeText(thenMs = snap.computedAtMs, prefix = "Last refresh: ")
                }
            }

            val s = snapshot
            if (s == null) {
                // v0.9.27 — per-card skeleton placeholders (Task #178) matching
                // the v0.9.23 pattern used by Readiness + Weekly Polarized cards.
                // Replaces the previous centered single CircularProgressIndicator
                // for UX consistency. Each skeleton mirrors the height + outline
                // of the real card so the LazyColumn doesn't reflow when data
                // lands. Order matches the actual card sequence below.
                item("skel_tsb") {
                    com.uruj.ui.components.LoadingCardSkeleton(
                        label = "TRAINING STATE — TSB",
                        cardHeight = 140.dp,
                    )
                }
                item("skel_sleep") {
                    com.uruj.ui.components.LoadingCardSkeleton(
                        label = "SLEEP PATTERN",
                        cardHeight = 110.dp,
                    )
                }
                item("skel_vo2") {
                    com.uruj.ui.components.LoadingCardSkeleton(
                        label = "VO2 MAX",
                        cardHeight = 130.dp,
                    )
                }
                item("skel_cardio_header") { SectionHeader("Cardiovascular") }
                item("skel_hr") {
                    // v0.9.34 — shortened skeleton labels. Long labels were
                    // wrapping the "ANALYZING…" status text vertically because
                    // the header is a fixed-width Row; tight squeeze visual.
                    com.uruj.ui.components.LoadingCardSkeleton(
                        label = "HEART RATE",
                        cardHeight = 150.dp,
                    )
                }
                item("skel_autonomic_header") { SectionHeader("Autonomic Health") }
                item("skel_autonomic_hrv") {
                    com.uruj.ui.components.LoadingCardSkeleton(
                        label = "AUTONOMIC HRV",
                        cardHeight = 180.dp,
                    )
                }
                item("skel_autonomic_freq") {
                    com.uruj.ui.components.LoadingCardSkeleton(
                        label = "AUTONOMIC FREQUENCY",
                        cardHeight = 200.dp,
                    )
                }
                // v0.9.31 — postprandial card placeholder (only renders if user
                // has marked at least one meal in last 7 days)
                item("skel_postprandial") {
                    com.uruj.ui.components.LoadingCardSkeleton(
                        label = "POSTPRANDIAL HRV",
                        cardHeight = 180.dp,
                    )
                }
                return@LazyColumn
            }

            // v0.9.6 — Training State card (TSB hero + see-trend link).
            // Shown above VO2 so the rider's first signal is "where am I in
            // the training cycle?" — primary daily decision driver.
            item("tsb_card") { TrainingLoadCard(s, onSeeTrend = onOpenTsbTrend) }

            // v0.9.8 — Sleep pattern card. URUJ's value-add over Samsung
            // Health is the longitudinal trend (Samsung shows current night
            // staging — URUJ shows the multi-week arc). Card is minimal —
            // tap-through to the trend chart is the entire point.
            item("sleep_card") { SleepPatternCard(onSeeTrend = onOpenSleepTrend) }

            // Cycling-training metrics — what URUJ uniquely computes
            item("vo2") { Vo2MaxCard(s, onSeeTrend = onOpenVo2Trend) }
            if (s.hrr1Median != null) {
                item("hrr1") { HrRecoveryCard(s, onSeeTrend = onOpenHrr1Trend) }
            }

            item("cardio_header") { SectionHeader("Cardiovascular") }
            item("hr_card") { HeartRateCard(s, onSeeRhrTrend = onOpenRhrTrend) }
            if (s.karvonenZones != null) {
                item("zones_card") { KarvonenZonesCard(s.karvonenZones) }
            }

            // v0.7.0 — Autonomic Health section. Only shows when 24/7 monitoring
            // has captured RR data. RMSSD / SDNN / pNN50 from BLE chest strap.
            if (s.autonomicRmssdMs != null) {
                item("autonomic_header") { SectionHeader("Autonomic Health") }
                item("autonomic_card") { AutonomicHealthCard(s, onSeeTrend = onOpenHrvTrend) }
                // v0.9.25 — Frequency-domain + non-linear card (LF/HF / DFA α1).
                // Only renders when sufficient beats (>= 240) computed in last
                // HRV window; auto-hides while baseline-building.
                if (s.autonomicFrequencyDomain != null) {
                    item("autonomic_freq_card") {
                        FrequencyDomainCard(
                            s = s,
                            onSeeLfHfTrend = onOpenLfHfTrend,
                            onSeeDfaTrend = onOpenDfaAlpha1Trend,
                        )
                    }
                }
                // v0.9.31 — Postprandial HRV response card. Renders only when
                // at least one meal mark has been processed (75+ min after
                // user tapped MARK MEAL). Auto-hides until first analysis.
                // v0.9.32 — long-press triggers delete confirmation.
                s.latestPostprandial?.let { snap ->
                    item("postprandial_card") {
                        PostprandialResponseCard(
                            snap = snap,
                            onLongPress = { deleteMarkConfirmId = snap.mealMarkId },
                            onSeeTrend = onOpenPostprandialTrend,
                        )
                    }
                }
            }

            // v0.9.39 — Subjective + Behavioral Tracker section (#111).
            // One compact summary card showing all 4 Phase 1 trackers today's
            // values. Tap card → opens TrackerSheet to log a new entry.
            item("tracker_header") { SectionHeader("Subjective + Behavioral") }
            item("tracker_summary_card") {
                TrackerSummaryCard(
                    s = s,
                    onLogEntry = { showTrackerSheet = true },
                )
            }

            // v0.7.2 — CAR (Cortisol Awakening Response). Auto-resolved from
            // 24/7 NDJSON + last SleepSessionRecord. Card hides until ~45 min
            // post-wake when the window is complete.
            // v0.9.36 — when sleep ended <45 min ago, show placeholder instead
            // of silent hide so rider knows the test is COMPUTING (not broken).
            if (s.carResult != null && s.carInterpretation != null) {
                item("car_card") {
                    CarCard(s.carResult, s.carInterpretation, onSeeTrend = onOpenCarTrend)
                }
            } else if (s.carMinutesSinceWake != null && s.carMinutesSinceWake < 45) {
                item("car_pending_card") {
                    CarPendingCard(minutesSinceWake = s.carMinutesSinceWake)
                }
            }

            // v0.7.1 — Lab tests section. Manual rituals that produce new
            // autonomic snapshots on demand. Each test reads from the 24/7
            // NDJSON for its own window.
            item("tests_header") { SectionHeader("Lab tests") }
            item("orthostatic_card") {
                OrthostaticTestLauncherCard(
                    onStart = onOpenOrthostatic,
                    onSeeTrend = onOpenOrthostaticTrend,
                )
            }

            // Everything else lives in Samsung Health where it's shown better.
            // We do NOT proxy. We deep-link.
            item("external_header") { SectionHeader("External — see Samsung Health") }
            item("samsung_link") { SamsungHealthDeepLinkCard() }
        }
    }
    // v0.9.32 — meal-mark backdate picker. Surfaces when user taps "🍽 MEAL"
    // so a late tap (e.g. 15 min after finishing eating) can be timestamped
    // accurately by selecting the offset. Pre-window is anchored to the
    // adjusted timestamp = clean pre-meal baseline.
    if (showMealMarkPicker) {
        MealMarkBackdatePicker(
            onDismiss = { showMealMarkPicker = false },
            onConfirm = { offsetMinutes, eventType, note ->
                viewModel.markMeal(offsetMinutes, eventType, note)
                showMealMarkPicker = false
            },
        )
    }
    // v0.9.39 — tracker sheet (#111). Universal sheet for all tracker types
    // chosen in Phase 1; extends cleanly as phase 2-4 trackers ship.
    if (showTrackerSheet) {
        TrackerSheet(
            onDismiss = { showTrackerSheet = false },
            onSave = { type, numericValue, textValue, note ->
                viewModel.saveTrackerEntry(type, numericValue, textValue, note)
                showTrackerSheet = false
            },
        )
    }
    // v0.9.32 — delete confirmation for the long-press affordance on the
    // postprandial card. Cascades: deletes both the MealMark file + the
    // PostprandialSnapshot file, then triggers a Bio Lab refresh.
    // `let` lambda is non-composable; using if-block to host the dialog.
    if (deleteMarkConfirmId != null) {
        val id = deleteMarkConfirmId!!
        AlertDialog(
            onDismissRequest = { deleteMarkConfirmId = null },
            title = { Text("Delete meal reading?", color = UrujText) },
            text = {
                Text(
                    "This permanently removes this meal mark and its " +
                        "postprandial analysis from disk. Use if the meal was " +
                        "wrongly timed (e.g. tapped too late) so you can re-mark " +
                        "next time. Past readings can NOT be recreated from this.",
                    color = UrujMuted, fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMealMark(id)
                    deleteMarkConfirmId = null
                }) {
                    Text("DELETE", color = UrujZone5,
                        fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteMarkConfirmId = null }) {
                    Text("CANCEL", color = UrujAccent,
                        fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                }
            },
            containerColor = UrujSurface,
        )
    }
}

/**
 * v0.7.7 — small inline pill showing which sensor produced a single reading.
 * STRAP = green dot + "strap" / BAND = amber dot + "band" / etc.
 */
@Composable
private fun SourcePill(source: com.uruj.domain.SensorSource) {
    val color = when (source) {
        com.uruj.domain.SensorSource.STRAP -> UrujZone2
        com.uruj.domain.SensorSource.BAND -> UrujZone3
        com.uruj.domain.SensorSource.MIXED -> UrujZone4
        com.uruj.domain.SensorSource.UNKNOWN_LEGACY -> UrujMuted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(color, androidx.compose.foundation.shape.CircleShape),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            source.displayShort(),
            color = color.copy(alpha = 0.85f),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

/**
 * v0.7.7 — aggregated source breakdown shown next to a metric's hero number.
 * "all strap ✓" / "8 strap · 3 band" / "all band" / "legacy".
 *
 * Empty map → renders nothing (caller should null-check before showing).
 */
@Composable
private fun SourceBreakdownBadge(breakdown: Map<com.uruj.domain.SensorSource, Int>) {
    if (breakdown.isEmpty()) return
    val total = breakdown.values.sum()
    val parts = breakdown.entries.sortedByDescending { it.value }
    val text = if (parts.size == 1) {
        val only = parts[0]
        when (only.key) {
            com.uruj.domain.SensorSource.STRAP -> "all from chest strap ✓"
            com.uruj.domain.SensorSource.BAND -> "all from band (batched)"
            com.uruj.domain.SensorSource.MIXED -> "mixed (strap + band)"
            com.uruj.domain.SensorSource.UNKNOWN_LEGACY -> "legacy readings (pre-v0.7.7)"
        }
    } else {
        parts.joinToString(" · ") { "${it.value} ${it.key.displayShort()}" } +
            " (of $total)"
    }
    Text(
        text,
        color = UrujMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        color = UrujMuted,
        fontWeight = FontWeight.Black,
        fontSize = 11.sp,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun BioCard(
    title: String,
    accentColor: Color = UrujAccent,
    infoOnClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UrujSurface, RoundedCornerShape(16.dp))
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        // v0.9.7 — title row gains optional ⓘ button. Tap opens an
        // educational dialog with "what it is / why cyclists care / where
        // to live / FOR YOU RIGHT NOW" content. Lab-grade rule 8 — every
        // metric self-documents from inside the UI.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.uppercase(),
                color = accentColor,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                modifier = Modifier.weight(1f),
            )
            if (infoOnClick != null) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(onClick = infoOnClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "ⓘ",
                        color = UrujAccent,
                        fontSize = 14.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun MetricRow(label: String, value: String, subtitle: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = UrujMuted, fontWeight = FontWeight.Black, fontSize = 9.sp, letterSpacing = 1.5.sp)
            if (subtitle != null) {
                Text(subtitle, color = UrujMuted, fontWeight = FontWeight.Medium, fontSize = 10.sp)
            }
        }
        Text(
            value,
            color = UrujText,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Black,
            fontSize = 18.sp,
        )
    }
}

/**
 * v0.9.6 — Training Load (TSB) hero card. Reads the most-recent
 * [com.uruj.data.TsbSnapshot] directly from disk (independent of
 * BioLabSnapshot which doesn't carry TSB). Surfaces the headline TSB +
 * CTL + ATL + tier-coloured band. Tapping → TsbTrendScreen for the
 * 6-month fitness-vs-fatigue curve cyclists actually need.
 */
/**
 * v0.9.8 — minimal Sleep card. Reads latest [com.uruj.data.SleepSnapshot]
 * from disk so the rider sees last night's hours inline, plus tap-through
 * to the trend chart (the actual value-add — multi-week arc).
 *
 * Honors the v0.4.0 cut: stages (REM/Deep/Light/Awake) live in Samsung
 * Health, not URUJ. This card shows hours only — the longitudinal pattern
 * is URUJ's contribution.
 */
@Composable
private fun SleepPatternCard(onSeeTrend: () -> Unit = {}) {
    val context = LocalContext.current
    val repo = remember { com.uruj.data.SleepSnapshotRepository(context) }
    var latest by remember { mutableStateOf<com.uruj.data.SleepSnapshot?>(null) }
    var snapshotCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        val all = withContext(kotlinx.coroutines.Dispatchers.IO) { repo.listAll() }
        latest = all.firstOrNull()
        snapshotCount = all.size
    }
    BioCard("Sleep — longitudinal pattern") {
        val s = latest
        if (s == null) {
            Text(
                "No sleep snapshots yet. Wear band overnight + open URUJ in " +
                    "the morning. URUJ persists each night's hours to disk so " +
                    "you can see the multi-week trend — Samsung Health shows " +
                    "stages, URUJ shows the long arc.",
                color = UrujMuted, fontSize = 12.sp,
            )
        } else {
            val tier = when {
                s.hoursTotal in 7f..9f -> UrujZone2 to "Optimal range"
                s.hoursTotal in 6f..7f -> UrujZone3 to "Acceptable"
                s.hoursTotal in 9f..10f -> UrujZone3 to "Slightly over"
                s.hoursTotal in 5f..6f -> UrujZone3 to "Under-slept"
                s.hoursTotal < 5f -> UrujZone5 to "Severe deficit"
                else -> UrujZone3 to "Recovery sleep"
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "%.1f".format(s.hoursTotal),
                    color = tier.first,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    fontSize = 44.sp,
                    letterSpacing = (-1).sp,
                )
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.padding(bottom = 10.dp)) {
                    Text("hours last night", color = UrujMuted, fontSize = 10.sp)
                    Text(tier.second, color = tier.first, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "$snapshotCount night${if (snapshotCount == 1) "" else "s"} on " +
                    "disk · source ${s.source}",
                color = UrujMuted, fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Stages (REM / Deep / Light / Awake) live in Samsung Health. URUJ " +
                "owns the longitudinal pattern — the trend cyclists care about.",
            color = UrujMuted, fontSize = 10.sp,
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onSeeTrend, modifier = Modifier.fillMaxWidth()) {
            Text(
                "SEE SLEEP TREND →",
                color = UrujAccent, fontWeight = FontWeight.Black,
                fontSize = 12.sp, letterSpacing = 1.5.sp,
            )
        }
    }
}

@Composable
private fun TrainingLoadCard(@Suppress("UNUSED_PARAMETER") s: BioLabSnapshot, onSeeTrend: () -> Unit = {}) {
    val context = LocalContext.current
    val repo = remember { com.uruj.data.TsbSnapshotRepository(context) }
    var latest by remember { mutableStateOf<com.uruj.data.TsbSnapshot?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        latest = withContext(kotlinx.coroutines.Dispatchers.IO) {
            repo.listAll().firstOrNull()
        }
    }
    val tsb = latest
    if (tsb == null) {
        BioCard("Training State — fitness vs fatigue", infoOnClick = { showInfo = true }) {
            Text(
                "TSB snapshots will populate as you open Readiness + record rides. " +
                    "Curve unlocks at ~3 days of data.",
                color = UrujMuted, fontSize = 12.sp,
            )
        }
        if (showInfo) TrainingStateInfoDialog(tsb = null, onDismiss = { showInfo = false })
        return
    }
    val accent = when {
        tsb.tsb >= 5f -> UrujZone2          // fresh
        tsb.tsb >= -5f -> UrujZone2         // balanced
        tsb.tsb >= -15f -> UrujZone3        // productive fatigue
        tsb.tsb >= -25f -> UrujZone3        // significant fatigue
        else -> UrujZone5                    // over-trained
    }
    val tierLabel = when {
        tsb.tsb >= 5f -> "FRESH"
        tsb.tsb >= -5f -> "BALANCED"
        tsb.tsb >= -15f -> "PRODUCTIVE FATIGUE"
        tsb.tsb >= -25f -> "SIGNIFICANT FATIGUE"
        else -> "OVER-TRAINED"
    }
    BioCard(
        "Training State — fitness vs fatigue",
        accentColor = accent,
        infoOnClick = { showInfo = true },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            // v0.9.18 — use roundToInt() to match every other TSB display
            // surface (Readiness card, ReadinessRecommendationEngine drivers,
            // BioLabInfoDialogs YouSection, ReadinessCalculator tier labels).
            // Pre-fix this hero used .toInt() (truncates toward zero) while
            // others used .roundToInt() (rounds half away from zero) →
            // when TSB landed at -29.5 the hero read -29 while Readiness card
            // read -30. One number, one rounding convention everywhere.
            val tsbInt = tsb.tsb.roundToInt()
            Text(
                if (tsb.tsb >= 0f) "+$tsbInt" else "$tsbInt",
                color = accent,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                fontSize = 56.sp,
                letterSpacing = (-2).sp,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                Text("TSB", color = UrujMuted, fontWeight = FontWeight.Black,
                    fontSize = 10.sp, letterSpacing = 1.5.sp)
                Text("CTL − ATL", color = UrujMuted, fontSize = 10.sp)
            }
        }
        Text(tierLabel, color = accent, fontWeight = FontWeight.Black,
            fontSize = 12.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(UrujSurfaceHigh.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text("BREAKDOWN", color = UrujMuted, fontWeight = FontWeight.Black,
                fontSize = 9.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                "CTL (fitness, 42d EWMA): ${"%.1f".format(tsb.ctl)}",
                color = UrujText, fontSize = 11.sp,
            )
            Text(
                "ATL (fatigue, 7d EWMA): ${"%.1f".format(tsb.atl)}",
                color = UrujText, fontSize = 11.sp,
            )
            Text(
                "42d total TSS: ${"%.0f".format(tsb.totalLoad42d)}",
                color = UrujText, fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Race-day target +5..+15. Productive training −10..−20. Below −25 = " +
                "rest mandated. Curve over months shows taper / overload / recovery " +
                "patterns — what cyclists pay Strava + Garmin to see.",
            color = UrujMuted, fontSize = 10.sp,
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onSeeTrend, modifier = Modifier.fillMaxWidth()) {
            Text(
                "SEE TSB TREND →",
                color = UrujAccent, fontWeight = FontWeight.Black,
                fontSize = 12.sp, letterSpacing = 1.5.sp,
            )
        }
    }
    if (showInfo) TrainingStateInfoDialog(tsb = tsb, onDismiss = { showInfo = false })
}

@Composable
private fun Vo2MaxCard(s: BioLabSnapshot, onSeeTrend: () -> Unit = {}) {
    var showInfo by remember { mutableStateOf(false) }
    BioCard(
        "VO₂ Max — aerobic capacity",
        accentColor = UrujZone2,
        infoOnClick = { showInfo = true },
    ) {
        val urujVo2 = s.vo2MaxConsensus
        val samsungVo2 = s.vo2MaxFromSamsung
        if (urujVo2 == null && samsungVo2 == null) {
            Text(
                "Need HR data + a few rides to estimate. Wear band more, ride more.",
                color = UrujMuted,
                fontSize = 12.sp,
            )
            return@BioCard
        }
        // Hero number — prefer Samsung's band-measured if it exists (more
        // authoritative); otherwise show URUJ's HR-formula estimate.
        val heroValue = samsungVo2 ?: urujVo2!!
        val heroSourceLabel = if (samsungVo2 != null) "Samsung Health (band-measured)"
        else "URUJ estimate (Uth-Sørensen HR-based)"
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "%.1f".format(heroValue),
                color = UrujZone2,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                fontSize = 56.sp,
                letterSpacing = (-2).sp,
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                Text(
                    "mL/kg/min",
                    color = UrujMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
                Text(
                    heroSourceLabel,
                    color = UrujMuted,
                    fontSize = 10.sp,
                )
            }
        }
        Text(
            s.vo2MaxClassification,
            color = UrujText,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        // Side-by-side: show both sources when both exist (transparency moat).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(UrujSurfaceHigh.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                "SOURCES",
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(2.dp))
            if (samsungVo2 != null) {
                Text(
                    "Samsung Health: ${"%.1f".format(samsungVo2)} mL/kg/min (band-measured)",
                    color = UrujText, fontSize = 11.sp,
                )
            }
            if (s.vo2MaxHrBased != null) {
                Text(
                    "URUJ Uth-Sørensen (HR-based): ${"%.1f".format(s.vo2MaxHrBased)} mL/kg/min",
                    color = UrujText, fontSize = 11.sp,
                )
            }
            if (s.vo2MaxPowerBased != null) {
                Text(
                    "URUJ Power-based (FTP/weight): ${"%.1f".format(s.vo2MaxPowerBased)} mL/kg/min",
                    color = UrujText, fontSize = 11.sp,
                )
            } else if (s.ftpIsLikelyUntested) {
                Text(
                    "URUJ Power-based: omitted — FTP at 200W placeholder. " +
                        "20-min all-out test → set FTP in Profile → unlocks cross-validation.",
                    color = UrujMuted, fontSize = 10.sp,
                )
            }
            if (samsungVo2 == null) {
                Text(
                    "Samsung Health: not in Health Connect yet — wear band during workouts to seed it.",
                    color = UrujMuted, fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Formula: 15 × (HRmax / HRrest). Cooper classification table. ≈ estimate, not lab measurement.",
                color = UrujMuted, fontSize = 9.sp,
            )
        }
        // v0.9.6 — daily VO2 snapshots persisted to disk since v0.9.1; trend
        // chart visualises long-arc aerobic fitness arc. Becomes useful after
        // ~5 days of data when slope direction stabilises.
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onSeeTrend, modifier = Modifier.fillMaxWidth()) {
            Text(
                "SEE TREND →",
                color = UrujAccent, fontWeight = FontWeight.Black,
                fontSize = 12.sp, letterSpacing = 1.5.sp,
            )
        }
    }
    if (showInfo) Vo2MaxInfoDialog(s = s, onDismiss = { showInfo = false })
}

@Composable
private fun HrRecoveryCard(s: BioLabSnapshot, onSeeTrend: () -> Unit = {}) {
    var showInfo by remember { mutableStateOf(false) }
    val drop = s.hrr1Median ?: return
    // Color the hero number by the cardiology-grade band.
    val accent = when {
        drop >= 18 -> UrujZone2  // green — excellent
        drop >= 12 -> UrujZone3  // amber — average
        else -> UrujZone5        // red — poor / elevated CV risk
    }
    BioCard(
        "HR Recovery (HRR1) — autonomic health",
        accentColor = accent,
        infoOnClick = { showInfo = true },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                drop.toString(),
                color = accent,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                fontSize = 56.sp,
                letterSpacing = (-2).sp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "bpm drop in 60s",
                color = UrujMuted,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        Text(
            s.hrr1Classification ?: "—",
            color = UrujText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        )
        // v0.7.7 — source breakdown badge (which sensor produced these readings)
        if (s.hrr1SourceBreakdown.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            SourceBreakdownBadge(s.hrr1SourceBreakdown)
        }
        if (s.hrr1AthleteContext != null) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UrujSurfaceHigh.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    "FOR YOUR FITNESS TIER",
                    color = UrujMuted,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    s.hrr1AthleteContext,
                    color = UrujText, fontSize = 11.sp,
                )
            }
        }
        if (s.hrr1RecentSamples.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UrujSurfaceHigh.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                // Label adapts to total count so the rider always sees "3 of N"
                // when more rides exist — keeps card compact while signaling
                // that the median uses the full set, not just these 3.
                val totalCount = s.hrr1SampleCount
                val shownCount = minOf(3, s.hrr1RecentSamples.size)
                val header = if (totalCount > shownCount) {
                    "$shownCount LATEST OF $totalCount QUALIFYING RIDES"
                } else {
                    "RECENT RIDES (latest first)"
                }
                Text(
                    header,
                    color = UrujMuted,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(4.dp))
                s.hrr1RecentSamples.take(3).forEach { sample ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatHrrSampleDate(sample.endTimeMs),
                            color = UrujMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            modifier = Modifier.width(56.dp),
                        )
                        Text(
                            "peak ${sample.peakBpm}",
                            color = UrujMuted, fontSize = 10.sp,
                            modifier = Modifier.weight(1f),
                        )
                        // v0.7.7 — per-sample source pill
                        SourcePill(sample.source)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${sample.hrr1Bpm} bpm drop",
                            color = UrujText,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Median of ${s.hrr1SampleCount} rides where peak HR ≥120 bpm, measured 30-180s post-ride. " +
                "Peer-reviewed cardiovascular health metric (Cole et al., NEJM 1999). " +
                "Lower drop → higher all-cause mortality risk independent of VO₂ max.",
            color = UrujMuted, fontSize = 10.sp,
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onSeeTrend, modifier = Modifier.fillMaxWidth()) {
            Text(
                "SEE TREND →",
                color = UrujAccent, fontWeight = FontWeight.Black,
                fontSize = 12.sp, letterSpacing = 1.5.sp,
            )
        }
    }
    if (showInfo) Hrr1InfoDialog(s = s, onDismiss = { showInfo = false })
}

@Composable
private fun HeartRateCard(s: BioLabSnapshot, onSeeRhrTrend: () -> Unit = {}) {
    var showInfo by remember { mutableStateOf(false) }
    // v0.4.0 slim — 4 cycling-training-relevant rows only.
    // Cut: today min/max (Samsung mirror), Samsung Direct RHR (same), HRV proxy
    // (misleading fake number). See [[reference_cut_features_v0_4]].
    BioCard("Heart Rate", infoOnClick = { showInfo = true }) {
        MetricRow(
            "MAX HR (effective)",
            value = "${s.maxHrBpm} bpm",
            subtitle = if (s.maxHrAutoDetected) "auto-detected from your rides — high confidence"
            else "220−age estimate, ±10-12 bpm. Hit ≥${s.maxHrBpm + 1} bpm in a ride to auto-bump.",
        )
        if (s.highestHr30d != null) {
            MetricRow(
                "30d PEAK",
                value = "${s.highestHr30d} bpm",
                subtitle = "hardest observed effort in 30d — athletic ceiling",
            )
        }
        if (s.restingHrBpm != null) {
            MetricRow(
                "HR RESERVE",
                value = "${s.maxHrBpm - s.restingHrBpm} bpm",
                subtitle = "max − resting — powers Karvonen zone math",
            )
            MetricRow(
                "ATHLETIC RHR",
                value = "${s.restingHrBpm} bpm",
                subtitle = s.restingHrSourceLabel + " · for Samsung's daily RHR open Samsung Health",
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Today's HR min/max and live HRV moved to Samsung Health. Their band firmware sees more.",
            color = UrujMuted, fontSize = 9.sp,
        )
        // v0.9.6 — Athletic RHR trend link. Daily snapshots persisted to disk
        // since v0.9.1; trend chart visualises the long-arc athletic-RHR drop
        // that correlates with VO2 build + training adaptation.
        if (s.restingHrBpm != null) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onSeeRhrTrend, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "SEE ATHLETIC RHR TREND →",
                    color = UrujAccent, fontWeight = FontWeight.Black,
                    fontSize = 12.sp, letterSpacing = 1.5.sp,
                )
            }
        }
    }
    if (showInfo) HeartRateInfoDialog(s = s, onDismiss = { showInfo = false })
}

@Composable
private fun KarvonenZonesCard(zones: KarvonenZonesCalculator.Result) {
    var showInfo by remember { mutableStateOf(false) }
    BioCard("Karvonen HR Zones — personalized", infoOnClick = { showInfo = true }) {
        Text(
            "Computed from YOUR heart rate reserve (${zones.hrReserve} bpm). More accurate than %-of-max because it accounts for your specific resting HR.",
            color = UrujMuted, fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))
        // v0.9.17 — surface the sub-Z1 band (HR below 50% HRR) so the rider
        // knows where their actual recovery floor sits. TIZ now exposes
        // sub-Z1 as its own bucket; this row makes the numeric threshold
        // visible alongside the standard 5 zones.
        SubZ1Row(floorBpm = zones.zones.first().lowerBpm, restingHrBpm = zones.hrRest)
        Spacer(Modifier.height(4.dp))
        zones.zones.forEach { zone ->
            ZoneRow(zone)
            Spacer(Modifier.height(4.dp))
        }
    }
    if (showInfo) KarvonenInfoDialog(zones = zones, onDismiss = { showInfo = false })
}

@Composable
private fun SubZ1Row(floorBpm: Int, restingHrBpm: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(8.dp).clip(CircleShape).background(UrujZoneBelowZ1),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Sub-Z1  Recovery floor",
            color = UrujText,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            "<$floorBpm",
            color = UrujZoneBelowZ1,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 14.sp,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "bpm",
            color = UrujMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ZoneRow(zone: KarvonenZonesCalculator.Zone) {
    val color = when (zone.number) {
        1 -> UrujZone1
        2 -> UrujZone2
        3 -> UrujZone3
        4 -> UrujZone4
        else -> UrujZone5
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(8.dp).clip(CircleShape).background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Z${zone.number}  ${zone.name}",
            color = UrujText,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${zone.lowerBpm}–${zone.upperBpm}",
            color = color,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 14.sp,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "bpm",
            color = UrujMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SamsungHealthDeepLinkCard() {
    val ctx = LocalContext.current
    BioCard("Open in Samsung Health", accentColor = UrujMuted) {
        Text(
            "These metrics live in Samsung Health with full depth — sleep stages, breathing rate, " +
                "live stress, daily activity, body composition. URUJ does NOT proxy them with worse inputs.",
            color = UrujMuted, fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))
        val items = listOf(
            "Sleep" to "stages, score, breathing rate",
            "Stress" to "live stress score (band-measured)",
            "Activity" to "steps, distance, calories today",
            "Body" to "weight, BMI, body comp",
        )
        items.forEach { (label, desc) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        label,
                        color = UrujText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                    Text(desc, color = UrujMuted, fontSize = 10.sp)
                }
                TextButton(onClick = { launchSamsungHealth(ctx) }) {
                    Text(
                        "OPEN →",
                        color = UrujAccent,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                    )
                }
            }
        }
    }
}

private fun launchSamsungHealth(ctx: android.content.Context) {
    val pkg = "com.sec.android.app.shealth"
    val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
    val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    runCatching { ctx.startActivity(intent) }
}

/** Human-readable elapsed time since a timestamp ("12s ago" / "4m ago" / "2h ago"). */
private fun relativeAge(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val ageMs = (nowMs - thenMs).coerceAtLeast(0L)
    val seconds = ageMs / 1000
    return when {
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}

/**
 * Self-updating "Last refresh: Xs ago" label. produceState ticks `now` every
 * second, forcing recomposition of just this Text. Restarts the ticker
 * whenever the underlying timestamp changes (i.e., user hits REFRESH).
 */
@Composable
private fun LiveAgeText(thenMs: Long, prefix: String = "") {
    val now by produceState(initialValue = System.currentTimeMillis(), thenMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    Text(
        text = "$prefix${relativeAge(thenMs, now)}",
        color = UrujMuted,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
    )
}

private val hrrSampleDateFmt = SimpleDateFormat("MMM d", Locale.getDefault())
private fun formatHrrSampleDate(ms: Long): String = hrrSampleDateFmt.format(Date(ms))

/**
 * v0.7.0 — Autonomic Health card. Real RMSSD HRV computed from BLE chest
 * strap RR intervals captured 24/7 by BiometricService. Replaces the
 * "chest strap unlocks" placeholder that was dimmed since v0.4.0.
 *
 * Shows:
 *   - RMSSD (hero) — parasympathetic / vagal tone marker
 *   - SDNN — overall HRV
 *   - pNN50 % — clinical parasympathetic indicator
 *   - Mean HR over the window
 *   - Sample count + window label ("last sleep" / "last 24h")
 *   - Reference ranges for context (athletic norms)
 *
 * Methodology footer per lab-level rule 3 — formula + filter rationale
 * visible so the rider can audit the number.
 */
@Composable
private fun AutonomicHealthCard(s: BioLabSnapshot, onSeeTrend: () -> Unit = {}) {
    val rmssd = s.autonomicRmssdMs ?: return
    var showInfo by remember { mutableStateOf(false) }
    // Color-code RMSSD by athletic-tier ranges. Norms from Plews et al.
    // (elite cyclist HRV) + Shaffer & Ginsberg 2017 (general adult HRV):
    //   <20ms = severely suppressed (illness, overtraining)
    //   20-30ms = below average
    //   30-50ms = average healthy adult
    //   50-80ms = trained athlete range
    //   80+ms = elite parasympathetic dominance
    val accent = when {
        rmssd >= 50f -> UrujZone2
        rmssd >= 30f -> UrujZone3
        rmssd >= 20f -> UrujZone3
        else -> UrujZone5
    }
    val tierLabel = when {
        rmssd >= 80f -> "Elite parasympathetic dominance ✓"
        rmssd >= 50f -> "Trained athlete range ✓"
        rmssd >= 30f -> "Average healthy adult range"
        rmssd >= 20f -> "Below athletic average"
        else -> "Below athletic average — check trend"
    }
    BioCard(
        "Autonomic HRV — beat-to-beat parasympathetic",
        accentColor = accent,
        infoOnClick = { showInfo = true },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "%.1f".format(rmssd),
                color = accent,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                fontSize = 56.sp,
                letterSpacing = (-2).sp,
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                Text(
                    "ms RMSSD",
                    color = UrujMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
                Text(
                    "from ${s.autonomicWindowLabel}",
                    color = UrujMuted,
                    fontSize = 10.sp,
                )
            }
        }
        Text(
            tierLabel,
            color = UrujText,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(UrujSurfaceHigh.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(10.dp),
        ) {
            Text(
                "BREAKDOWN",
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(4.dp))
            s.autonomicSdnnMs?.let {
                Text("SDNN (overall HRV): ${"%.1f".format(it)} ms",
                    color = UrujText, fontSize = 11.sp)
            }
            s.autonomicPnn50Pct?.let {
                Text("pNN50: ${"%.1f".format(it)}%",
                    color = UrujText, fontSize = 11.sp)
            }
            s.autonomicMeanHrBpm?.let {
                Text("Mean HR over window: ${it.toInt()} bpm",
                    color = UrujText, fontSize = 11.sp)
            }
            Text(
                "Median of ${s.autonomicWindowCount} 5-min windows · " +
                    "${s.autonomicSampleCount} clean RR intervals",
                color = UrujMuted, fontSize = 11.sp,
            )
        }
        // Baseline-building notice — only shows during the first 7 days of
        // continuous monitoring when we don't yet have a stable personal
        // baseline to compare against. Surfaces honestly that today's number
        // is being measured but not yet trend-contextualized.
        if (s.autonomicDaysOfData < 7) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UrujSurfaceHigh.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                Text(
                    "BASELINE BUILDING",
                    color = UrujMuted,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.sp,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Day ${s.autonomicDaysOfData} of 7. Today's number is your absolute " +
                        "RMSSD compared to general athletic norms. After 7 nights captured, " +
                        "we switch to ratio-vs-YOUR-baseline for catching subtle changes.",
                    color = UrujText, fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "RMSSD = √(mean of squared consecutive RR diffs). 5-min windowed " +
                "(Task Force 1996 standard), median-aggregated across natural " +
                "overnight breathing. Filters: 300–2000 ms physiological range + " +
                "timestamp-aware consecutive-beat check + 20% ectopic delta cap " +
                "(Kubios). Real ECG data from Magene H613, NOT a PPG proxy. " +
                "Athletic norms: <20 below / 20-30 low / 30-50 average / 50-80 " +
                "trained / 80+ elite (Plews et al., Shaffer & Ginsberg 2017).\n\n" +
                "Why this can differ from Elite HRV / morning seated readings: " +
                "those use paced breathing (~5 breaths/min) which maximizes RSA " +
                "and inflates RMSSD 1.5-3× over natural breathing. URUJ measures " +
                "your actual overnight autonomic state, not a paced-breathing peak. " +
                "Both are valid — they answer different questions.",
            color = UrujMuted, fontSize = 10.sp,
        )
        Spacer(Modifier.height(10.dp))
        // v0.7.3 — deep-view trend chart link. Becomes useful day 3+ as
        // nightly readings accumulate.
        TextButton(
            onClick = onSeeTrend,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "SEE TREND →",
                color = UrujAccent,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 1.5.sp,
            )
        }
    }
}

/**
 * v0.9.25 — Frequency-domain + non-linear HRV card. Renders on Bio Lab
 * directly below AutonomicHealthCard. Computed over the same overnight
 * RR window as RMSSD via [com.uruj.power.FrequencyDomainCalculator].
 *
 * Headlines: LF/HF ratio (autonomic balance, classical Kubios index) +
 * DFA α1 (fractal scaling; foundation for future LT1 detection from
 * ramp test per Rogero 2021). Breakdown box exposes LF/HF/VLF power +
 * Poincaré SD1/SD2 + sample entropy.
 *
 * ⓘ dialog explains each metric ELI10 + caveats. Critical: LF/HF
 * mechanistic interpretation is partially debunked (Heathers 2014,
 * Hayano 2019); UI shows the caveat per lab-level rule 4 (no fake
 * numbers).
 */
@Composable
private fun FrequencyDomainCard(
    s: BioLabSnapshot,
    onSeeLfHfTrend: () -> Unit = {},
    onSeeDfaTrend: () -> Unit = {},
) {
    val fd = s.autonomicFrequencyDomain ?: return
    var showInfo by remember { mutableStateOf(false) }
    // LF/HF ratio tier (Heathers 2014 / Hayano 2019 caveat applies):
    //   <1.0 → parasympathetic dominant (deep recovery / high vagal tone)
    //   1.0-2.0 → balanced (normal autonomic state)
    //   >2.0 → sympathetic dominant (stress / fatigue / overload)
    val lfHf = fd.lfHfRatio
    val accent = when {
        lfHf == null -> UrujMuted
        lfHf < 1.0f -> UrujZone2
        lfHf < 2.0f -> UrujZone3
        else -> UrujZone5
    }
    val tierLabel = when {
        lfHf == null -> "Baseline building — analyzing"
        lfHf < 1.0f -> "Parasympathetic dominant — deep recovery zone"
        lfHf < 2.0f -> "Balanced autonomic state"
        lfHf < 4.0f -> "Sympathetic dominant — stress / fatigue"
        else -> "High sympathetic — overload / acute stress"
    }
    BioCard(
        "Autonomic Frequency — LF/HF + DFA α1",
        accentColor = accent,
        infoOnClick = { showInfo = true },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = lfHf?.let { "%.2f".format(it) } ?: "—",
                color = accent,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                fontSize = 48.sp,
                letterSpacing = (-1.5).sp,
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.padding(bottom = 10.dp)) {
                Text(
                    "LF / HF",
                    color = UrujMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                )
                Text(
                    "autonomic balance",
                    color = UrujMuted,
                    fontSize = 9.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            // DFA α1 secondary hero — the athletic-tier killer feature.
            // Healthy ~1.0; <0.75 crosses above LT1 (Rogero 2021).
            fd.dfaAlpha1?.let { dfa ->
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(bottom = 10.dp),
                ) {
                    Text(
                        text = "%.2f".format(dfa),
                        color = dfaAlpha1Color(dfa),
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Black,
                        fontSize = 28.sp,
                    )
                    Text(
                        "DFA α1",
                        color = UrujMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                    )
                }
            }
        }
        Text(
            tierLabel,
            color = UrujText,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        // Breakdown — power bands + Poincaré + sample entropy
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(UrujSurfaceHigh.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(10.dp),
        ) {
            Text(
                "BREAKDOWN",
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(4.dp))
            fd.lfMs2?.let {
                Text("LF power (0.04-0.15 Hz): ${"%.0f".format(it)} ms²",
                    color = UrujText, fontSize = 11.sp)
            }
            fd.hfMs2?.let {
                Text("HF power (0.15-0.4 Hz): ${"%.0f".format(it)} ms²",
                    color = UrujText, fontSize = 11.sp)
            }
            fd.vlfMs2?.let {
                Text("VLF power (0.0033-0.04 Hz): ${"%.0f".format(it)} ms²",
                    color = UrujText, fontSize = 11.sp)
            }
            fd.sd1Ms?.let {
                Text("Poincaré SD1 (short-term): ${"%.1f".format(it)} ms",
                    color = UrujText, fontSize = 11.sp)
            }
            fd.sd2Ms?.let {
                Text("Poincaré SD2 (long-term): ${"%.1f".format(it)} ms",
                    color = UrujText, fontSize = 11.sp)
            }
            fd.sampleEntropy?.let {
                Text("Sample entropy (complexity): ${"%.2f".format(it)}",
                    color = UrujText, fontSize = 11.sp)
            }
            Text(
                "Median of ${fd.windowCount} 5-min windows · ${fd.sampleCount} beats · " +
                    "Welch's PSD · DFA scales 4-16",
                color = UrujMuted, fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap ⓘ for what each metric means + honest caveats. " +
                "LF/HF \"sympathetic balance\" mapping is partially debunked " +
                "(Heathers 2014) — useful as empirical stress index but not " +
                "the clean mechanism originally claimed. DFA α1 is the athletic-" +
                "tier marker: crosses 0.75 at the rider's Aerobic Threshold " +
                "(Rogero 2021) — foundation for future LT1-from-ramp-test feature.",
            color = UrujMuted, fontSize = 10.sp,
        )
        // v0.9.28 — trend chart links. Each metric persists to disk daily
        // via HrvSnapshotRepository (v0.9.27 architecture), so the trend
        // becomes valuable from day 2 onwards.
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = onSeeLfHfTrend,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    "LF/HF TREND →",
                    color = UrujAccent,
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                )
            }
            TextButton(
                onClick = onSeeDfaTrend,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    "DFA α1 TREND →",
                    color = UrujAccent,
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                )
            }
        }
    }
    if (showInfo) FrequencyDomainInfoDialog(fd = fd, onDismiss = { showInfo = false })
}

/** DFA α1 tier color — healthy ~1.0; drops below 0.75 above Aerobic Threshold. */
private fun dfaAlpha1Color(dfa: Float): androidx.compose.ui.graphics.Color = when {
    dfa >= 0.85f && dfa < 1.15f -> UrujZone2  // healthy fractal scaling
    dfa >= 0.75f -> UrujZone3                  // slight stress / fatigue
    dfa >= 0.5f -> UrujZone4                   // above AeT (high intensity)
    dfa >= 1.15f -> UrujZone3                  // overcorrelated (overtraining?)
    else -> UrujZone5                          // anomalous
}

/**
 * v0.9.31 — Postprandial HRV response card. Renders the latest meal
 * mark's pre/post HRV comparison. Auto-hides until user has marked a
 * meal AND 75 min have elapsed (full post-window captured).
 *
 * Headline: RMSSD % drop post-meal — primary postprandial autonomic
 * signal per Hara 2016 + Tarvainen 2018. Larger drop = more
 * metabolically reactive meal.
 *
 * v0.9.32 — Long-press anywhere on the card triggers [onLongPress]
 * (caller shows the delete-confirmation dialog). Enables rider to
 * remove badly-timed meals (e.g. tapped 15 min late) so they can
 * re-mark cleanly without the wrong data polluting the trend chart.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PostprandialResponseCard(
    snap: com.uruj.domain.PostprandialSnapshot,
    onLongPress: () -> Unit = {},
    onSeeTrend: () -> Unit = {},
) {
    var showInfo by remember { mutableStateOf(false) }
    val dropPct = snap.rmssdDeltaPercent
    val accent = when {
        dropPct == null -> UrujMuted
        kotlin.math.abs(dropPct) < 15f -> UrujZone2  // stable
        kotlin.math.abs(dropPct) < 30f -> UrujZone3  // typical
        kotlin.math.abs(dropPct) < 50f -> UrujZone4  // sensitive
        else -> UrujZone5                            // strong
    }
    val tierLabel = when {
        dropPct == null -> "Insufficient strap data in pre/post window"
        kotlin.math.abs(dropPct) < 15f -> "Stable metabolism ✓"
        kotlin.math.abs(dropPct) < 30f -> "Typical autonomic response"
        kotlin.math.abs(dropPct) < 50f -> "Sensitive — review meal composition + pre-ride timing"
        else -> "Strong response — large meal, glucose-sensitive, or chronic-stress amplified"
    }
    val mealTimeLabel = run {
        val instant = java.time.Instant.ofEpochMilli(snap.mealMarkMs)
        val time = instant.atZone(java.time.ZoneId.systemDefault())
            .toLocalTime().withSecond(0).withNano(0)
        "Marked at $time"
    }
    // v0.9.35 — dynamic title based on event type
    val cardTitle = when (snap.eventType) {
        "coffee" -> "Coffee response — caffeine HRV signature"
        "drink" -> "Drink response — sugar HRV signature"
        "alcohol" -> "Alcohol response — recovery cost"
        "snack" -> "Snack response — HRV signature"
        "custom" -> "Event response — custom mark"
        else -> "Postprandial HRV response — meal stress test"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onLongPress,
            ),
    ) {
    BioCard(
        cardTitle,
        accentColor = accent,
        infoOnClick = { showInfo = true },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = dropPct?.let { "${if (it >= 0) "+" else ""}%.1f".format(it) } ?: "—",
                color = accent,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                fontSize = 48.sp,
                letterSpacing = (-1.5).sp,
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.padding(bottom = 10.dp)) {
                Text("% RMSSD CHANGE",
                    color = UrujMuted, fontWeight = FontWeight.Bold,
                    fontSize = 11.sp, letterSpacing = 1.sp)
                Text("(negative = HRV dropped post-meal)",
                    color = UrujMuted, fontSize = 9.sp)
            }
        }
        Text(tierLabel,
            color = UrujText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text(mealTimeLabel,
            color = UrujMuted, fontSize = 11.sp)
        // v0.9.35 — show rider's freeform note if present
        snap.note?.takeIf { it.isNotBlank() }?.let { n ->
            Spacer(Modifier.height(4.dp))
            Text("📝 $n",
                color = UrujText, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        // Breakdown
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(UrujSurfaceHigh.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(10.dp),
        ) {
            Text("BREAKDOWN",
                color = UrujMuted, fontWeight = FontWeight.Black,
                fontSize = 9.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(4.dp))
            snap.preRmssdMs?.let {
                Text("Pre-meal RMSSD (-30..-5 min): ${"%.1f".format(it)} ms",
                    color = UrujText, fontSize = 11.sp)
            }
            snap.postRmssdMs?.let {
                Text("Post-meal RMSSD (+45..+75 min): ${"%.1f".format(it)} ms",
                    color = UrujText, fontSize = 11.sp)
            }
            snap.hrDeltaBpm?.let {
                Text("Mean HR change: ${if (it >= 0) "+" else ""}${"%.1f".format(it)} bpm",
                    color = UrujText, fontSize = 11.sp)
            }
            snap.hfDeltaPercent?.let {
                Text("HF power change: ${if (it >= 0) "+" else ""}${"%.0f".format(it)}%",
                    color = UrujText, fontSize = 11.sp)
            }
            snap.lfHfDeltaPercent?.let {
                Text("LF/HF ratio change: ${if (it >= 0) "+" else ""}${"%.0f".format(it)}%",
                    color = UrujText, fontSize = 11.sp)
            }
            Text("Source: ${snap.source} · ${snap.preSampleCount}+${snap.postSampleCount} beats",
                color = UrujMuted, fontSize = 11.sp)
        }
        // v0.9.33 → v0.9.38 — edge-case warning chips. Render only when relevant.
        if (snap.overlapsPriorMeal || snap.isDuringSleep ||
            snap.rideOverlapsPostprandial ||
            (snap.preCoveragePct != null && snap.preCoveragePct < 0.6f) ||
            (snap.postCoveragePct != null && snap.postCoveragePct < 0.6f)
        ) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UrujZone5.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                Text(
                    "DATA QUALITY NOTES",
                    color = UrujZone5,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.sp,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(4.dp))
                if (snap.overlapsPriorMeal) {
                    Text(
                        "⚠ Pre-window overlaps with a prior meal's post-window. " +
                            "Baseline is another meal's recovery, not a true rest state. " +
                            "Interpret with caution.",
                        color = UrujText, fontSize = 11.sp,
                    )
                }
                if (snap.isDuringSleep) {
                    Text(
                        "⚠ Meal mark falls inside last sleep window. Pre-window " +
                            "is sleep state (not awake baseline). Useful midnight-snack " +
                            "data but interpret deltas differently than daytime meals.",
                        color = UrujText, fontSize = 11.sp,
                    )
                }
                if (snap.rideOverlapsPostprandial) {
                    val rideGap = snap.minutesToNextRide
                    val gapText = if (rideGap != null) {
                        if (rideGap < 75L) {
                            "Ride started $rideGap min after meal (recommended: 75+ min)."
                        } else {
                            "Ride overlapped post-window."
                        }
                    } else {
                        "Ride overlapped the post-window."
                    }
                    Text(
                        "⚠ Post-window captured during a RIDE — exercise " +
                            "autonomic effect dominates, not meal response. $gapText " +
                            "For clean postprandial data, wait 75+ min between meal " +
                            "start and ride start. This reading reflects exercise + " +
                            "digestion mixed.",
                        color = UrujText, fontSize = 11.sp,
                    )
                }
                snap.preCoveragePct?.takeIf { it < 0.6f }?.let { pct ->
                    Text(
                        "⚠ Pre-window strap coverage: ${(pct * 100).toInt()}% " +
                            "(strap was off / disconnected / 24/7 service paused for " +
                            "part of window). Check Pipeline → 24/7 MONITORING is ON " +
                            "and strap is connected continuously for cleaner readings.",
                        color = UrujText, fontSize = 11.sp,
                    )
                }
                snap.postCoveragePct?.takeIf { it < 0.6f }?.let { pct ->
                    Text(
                        "⚠ Post-window strap coverage: ${(pct * 100).toInt()}% " +
                            "(strap was off / disconnected for part of window).",
                        color = UrujText, fontSize = 11.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap ⓘ for what this means + honest caveats. " +
                "Postprandial HRV drop is a metabolic-stress indicator " +
                "(Hara 2016; Tarvainen 2018). Larger drops = stronger sympathetic " +
                "response to digestion. Useful for tuning pre-ride meal timing.",
            color = UrujMuted, fontSize = 10.sp,
        )
        // v0.9.34 — deep-view trend chart link. Becomes useful from
        // meal 2 onwards as readings accumulate.
        Spacer(Modifier.height(10.dp))
        TextButton(
            onClick = onSeeTrend,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "SEE TREND →",
                color = UrujAccent,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 1.5.sp,
            )
        }
    }
    }
    if (showInfo) PostprandialInfoDialog(snap = snap, onDismiss = { showInfo = false })
}

/**
 * v0.9.32 → v0.9.35 — Event-mark picker.
 *
 * v0.9.35 expansion: rider now picks BOTH:
 *   1. Event type (Meal / Snack / Coffee / Drink / Alcohol / Custom) —
 *      tunes card title + interpretation copy. Math is identical across
 *      types (same pre/post HRV windows).
 *   2. Time offset (Just now / 5 / 15 / 30 / 45 / 60 / 90 min ago) —
 *      for late-tap support.
 *   3. Optional note (e.g. "Black coffee + 1 sugar", "Coke + chips")
 *      — memory aid for later correlation.
 *
 * Custom HH:MM picker still deferred (Task #184).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealMarkBackdatePicker(
    onDismiss: () -> Unit,
    onConfirm: (offsetMinutes: Int, eventType: String, note: String?) -> Unit,
) {
    val timeOptions = listOf(
        0 to "Just now",
        5 to "5 min ago",
        15 to "15 min ago",
        30 to "30 min ago",
        45 to "45 min ago",
        60 to "1 hour ago",
        90 to "1.5 hours ago",
    )
    val typeOptions = listOf(
        "meal" to "🍽 Meal",
        "snack" to "🍪 Snack",
        "coffee" to "☕ Coffee",
        "drink" to "🥤 Drink",
        "alcohol" to "🍺 Alcohol",
        "custom" to "✱ Custom",
    )
    var selectedTime by remember { mutableStateOf(0) }
    var selectedType by remember { mutableStateOf("meal") }
    var noteText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark consumption event", color = UrujText) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text("WHAT DID YOU CONSUME?",
                    color = UrujMuted, fontWeight = FontWeight.Black,
                    fontSize = 10.sp, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    typeOptions.forEach { (key, label) ->
                        val isSelected = selectedType == key
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    if (isSelected) UrujAccent.copy(alpha = 0.20f)
                                    else UrujSurfaceHigh,
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) UrujAccent else UrujSurfaceHigh,
                                    RoundedCornerShape(20.dp),
                                )
                                .clickable { selectedType = key }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(label,
                                color = if (isSelected) UrujAccent else UrujText,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("WHEN DID IT START?",
                    color = UrujMuted, fontWeight = FontWeight.Black,
                    fontSize = 10.sp, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "URUJ analyzes -30..-5 min PRE-event window. If you tapped " +
                        "late, backdate so pre-window covers actual baseline.",
                    color = UrujMuted, fontSize = 11.sp,
                )
                Spacer(Modifier.height(8.dp))
                timeOptions.forEach { (mins, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedTime = mins }
                            .padding(vertical = 4.dp),
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selectedTime == mins,
                            onClick = { selectedTime = mins },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, color = UrujText, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("NOTE (OPTIONAL)",
                    color = UrujMuted, fontWeight = FontWeight.Black,
                    fontSize = 10.sp, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = noteText,
                    onValueChange = { if (it.length <= 100) noteText = it },
                    placeholder = {
                        Text("e.g. \"Rice + chicken + 2 eggs\" or \"Black coffee + 1 sugar\"",
                            color = UrujMuted, fontSize = 12.sp)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 2,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = UrujText, fontSize = 13.sp,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedTime, selectedType, noteText) }) {
                Text("MARK", color = UrujAccent,
                    fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = UrujMuted,
                    fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
            }
        },
        containerColor = UrujSurface,
    )
}

/**
 * v0.9.39 — Compact tracker summary card for the in-app subjective +
 * behavioral tracker layer (#111). Displays today's Mood / Energy /
 * Hydration / Caffeine values from the disk-persisted TrackerEntry list.
 *
 * **Why one summary card instead of 4 separate**: Bio Lab already has
 * many cards. The subjective layer is a single "self-rating" surface
 * conceptually — Whoop / Oura combine these into one journal too. Per-
 * tracker trend screens come in v0.9.40 once data has accumulated.
 *
 * Tap anywhere → opens TrackerSheet to log a new entry. Each row shows:
 *   - Tracker name + emoji
 *   - Today's value or "—" if not yet logged
 *   - Tap to log more
 */
@Composable
private fun TrackerSummaryCard(
    s: BioLabSnapshot,
    onLogEntry: () -> Unit = {},
) {
    val latestMood = s.moodEntriesToday.firstOrNull()?.numericValue?.toInt()
    val latestEnergy = s.energyEntriesToday.firstOrNull()?.numericValue?.toInt()
    val totalHydration = s.hydrationEntriesToday.sumOf { (it.numericValue ?: 0f).toInt() }
    val totalCaffeine = s.caffeineEntriesToday.sumOf { (it.numericValue ?: 0f).toInt() }
    // v0.9.40 Phase 2 trackers
    val supplementCount = s.supplementsEntriesToday.size
    val supplementNames = s.supplementsEntriesToday.mapNotNull { it.textValue }
    val bristolScore = s.bristolEntriesToday.firstOrNull()?.numericValue?.toInt()
    val sleepQualityScore = s.sleepQualityEntriesToday.firstOrNull()?.numericValue?.toInt()
    val sorenessLatest = s.sorenessEntriesToday.firstOrNull()
    val totalEntries = s.moodEntriesToday.size + s.energyEntriesToday.size +
        s.hydrationEntriesToday.size + s.caffeineEntriesToday.size +
        s.supplementsEntriesToday.size + s.bristolEntriesToday.size +
        s.sleepQualityEntriesToday.size + s.sorenessEntriesToday.size

    BioCard("Subjective + Behavioral — today") {
        if (totalEntries == 0) {
            Text(
                "No entries today yet. Tap + TRACK above to log mood, energy, " +
                    "hydration, or caffeine.",
                color = UrujMuted, fontSize = 12.sp,
            )
        } else {
            Text(
                "$totalEntries entries logged today",
                color = UrujMuted, fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(6.dp))
        TrackerRow(emoji = "🎭", label = "MOOD",
            value = latestMood?.let { "$it / 10" } ?: "—",
            sub = s.moodEntriesToday.firstOrNull()?.note)
        TrackerRow(emoji = "⚡", label = "ENERGY",
            value = latestEnergy?.let { "$it / 10" } ?: "—",
            sub = s.energyEntriesToday.firstOrNull()?.note)
        TrackerRow(emoji = "💧", label = "HYDRATION",
            value = if (totalHydration > 0) "$totalHydration ml" else "—",
            sub = if (s.hydrationEntriesToday.size > 1) "${s.hydrationEntriesToday.size} entries" else null)
        TrackerRow(emoji = "☕", label = "CAFFEINE",
            value = if (totalCaffeine > 0) "$totalCaffeine mg" else "—",
            sub = if (s.caffeineEntriesToday.size > 1) "${s.caffeineEntriesToday.size} entries" else null)
        // v0.9.40 Phase 2 trackers
        TrackerRow(emoji = "💊", label = "SUPPLEMENTS",
            value = if (supplementCount > 0) "$supplementCount logged" else "—",
            sub = if (supplementNames.isNotEmpty()) supplementNames.joinToString(", ").take(40) else null)
        TrackerRow(emoji = "🌀", label = "BRISTOL",
            value = bristolScore?.let { "Type $it" } ?: "—",
            sub = bristolScore?.let { num ->
                when {
                    num == 4 -> "ideal ✓"
                    num in 3..5 -> "normal"
                    num <= 2 -> "constipation risk"
                    else -> "loose"
                }
            })
        TrackerRow(emoji = "🌙", label = "SLEEP QUALITY",
            value = sleepQualityScore?.let { "$it / 10" } ?: "—",
            sub = s.sleepQualityEntriesToday.firstOrNull()?.note)
        TrackerRow(emoji = "🦵", label = "SORENESS",
            value = sorenessLatest?.numericValue?.toInt()?.let { "$it / 10" } ?: "—",
            sub = sorenessLatest?.textValue)
        Spacer(Modifier.height(10.dp))
        Text(
            "Subjective data captures what sensors can't — mood, hunger, " +
                "supplements, breathing, drinking water. Track over weeks to " +
                "correlate with HRV, sleep, performance.",
            color = UrujMuted, fontSize = 10.sp,
        )
        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = onLogEntry,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "+ LOG ENTRY",
                color = UrujAccent,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp, letterSpacing = 1.5.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Trend charts ship in v0.9.40 once you have a few days of data.",
            color = UrujMuted, fontSize = 9.sp,
        )
    }
}

@Composable
private fun TrackerRow(
    emoji: String,
    label: String,
    value: String,
    sub: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$emoji  $label",
            color = UrujMuted, fontSize = 11.sp,
            letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(value,
                color = if (value == "—") UrujMuted else UrujText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold)
            if (sub != null) {
                Text(sub, color = UrujMuted, fontSize = 9.sp)
            }
        }
    }
}

/**
 * v0.7.2 — Cortisol Awakening Response card. Surfaces the morning HPA-axis
 * activation pattern automatically from 24/7 NDJSON. Tier-coded with
 * Bio Lab's standard color language (green = healthy, red = blunted).
 *
 * Lives under "Autonomic Health" since it's another autonomic signal but
 * gets its own card because the methodology is different (single-event
 * detector, not windowed average).
 */
@Composable
private fun CarPendingCard(minutesSinceWake: Long) {
    // v0.9.36 — placeholder shown when sleep ended <45 min ago. CAR's
    // post-wake window needs ~30 min of data to be valid; until then
    // we'd otherwise silently hide the card (rider thinks it's broken).
    // This makes the COMPUTING state explicit.
    val minutesRemaining = (45L - minutesSinceWake).coerceAtLeast(1L)
    BioCard("CAR — computing post-wake window", accentColor = UrujMuted) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = UrujAccent,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "Awake $minutesSinceWake min · need ~$minutesRemaining more",
                    color = UrujText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                )
                Text(
                    "Cortisol Awakening Response needs the first ~45 min " +
                        "after you wake. Card will populate once the window completes.",
                    color = UrujMuted, fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun CarCard(
    car: com.uruj.domain.CarResult,
    interp: com.uruj.domain.CarInterpretation,
    onSeeTrend: () -> Unit = {},
) {
    var showInfo by remember { mutableStateOf(false) }
    val accent = carTierColor(interp.overallTier)
    // v0.9.7 — use BioCard's standard infoOnClick (consistent with all other
    // Bio Lab cards) instead of the v0.7.2 inline TextButton. Removes ⓘ
    // duplication; unifies the tooltip pattern across the screen.
    // v0.9.48.8 — primary amplitude = rigorous quiet-window mean-of-bins
    // (Pruessner/Clow/Stalder). Falls back to legacy wide-window peak for
    // older results saved before v0.9.48.8.
    val primaryAmp = car.quietWindowAmplitudeBpm ?: car.amplitudeBpm
    val hasRigorous = car.quietWindowAmplitudeBpm != null
    BioCard(
        "CAR — cortisol awakening response",
        accentColor = accent,
        infoOnClick = { showInfo = true },
    ) {
        Text(interp.summary, color = accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "+${"%.0f".format(primaryAmp)}",
                color = UrujText,
                fontWeight = FontWeight.Black,
                fontSize = 44.sp,
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.padding(bottom = 10.dp)) {
                Text("bpm quiet-window CAR", color = UrujMuted, fontSize = 10.sp)
                Text(
                    if (hasRigorous) "5-min bin means · 0-30 min post-wake"
                    else "legacy wide-window peak",
                    color = UrujMuted, fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(UrujSurfaceHigh.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(10.dp),
        ) {
            Text(
                "BREAKDOWN",
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Baseline (last 10 min of sleep): ${"%.0f".format(car.baselineHrBpm)} bpm · " +
                    "${"%.1f".format(car.baselineRmssdMs)} ms RMSSD",
                color = UrujText, fontSize = 11.sp,
            )
            if (hasRigorous) {
                Text(
                    "Quiet-window peak: ${"%.0f".format(car.quietWindowPeakMeanBpm ?: 0f)} bpm " +
                        "(${car.quietWindowPeakBinMinutes ?: 0}-${(car.quietWindowPeakBinMinutes ?: 0) + 5} min)",
                    color = UrujText, fontSize = 11.sp,
                )
                Text(
                    "Wide-window raw peak: ${"%.0f".format(car.peakHrBpm)} bpm " +
                        "(includes post-wake activity)",
                    color = UrujMuted, fontSize = 10.sp,
                )
            } else {
                Text(
                    "Peak post-wake: ${"%.0f".format(car.peakHrBpm)} bpm",
                    color = UrujText, fontSize = 11.sp,
                )
            }
            Text(
                "RMSSD drop on activation: ${"%.0f".format(car.rmssdDropPercent)}% " +
                    "(trough ${"%.1f".format(car.troughRmssdMs)} ms)",
                color = UrujText, fontSize = 11.sp,
            )
            Text(
                "${car.cleanBeatsInWindow} clean beats in 45-min window",
                color = UrujMuted, fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "CAR = HR + HRV inflection in first 30 min after waking, proxy " +
                "for cortisol surge (Pruessner 1997, Clow 2010, Stalder 2016). " +
                "v0.9.48.8: primary amplitude uses 5-min bin MEANS to filter " +
                "out post-wake activity (walking, kitchen, etc.) that " +
                "incorrectly inflated wide-window peak HR. " +
                "Healthy adult range: 10-20 bpm rise, peak 20-40 min post-wake. " +
                "Blunted CAR (<5 bpm) = chronic stress / burnout. " +
                "Robust CAR (20-30 bpm) = strong HPA-axis activation. " +
                "Computed from BLE chest strap RR + HC sleep window — " +
                "NOT salivary cortisol (the lab-gold standard).",
            color = UrujMuted, fontSize = 10.sp,
        )
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onSeeTrend, modifier = Modifier.fillMaxWidth()) {
            Text(
                "SEE TREND →",
                color = UrujAccent, fontWeight = FontWeight.Black,
                fontSize = 12.sp, letterSpacing = 1.5.sp,
            )
        }
    }
    if (showInfo) {
        CarInfoDialog(car = car, interpretation = interp, onDismiss = { showInfo = false })
    }
}

private fun carTierColor(tier: com.uruj.domain.CarTier): Color = when (tier) {
    com.uruj.domain.CarTier.NORMAL -> UrujZone2
    com.uruj.domain.CarTier.ROBUST -> UrujZone1
    com.uruj.domain.CarTier.SUPPRESSED -> UrujZone3
    com.uruj.domain.CarTier.EXAGGERATED -> UrujZone4
    com.uruj.domain.CarTier.BLUNTED -> UrujZone5
}

// v0.9.7 — private CarInfoDialog removed. Unified into the public
// CarInfoDialog in BioLabInfoDialogs.kt which now uses the same section/
// YouSection pattern as every other Bio Lab card. One tooltip style across
// the screen.

/**
 * v0.7.1 — launcher card for the manual orthostatic test ritual.
 * Shows the most-recent saved test result inline + a START button to take
 * a fresh reading. Reads history from OrthostaticTestRepository on first
 * composition.
 */
@Composable
private fun OrthostaticTestLauncherCard(
    onStart: () -> Unit,
    onSeeTrend: () -> Unit = {},
) {
    val context = LocalContext.current
    val repo = androidx.compose.runtime.remember {
        com.uruj.data.OrthostaticTestRepository(context)
    }
    val calc = androidx.compose.runtime.remember {
        com.uruj.power.OrthostaticTestCalculator()
    }
    var allReadings by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<List<com.uruj.domain.OrthostaticTestResult>>(emptyList())
    }
    LaunchedEffect(Unit) {
        allReadings = withContext(kotlinx.coroutines.Dispatchers.IO) { repo.listAll() }
    }
    val latest = allReadings.firstOrNull()
    var showInfo by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val interpretation = latest?.let { calc.interpret(it) }
    val tierAccent = interpretation?.let { tierColorForLauncher(it.overallTier) } ?: UrujAccent
    BioCard(
        "Orthostatic test — sit→stand autonomic snapshot",
        accentColor = tierAccent,
        infoOnClick = { showInfo = true },
    ) {
        // v0.9.7 — ⓘ button moved into BioCard's title row via infoOnClick.
        // Removes the inline TextButton — one consistent tooltip pattern across
        // every Bio Lab card.
        if (latest == null) {
            Text(
                "No reading yet. The 4-minute protocol gives you a second " +
                    "autonomic signal alongside overnight HRV — catches acute " +
                    "fatigue faster than the chronic overnight number does.",
                color = UrujText, fontSize = 12.sp,
            )
        } else {
            val r = latest
            Text(
                interpretation?.summary ?: "",
                color = tierAccent,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${"%.0f".format(r.hrDeltaBpm)}",
                    color = UrujText,
                    fontWeight = FontWeight.Black,
                    fontSize = 36.sp,
                )
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Text("bpm HR delta", color = UrujMuted, fontSize = 10.sp)
                    Text(
                        "RMSSD ratio %.2f".format(r.rmssdRatio),
                        color = UrujMuted, fontSize = 10.sp,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                interpretationHint(r, interpretation),
                color = UrujText.copy(alpha = 0.85f),
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(6.dp))
            val sinceText = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(r.startedAtMs))
            val countText = if (allReadings.size == 1) "1 reading" else "${allReadings.size} readings"
            Text(
                "Captured $sinceText · $countText on disk",
                color = UrujMuted, fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = UrujAccent,
            ),
        ) {
            Text(
                if (latest == null) "TAKE FIRST READING" else "TAKE NEW READING",
                color = Color.Black,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
            )
        }
        // v0.7.4: only surface trend link once at least one reading is saved.
        if (latest != null && allReadings.isNotEmpty()) {
            TextButton(onClick = onSeeTrend, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "SEE TREND →",
                    color = UrujAccent, fontWeight = FontWeight.Black,
                    fontSize = 12.sp, letterSpacing = 1.5.sp,
                )
            }
        }
    }
    if (showInfo) {
        OrthostaticInfoDialog(
            latest = latest,
            interpretation = interpretation,
            onDismiss = { showInfo = false },
        )
    }
}

/**
 * Bands-tier color for the launcher card title accent + tier-label color.
 */
private fun tierColorForLauncher(tier: com.uruj.domain.AutonomicTier): Color = when (tier) {
    com.uruj.domain.AutonomicTier.ELITE -> UrujZone1
    com.uruj.domain.AutonomicTier.HEALTHY -> UrujZone2
    com.uruj.domain.AutonomicTier.MODERATE_STRAIN -> UrujZone3
    com.uruj.domain.AutonomicTier.SIGNIFICANT_STRAIN -> UrujZone4
    com.uruj.domain.AutonomicTier.SEVERE_STRAIN -> UrujZone5
}

/**
 * Plain-English nuance line tailored to the actual numbers. Surfaces edge
 * cases (high ratio at low absolute RMSSD = possible noise floor) that the
 * tier-label-only view would hide.
 */
private fun interpretationHint(
    r: com.uruj.domain.OrthostaticTestResult,
    i: com.uruj.domain.OrthostaticInterpretation?,
): String {
    if (i == null) return ""
    // Flag when both RMSSD values are very low — ratio comparison gets noisy
    // at the strap's effective resolution floor.
    val bothLow = r.seatedRmssdMs < 20f && r.standingRmssdMs < 20f
    val tierLine = when (i.overallTier) {
        com.uruj.domain.AutonomicTier.ELITE ->
            "Body absorbed the postural challenge cleanly. Minimal HR climb + preserved vagal tone — elite autonomic flexibility."
        com.uruj.domain.AutonomicTier.HEALTHY ->
            "Acute autonomic reflex is intact. HR climbed appropriately on standing without over-reacting."
        com.uruj.domain.AutonomicTier.MODERATE_STRAIN ->
            "Body is straining to absorb the postural change. Consistent with recent training load — rest is the play."
        com.uruj.domain.AutonomicTier.SIGNIFICANT_STRAIN ->
            "Significant autonomic strain. HR over-reacts and/or RMSSD collapses on standing — body is in deep recovery debt."
        com.uruj.domain.AutonomicTier.SEVERE_STRAIN ->
            "Severe autonomic strain — over-reaching territory. Skip training today, prioritize sleep + nutrition."
    }
    val noiseNote = if (bothLow) " (Both RMSSD values are low — ratio comparison is near the strap's resolution floor; trust the HR-delta tier more than the ratio.)" else ""
    return tierLine + noiseNote
}

@Composable
private fun OrthostaticInfoDialog(
    latest: com.uruj.domain.OrthostaticTestResult?,
    interpretation: com.uruj.domain.OrthostaticInterpretation?,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("CLOSE", color = UrujAccent, fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Text("Orthostatic test", color = UrujText, fontWeight = FontWeight.Black, fontSize = 18.sp)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InfoBlock(
                    "What it is",
                    "A 4-minute sit→stand protocol measuring how your autonomic " +
                        "nervous system responds to a sharp posture change. Standing " +
                        "requires parasympathetic tone to drop and sympathetic tone " +
                        "to rise — your HR climbs, your HRV falls. The SIZE of those " +
                        "shifts tells you how fatigued / recovered you actually are.",
                )
                InfoBlock(
                    "Why cyclists care",
                    "Overnight HRV is a CHRONIC signal — slow to respond, weeks-long " +
                        "trend. The orthostatic test is an ACUTE signal — same-day " +
                        "snapshot of autonomic strain. Together they bracket your " +
                        "state: a healthy orthostatic with suppressed overnight HRV " +
                        "= classic early over-reach (acute reflex intact, baseline " +
                        "depleted). Both impaired = deep recovery debt.",
                )
                InfoBlock(
                    "How URUJ computes it",
                    "Strap stays on through the 4-min ritual. 24/7 BiometricService " +
                        "writes RR intervals to NDJSON throughout. After capture we " +
                        "slice the file by phase timestamps and compute:\n\n" +
                        "• Mean HR seated + standing\n" +
                        "• RMSSD seated + standing (Kubios-style ectopic filter, " +
                        "physiological 300-2000 ms range, NO timestamp filter — " +
                        "continuous strap-on capture is consecutive by construction)\n" +
                        "• HR delta = standing − seated\n" +
                        "• RMSSD ratio = standing / seated",
                )
                InfoBlock(
                    "Reference ranges",
                    "HR delta tiers (Klivington 1995, Plews et al.):\n" +
                        "  <10 bpm   Elite autonomic flexibility\n" +
                        "  10-15 bpm Healthy response\n" +
                        "  15-25 bpm Moderate strain\n" +
                        "  25-35 bpm Significant strain\n" +
                        "  35+ bpm   Severe — overreaching\n\n" +
                        "RMSSD ratio tiers:\n" +
                        "  ≥0.4 Healthy\n" +
                        "  0.3-0.4 Moderate suppression on stand\n" +
                        "  0.2-0.3 Significant\n" +
                        "  <0.2 Severe parasympathetic suppression",
                )
                InfoBlock(
                    "Honest caveats",
                    "• One reading is a snapshot. The TREND across days matters " +
                        "more than any single value.\n" +
                        "• RMSSD ratio above 0.85 can mean either elite vagal " +
                        "preservation OR low-signal noise at the strap's resolution " +
                        "floor. When both RMSSD values are <20 ms, trust HR delta " +
                        "more than ratio.\n" +
                        "• Same-time-of-day testing matters — diurnal autonomic tone " +
                        "varies. Pick a consistent slot (e.g. morning post-pee, " +
                        "before caffeine).\n" +
                        "• Caffeine, anxiety, recent movement, dehydration all skew " +
                        "the test. Sit 5+ min before STARTing.",
                )
                if (latest != null && interpretation != null) {
                    InfoBlock(
                        "For YOU right now",
                        "Reading: ${"%.0f".format(latest.hrDeltaBpm)} bpm HR delta · " +
                            "%.2f RMSSD ratio\n".format(latest.rmssdRatio) +
                            "Tier: ${interpretation.summary}\n" +
                            "Seated RMSSD: ${"%.1f".format(latest.seatedRmssdMs)} ms · " +
                            "Standing: ${"%.1f".format(latest.standingRmssdMs)} ms",
                    )
                }
            }
        },
        containerColor = UrujSurface,
    )
}

@Composable
private fun InfoBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title.uppercase(),
            color = UrujAccent,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp,
            letterSpacing = 1.2.sp,
        )
        Text(body, color = UrujText, fontSize = 12.sp)
    }
}
