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
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
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
                item("hrr1") { HrRecoveryCard(s) }
            }

            item("cardio_header") { SectionHeader("Cardiovascular") }
            item("hr_card") { HeartRateCard(s) }
            if (s.karvonenZones != null) {
                item("zones_card") { KarvonenZonesCard(s.karvonenZones) }
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
private fun HrRecoveryCard(s: BioLabSnapshot) {
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
                val shownCount = s.hrr1RecentSamples.size
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
                s.hrr1RecentSamples.forEach { sample ->
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
