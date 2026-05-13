package com.uruj.ui.biolab

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
import com.uruj.ui.theme.UrujNeonMagenta
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
                    "Your Lab",
                    color = UrujText,
                    fontWeight = FontWeight.Black,
                    fontSize = 32.sp,
                )
                Text(
                    "Every biomarker we can compute from what you've shared. Updated on each refresh.",
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

            // Hero cards: the biohacker flex numbers
            item("vo2") { Vo2MaxCard(s) }
            item("cv_age") { CardiovascularAgeCard(s) }
            if (s.hrr1Median != null) {
                item("hrr1") { HrRecoveryCard(s) }
            }

            item("cardio_header") { SectionHeader("Cardiovascular") }
            item("hr_card") { HeartRateCard(s) }
            if (s.karvonenZones != null) {
                item("zones_card") { KarvonenZonesCard(s.karvonenZones) }
            }

            item("recovery_header") { SectionHeader("Recovery") }
            item("recovery_card") { RecoveryCard(s) }

            item("body_header") { SectionHeader("Body Composition") }
            item("body_card") { BodyCompositionCard(s) }

            item("activity_header") { SectionHeader("Activity Today") }
            item("activity_card") { ActivityCard(s) }
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
        if (s.vo2MaxConsensus == null) {
            Text(
                "Need HR data + a few rides to estimate. Wear band more, ride more.",
                color = UrujMuted,
                fontSize = 12.sp,
            )
            return@BioCard
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "%.1f".format(s.vo2MaxConsensus),
                color = UrujZone2,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                fontSize = 56.sp,
                letterSpacing = (-2).sp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "mL/kg/min",
                color = UrujMuted,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        Text(
            s.vo2MaxClassification,
            color = UrujText,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(UrujSurfaceHigh.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (s.vo2MaxFromSamsung) {
                Text(
                    "Source: Samsung Health (band-measured)",
                    color = UrujMuted, fontSize = 10.sp,
                )
            } else {
                Text(
                    if (s.ftpIsLikelyUntested) "Estimate (HR-only — FTP untested):"
                    else "Estimates (cross-validated):",
                    color = UrujMuted,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(2.dp))
                if (s.vo2MaxHrBased != null) {
                    Text(
                        "Uth–Sørensen (HR-based): ${"%.1f".format(s.vo2MaxHrBased)} mL/kg/min",
                        color = UrujText, fontSize = 11.sp,
                    )
                }
                if (s.vo2MaxPowerBased != null) {
                    Text(
                        "Power-based (FTP/weight): ${"%.1f".format(s.vo2MaxPowerBased)} mL/kg/min",
                        color = UrujText, fontSize = 11.sp,
                    )
                } else if (s.ftpIsLikelyUntested) {
                    Text(
                        "Power-based: omitted — FTP at default 200W placeholder. " +
                            "Do a 20-min all-out test, set FTP in Profile, unlock cross-validation.",
                        color = UrujMuted,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun CardiovascularAgeCard(s: BioLabSnapshot) {
    val bio = s.biologicalAge
    val delta = s.biologicalAgeDelta
    BioCard("Fitness Age Estimate", accentColor = UrujNeonMagenta) {
        if (bio == null) {
            Text(
                "Need RHR + VO₂ max to compute fitness age.",
                color = UrujMuted, fontSize = 12.sp,
            )
            return@BioCard
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                bio.toString(),
                color = UrujNeonMagenta,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                fontSize = 64.sp,
                letterSpacing = (-2).sp,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                Text(
                    "vs your ${s.chronologicalAge}",
                    color = UrujMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                )
                if (delta != null) {
                    val sign = if (delta >= 0) "+" else ""
                    val color = when {
                        delta >= 5 -> UrujZone2
                        delta >= 0 -> UrujZone3
                        else -> UrujZone5
                    }
                    Text(
                        "$sign$delta years " + if (delta >= 0) "younger" else "older",
                        color = color,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        Text(
            s.biologicalAgeVerdict,
            color = UrujText, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Estimate from RHR + VO₂ max fitness markers. NOT a cardiovascular risk metric — real CV age needs blood pressure, lipids, family history.",
            color = UrujMuted, fontSize = 10.sp,
        )
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
    BioCard("Heart Rate") {
        MetricRow(
            "RESTING HR",
            value = s.restingHrBpm?.let { "$it bpm" } ?: "—",
            subtitle = s.restingHrSourceLabel.takeIf { it.isNotBlank() && s.restingHrBpm != null },
        )
        if (s.lowestHrToday != null) {
            MetricRow(
                "MIN TODAY",
                value = "${s.lowestHrToday} bpm",
                subtitle = "lowest sample recorded today (matches Samsung Health)",
            )
        }
        if (s.samsungDirectRhrBpm != null) {
            MetricRow(
                "SAMSUNG RHR",
                value = "${s.samsungDirectRhrBpm} bpm",
                subtitle = "Samsung Health's own daily RHR (different definition — includes daytime rest)",
            )
        }
        MetricRow(
            "MAX HR (effective)",
            value = "${s.maxHrBpm} bpm",
            subtitle = if (s.maxHrAutoDetected) "auto-detected from your rides — high confidence"
            else "220−age estimate, ±10-12 bpm. Hit ≥${s.maxHrBpm + 1} bpm in a ride to auto-bump.",
        )
        if (s.highestHrToday != null) {
            MetricRow(
                "MAX HR TODAY",
                value = "${s.highestHrToday} bpm",
                subtitle = "highest sample observed today (matches Samsung's max)",
            )
        }
        if (s.highestHr30d != null) {
            MetricRow(
                "MAX HR 30d",
                value = "${s.highestHr30d} bpm",
                subtitle = "your hardest observed effort in 30 days — true max likely higher",
            )
        }
        if (s.restingHrBpm != null) {
            MetricRow("HR RESERVE", value = "${s.maxHrBpm - s.restingHrBpm} bpm")
        }
        MetricRow(
            "HR VARIABILITY (proxy)",
            value = s.hrvProxyMs?.let { "%.1f".format(it) } ?: "—",
            subtitle = "std dev of HR samples — NOT true HRV. Real HRV needs RR-intervals from a chest strap (v1.5).",
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
private fun RecoveryCard(s: BioLabSnapshot) {
    BioCard("Recovery") {
        MetricRow(
            "SLEEP LAST NIGHT",
            value = s.sleepLastNightHours?.let { "%.1fh".format(it) } ?: "—",
            subtitle = s.sleepLastNightHours?.let { if (it >= 7f) "in optimal range" else "below 7h target" },
        )
        MetricRow(
            "SpO₂",
            value = s.spo2Percent?.let { "${it.toInt()}%" } ?: "—",
            subtitle = "overnight pulse-oximetry",
        )
    }
}

@Composable
private fun BodyCompositionCard(s: BioLabSnapshot) {
    BioCard("Body Composition") {
        MetricRow(
            "WEIGHT",
            value = "%.1f kg".format(s.bodyWeightKg),
            subtitle = "logged in Samsung Health",
        )
        if (s.bmi != null) {
            val bmiCategory = when {
                s.bmi < 18.5f -> "underweight"
                s.bmi < 25f -> "normal range"
                s.bmi < 30f -> "overweight"
                else -> "obese"
            }
            MetricRow("BMI", value = "%.1f".format(s.bmi), subtitle = bmiCategory)
        }
        MetricRow("HEIGHT", value = "${s.heightCm} cm")
    }
}

@Composable
private fun ActivityCard(s: BioLabSnapshot) {
    BioCard("Activity Today") {
        if (s.stepsToday != null) {
            MetricRow("STEPS", value = "%,d".format(s.stepsToday))
        }
        if (s.distanceTodayMeters != null) {
            MetricRow("DISTANCE", value = "%.2f km".format(s.distanceTodayMeters / 1000f))
        }
        if (s.totalCaloriesToday != null) {
            MetricRow("TOTAL CALORIES", value = "${s.totalCaloriesToday.toInt()} kcal")
        }
        if (s.activeCaloriesToday != null) {
            MetricRow("ACTIVE CALORIES", value = "${s.activeCaloriesToday.toInt()} kcal")
        }
        if (s.exerciseSessionsToday != null) {
            MetricRow("EXERCISE SESSIONS", value = s.exerciseSessionsToday.toString())
        }
    }
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
