package com.uruj.ui.biolab

import android.content.Intent
import android.net.Uri
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
    viewModel: BioLabViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

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
                    TextButton(onClick = { viewModel.refresh() }, enabled = !isLoading) {
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
                item("loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = UrujAccent)
                    }
                }
                return@LazyColumn
            }

            // Cycling-training metrics — what URUJ uniquely computes
            item("vo2") { Vo2MaxCard(s) }
            if (s.hrr1Median != null) {
                item("hrr1") { HrRecoveryCard(s, onSeeTrend = onOpenHrr1Trend) }
            }

            item("cardio_header") { SectionHeader("Cardiovascular") }
            item("hr_card") { HeartRateCard(s) }
            if (s.karvonenZones != null) {
                item("zones_card") { KarvonenZonesCard(s.karvonenZones) }
            }

            // v0.7.0 — Autonomic Health section. Only shows when 24/7 monitoring
            // has captured RR data. RMSSD / SDNN / pNN50 from BLE chest strap.
            if (s.autonomicRmssdMs != null) {
                item("autonomic_header") { SectionHeader("Autonomic Health") }
                item("autonomic_card") { AutonomicHealthCard(s, onSeeTrend = onOpenHrvTrend) }
            }

            // v0.7.2 — CAR (Cortisol Awakening Response). Auto-resolved from
            // 24/7 NDJSON + last SleepSessionRecord. Card hides until ~45 min
            // post-wake when the window is complete.
            if (s.carResult != null && s.carInterpretation != null) {
                item("car_card") {
                    CarCard(s.carResult, s.carInterpretation, onSeeTrend = onOpenCarTrend)
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
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UrujSurface, RoundedCornerShape(16.dp))
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            title.uppercase(),
            color = accentColor,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
        )
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

@Composable
private fun Vo2MaxCard(s: BioLabSnapshot) {
    BioCard("VO₂ Max — aerobic capacity", accentColor = UrujZone2) {
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
    }
}

@Composable
private fun HrRecoveryCard(s: BioLabSnapshot, onSeeTrend: () -> Unit = {}) {
    val drop = s.hrr1Median ?: return
    // Color the hero number by the cardiology-grade band.
    val accent = when {
        drop >= 18 -> UrujZone2  // green — excellent
        drop >= 12 -> UrujZone3  // amber — average
        else -> UrujZone5        // red — poor / elevated CV risk
    }
    BioCard("HR Recovery (HRR1) — autonomic health", accentColor = accent) {
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
}

@Composable
private fun HeartRateCard(s: BioLabSnapshot) {
    // v0.4.0 slim — 4 cycling-training-relevant rows only.
    // Cut: today min/max (Samsung mirror), Samsung Direct RHR (same), HRV proxy
    // (misleading fake number). See [[reference_cut_features_v0_4]].
    BioCard("Heart Rate") {
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
    }
}

@Composable
private fun KarvonenZonesCard(zones: KarvonenZonesCalculator.Result) {
    BioCard("Karvonen HR Zones — personalized") {
        Text(
            "Computed from YOUR heart rate reserve (${zones.hrReserve} bpm). More accurate than %-of-max because it accounts for your specific resting HR.",
            color = UrujMuted, fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))
        zones.zones.forEach { zone ->
            ZoneRow(zone)
            Spacer(Modifier.height(4.dp))
        }
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
    BioCard("Autonomic HRV — beat-to-beat parasympathetic", accentColor = accent) {
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
 * v0.7.2 — Cortisol Awakening Response card. Surfaces the morning HPA-axis
 * activation pattern automatically from 24/7 NDJSON. Tier-coded with
 * Bio Lab's standard color language (green = healthy, red = blunted).
 *
 * Lives under "Autonomic Health" since it's another autonomic signal but
 * gets its own card because the methodology is different (single-event
 * detector, not windowed average).
 */
@Composable
private fun CarCard(
    car: com.uruj.domain.CarResult,
    interp: com.uruj.domain.CarInterpretation,
    onSeeTrend: () -> Unit = {},
) {
    var showInfo by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val accent = carTierColor(interp.overallTier)
    BioCard("CAR — cortisol awakening response", accentColor = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(
                onClick = { showInfo = true },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
            ) {
                Text("ⓘ", color = UrujMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(interp.summary, color = accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "+${"%.0f".format(car.amplitudeBpm)}",
                color = UrujText,
                fontWeight = FontWeight.Black,
                fontSize = 44.sp,
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.padding(bottom = 10.dp)) {
                Text("bpm amplitude", color = UrujMuted, fontSize = 10.sp)
                Text(
                    "peak ${"%.0f".format(car.latencyMinutes)} min after wake",
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
            Text(
                "Peak post-wake: ${"%.0f".format(car.peakHrBpm)} bpm",
                color = UrujText, fontSize = 11.sp,
            )
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
            "CAR = HR + HRV inflection in first 30-45 min after waking, proxy " +
                "for cortisol surge (Pruessner 1997, Clow 2010, Stalder 2016). " +
                "Healthy adult range: 10-20 bpm rise, peak 20-40 min post-wake. " +
                "Blunted CAR (<5 bpm) is a published marker of chronic stress, " +
                "burnout, overtraining, depression. Robust CAR (20+ bpm) = " +
                "strong HPA-axis activation. Computed from BLE chest strap RR + " +
                "HC sleep window — NOT salivary cortisol (the lab-gold standard).",
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

@Composable
private fun CarInfoDialog(
    car: com.uruj.domain.CarResult,
    interpretation: com.uruj.domain.CarInterpretation,
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
            Text("CAR — Cortisol Awakening Response", color = UrujText,
                fontWeight = FontWeight.Black, fontSize = 18.sp)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InfoBlock(
                    "What it is",
                    "Cortisol surges 50-160% in the first 30-45 min after waking — " +
                        "the HPA-axis (hypothalamus-pituitary-adrenal) firing up the " +
                        "body for the day. URUJ proxies this surge via HR + HRV " +
                        "inflection because we can't measure salivary cortisol " +
                        "non-invasively. HR climbs, HRV drops, both driven by the " +
                        "same sympathetic activation that's pumping the cortisol.",
                )
                InfoBlock(
                    "Why athletes care",
                    "Blunted CAR is a published marker of:\n" +
                        "• Chronic stress / burnout\n" +
                        "• Overtraining syndrome\n" +
                        "• Depression / mood disorders\n" +
                        "• HPA-axis dysfunction\n\n" +
                        "Robust CAR = healthy stress-response system. The body's " +
                        "ability to ACTIVATE in the morning is a separate signal " +
                        "from its ability to RECOVER overnight. Both matter.",
                )
                InfoBlock(
                    "How URUJ computes it",
                    "Automatic — no ritual needed. Each morning after Samsung " +
                        "writes the SleepSessionRecord and 24/7 NDJSON has 45 min " +
                        "of post-wake data:\n\n" +
                        "1. Baseline HR + RMSSD over the last 10 min of sleep\n" +
                        "2. Peak HR detected in 0-45 min post-wake window\n" +
                        "3. Amplitude = peak − baseline (the morning surge size)\n" +
                        "4. Latency = minutes from wake to peak\n" +
                        "5. RMSSD trajectory binned to 5-min, find the trough\n" +
                        "6. Cached per-day so opening Bio Lab is instant",
                )
                InfoBlock(
                    "Reference ranges",
                    "Amplitude tiers (HR rise above sleep baseline):\n" +
                        "  <5 bpm    Blunted — chronic stress marker\n" +
                        "  5-10 bpm  Suppressed — HPA dampened\n" +
                        "  10-20 bpm Healthy ✓ normal range\n" +
                        "  20-30 bpm Robust ✓ strong activation\n" +
                        "  >30 bpm   Exaggerated — acute stress/anxiety\n\n" +
                        "Latency tiers (time to peak):\n" +
                        "  <10 min  Very fast — possible acute stress\n" +
                        "  10-20 min Fast — robust activation\n" +
                        "  20-40 min Typical — healthy range\n" +
                        "  40-60 min Slow — HPA dampened\n" +
                        "  >60 min  Very slow — blunted",
                )
                InfoBlock(
                    "Honest caveats",
                    "• URUJ measures the HR/HRV signature of the cortisol surge, " +
                        "not cortisol itself. Strong correlation in research but " +
                        "salivary measurement is the lab-gold standard.\n" +
                        "• Wake events that follow a poor sleep can suppress CAR " +
                        "amplitude — interpret in context with sleep quality.\n" +
                        "• Coffee + stress in the first 30 min skew the peak " +
                        "(more sympathetic activation = bigger spike). For a " +
                        "clean reading, no caffeine before the 45-min mark.\n" +
                        "• Naps don't produce CAR (different neuroendocrine pattern).\n" +
                        "• Trends > single readings. Track week-over-week.",
                )
                InfoBlock(
                    "For YOU right now",
                    "Amplitude: ${"%.0f".format(car.amplitudeBpm)} bpm " +
                        "(${interpretation.amplitudeTier.name.lowercase()})\n" +
                        "Latency: ${"%.0f".format(car.latencyMinutes)} min " +
                        "(${interpretation.latencyTier.name.lowercase()})\n" +
                        "RMSSD trough: ${"%.1f".format(car.troughRmssdMs)} ms " +
                        "(${"%.0f".format(car.rmssdDropPercent)}% drop from baseline)\n" +
                        "Overall: ${interpretation.summary}",
                )
            }
        },
        containerColor = UrujSurface,
    )
}

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
    BioCard("Orthostatic test — sit→stand autonomic snapshot", accentColor = tierAccent) {
        // Title row with info icon
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f))
            androidx.compose.material3.TextButton(
                onClick = { showInfo = true },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
            ) {
                Text(
                    "ⓘ",
                    color = UrujMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
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
