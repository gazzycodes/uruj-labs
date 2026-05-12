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
import com.uruj.power.KarvonenZonesCalculator
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
                    "Estimates (cross-validated):",
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
                }
            }
        }
    }
}

@Composable
private fun CardiovascularAgeCard(s: BioLabSnapshot) {
    val bio = s.biologicalAge
    val delta = s.biologicalAgeDelta
    BioCard("Cardiovascular Biological Age", accentColor = UrujNeonMagenta) {
        if (bio == null) {
            Text(
                "Need RHR + VO₂ max to compute biological age.",
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
    }
}

@Composable
private fun HeartRateCard(s: BioLabSnapshot) {
    BioCard("Heart Rate") {
        MetricRow(
            "RESTING HR",
            value = s.restingHrBpm?.let { "$it bpm" } ?: "—",
            subtitle = if (s.restingHrBpm != null) "proxy from ${s.hrRecords7d} HR samples" else null,
        )
        MetricRow(
            "MAX HR",
            value = "${s.maxHrBpm} bpm",
            subtitle = if (s.maxHrAutoDetected) "auto-detected from your rides"
            else "default 220 − age; will refine as you train",
        )
        if (s.restingHrBpm != null) {
            MetricRow("HR RESERVE", value = "${s.maxHrBpm - s.restingHrBpm} bpm")
        }
        MetricRow(
            "HRV PROXY",
            value = s.hrvProxyMs?.let { "%.1f".format(it) } ?: "—",
            subtitle = "std dev of resting-HR samples",
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
