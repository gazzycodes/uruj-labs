package com.uruj.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uruj.data.WearTimeRepository
import com.uruj.data.WearTimeSnapshot
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
import kotlinx.coroutines.delay

/**
 * v0.7.8 — sensor wear-time card. Shows TODAY's coverage for BOTH the
 * BLE chest strap and the Samsung Fit Band 3, plus the combined (either-
 * sensor) coverage which is the redundancy metric.
 *
 * Why both: user runs both devices (strap + band). When one is off (shower,
 * charging), the other still captures. Combined coverage = lab-grade
 * continuity. This card makes that redundancy visible so the rider knows
 * exactly what was captured and where the blind windows are.
 *
 * Refreshes every 30s while the screen is foregrounded.
 */
@Composable
fun WearTimeCard() {
    val context = LocalContext.current
    val repo = remember { WearTimeRepository(context) }
    var snapshot by remember { mutableStateOf<WearTimeSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) {
            loading = snapshot == null
            snapshot = repo.today()
            loading = false
            delay(30_000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UrujSurface, RoundedCornerShape(16.dp))
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SENSOR WEAR — TODAY",
                color = UrujAccent,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Local midnight → now. Higher % = more continuous data on disk. " +
                "Combined = redundancy (either sensor counted). One device off doesn't " +
                "mean a blind window if the other was capturing.",
            color = UrujMuted, fontSize = 11.sp,
        )
        Spacer(Modifier.height(12.dp))

        val s = snapshot
        when {
            loading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = UrujAccent) }
            }
            s == null -> {
                Text(
                    "No sensor data yet today. Wear the strap (24/7 monitoring ON " +
                        "above) or the Samsung band, OR both. Coverage shows up here as " +
                        "soon as samples land.",
                    color = UrujMuted, fontSize = 12.sp,
                )
            }
            else -> {
                WearRow(
                    label = "CHEST STRAP",
                    wornMinutes = s.result.strapWornMinutes,
                    percent = s.result.strapPercent(),
                    longestGapMin = s.result.longestStrapGapMinutes,
                    color = pickPercentColor(s.result.strapPercent()),
                    available = s.strapEverPaired,
                    unavailableMsg = "No strap paired — pair via SCAN & PAIR below",
                )
                Spacer(Modifier.height(8.dp))
                WearRow(
                    label = "SAMSUNG BAND",
                    wornMinutes = s.result.bandWornMinutes,
                    percent = s.result.bandPercent(),
                    longestGapMin = s.result.longestBandGapMinutes,
                    color = pickPercentColor(s.result.bandPercent()),
                    available = s.bandAvailable,
                    unavailableMsg = "No band HR data — check Samsung Health sync + HC permission",
                )
                Spacer(Modifier.height(8.dp))
                WearRow(
                    label = "COMBINED ✓",
                    wornMinutes = s.result.combinedWornMinutes,
                    percent = s.result.combinedPercent(),
                    longestGapMin = s.result.longestCombinedGapMinutes,
                    color = pickPercentColor(s.result.combinedPercent()),
                    available = true,
                    unavailableMsg = "",
                    isCombined = true,
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    interpretation(s),
                    color = UrujText.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Methodology: each minute is 'worn' if ≥1 sample landed in it. Strap " +
                        "= 24/7 BLE NDJSON. Band = HC HeartRateRecord (HC writes 15-30 min " +
                        "after Samsung Health syncs, so band % may lag the strap %).",
                    color = UrujMuted, fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun WearRow(
    label: String,
    wornMinutes: Int,
    percent: Float,
    longestGapMin: Int,
    color: Color,
    available: Boolean,
    unavailableMsg: String,
    isCombined: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = if (isCombined) UrujAccent else UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
                modifier = Modifier.width(132.dp),
            )
            if (!available) {
                Text(unavailableMsg, color = UrujMuted, fontSize = 10.sp)
                return@Row
            }
            Text(
                "${formatHm(wornMinutes)} (${"%.0f".format(percent)}%)",
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            if (longestGapMin > 0) {
                Text(
                    "gap ${formatHm(longestGapMin)}",
                    color = UrujMuted,
                    fontSize = 10.sp,
                )
            }
        }
        if (available) {
            // Progress bar
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(UrujSurfaceHigh, RoundedCornerShape(2.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = (percent / 100f).coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(color, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

private fun pickPercentColor(pct: Float): Color = when {
    pct >= 95f -> UrujZone1     // near-perfect — bright cyan/blue
    pct >= 80f -> UrujZone2     // good — green
    pct >= 60f -> UrujZone3     // decent — amber
    pct >= 40f -> UrujZone4     // weak — orange
    else -> UrujZone5            // mostly missing — red
}

/** Format minutes as "Xh Ym" (or "Ym" when <1h). */
private fun formatHm(minutes: Int): String {
    if (minutes < 60) return "${minutes}m"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}h" else "${h}h ${m}m"
}

private fun interpretation(s: WearTimeSnapshot): String {
    val combinedPct = s.result.combinedPercent()
    val gapMin = s.result.longestCombinedGapMinutes
    return when {
        combinedPct >= 95f && gapMin <= 20 ->
            "Near-perfect coverage today. Both sensors mostly captured continuously."
        combinedPct >= 95f && gapMin > 20 ->
            "Excellent overall coverage. One ${formatHm(gapMin)} blind window — probably " +
                "shower or charging cycle when both came off."
        combinedPct >= 80f ->
            "Strong coverage. ${formatHm(gapMin)} longest blind window. Tomorrow try " +
                "to keep at least one device on during routine breaks (shower / charge)."
        combinedPct >= 60f ->
            "Moderate coverage — ${formatHm(gapMin)} blind window today. Consider staggered " +
                "charging (strap charges while band stays on, then swap)."
        else ->
            "Low coverage today. ${formatHm(gapMin)} longest blind window. Wear at least " +
                "one device continuously to feed the 24/7 metrics."
    }
}
