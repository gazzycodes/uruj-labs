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
fun WearTimeCard(refreshTrigger: Long = 0L) {
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
    // v0.7.10 — re-fetch on global refresh button taps
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0L) {
            snapshot = repo.today()
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
            "Strap is the realtime sensor (per-beat NDJSON) so it gets a wear % + " +
                "gap line. Band is batched — Samsung writes HR every 10-15 min at " +
                "idle and HC sync lags 6-24h on quiet days — so a wear % would be " +
                "misleading; it's shown as a sync indicator only. Band still fills " +
                "HRR1 + RHR via the source-labeled fallback path.",
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
                val nowMs = System.currentTimeMillis()
                // v0.7.10 — strap is the only metric we display as wear-time %
                // (per-second cadence is accurate at minute resolution). Band
                // wear-time math was misleading at idle (14h+ batch lag is
                // normal) → demoted to an informational sync line below.
                WearRow(
                    label = "CHEST STRAP",
                    wornMinutes = s.result.strapWornMinutes,
                    percent = s.result.strapPercent(),
                    longestGapMin = s.result.longestStrapGapMinutes,
                    color = pickPercentColor(s.result.strapPercent()),
                    available = s.strapEverPaired,
                    unavailableMsg = "No strap paired — pair via SCAN & PAIR below",
                    lastSyncedAgoText = s.mostRecentStrapSampleMs?.let {
                        "strap streamed " + formatAgo(nowMs - it) + " ago"
                    } ?: "no strap data yet",
                )
                Spacer(Modifier.height(10.dp))
                // v0.7.10 — band info-only row (no percentage, no progress bar)
                BandStatusRow(snapshot = s, nowMs = nowMs)

                Spacer(Modifier.height(10.dp))
                Text(
                    strapInterpretation(s),
                    color = UrujText.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Methodology: strap uses 1-min buckets (per-second NDJSON cadence " +
                        "supports minute resolution). Band is intentionally NOT shown as a " +
                        "wear-time %: Samsung writes idle HR samples at ~10-15 min cadence " +
                        "and HC sync lags 6-24h on quiet days, so any % would be misleading. " +
                        "Band's role here is backup verification — when strap is off " +
                        "(charging / shower / app crash), the band's batched HR fills in " +
                        "HRR1 + RHR via the source-labeled path. See HR Recovery + Athletic " +
                        "RHR cards for which sensor produced each reading.",
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
    lastSyncedAgoText: String,
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
                Column {
                    Text(unavailableMsg, color = UrujMuted, fontSize = 10.sp)
                    Text(
                        lastSyncedAgoText,
                        color = UrujMuted.copy(alpha = 0.7f),
                        fontSize = 9.sp,
                    )
                }
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
            // v0.7.9 — last-sync indicator
            Spacer(Modifier.height(2.dp))
            Text(
                lastSyncedAgoText,
                color = UrujMuted.copy(alpha = 0.75f),
                fontSize = 9.sp,
            )
        }
    }
}

/** "5m" / "1h 12m" / "3h" / "2d" — formats a duration in ms as human text. */
private fun formatAgo(ms: Long): String {
    val absMs = if (ms < 0) 0L else ms
    val totalMin = absMs / 60_000L
    return when {
        totalMin < 1L -> "<1m"
        totalMin < 60L -> "${totalMin}m"
        totalMin < 60L * 24L -> {
            val h = totalMin / 60L
            val m = totalMin % 60L
            if (m == 0L) "${h}h" else "${h}h ${m}m"
        }
        else -> "${totalMin / (60L * 24L)}d"
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

/** v0.7.10 — band as info-only row. No percentage, no progress bar — just
 *  current sync state + count of today's batched samples. Honest because
 *  band's idle-cadence batch behavior makes any % misleading. */
@Composable
private fun BandStatusRow(snapshot: WearTimeSnapshot, nowMs: Long) {
    val s = snapshot
    val lastSyncMs = s.mostRecentBandSampleMs
    val lastSyncText = if (lastSyncMs != null) {
        "Last band HR sample synced ${formatAgo(nowMs - lastSyncMs)} ago"
    } else if (s.bandAvailable) {
        "Band paired — no HR samples in HC yet today (batch sync pending)"
    } else {
        "No band HR data — check Samsung Health sync + HC permission"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "SAMSUNG BAND",
            color = UrujMuted,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
            modifier = Modifier.width(132.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Backup / verification",
                color = UrujText.copy(alpha = 0.9f),
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
            )
            Text(
                lastSyncText,
                color = UrujMuted,
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * v0.7.10 — interpretation focused on the strap (band wear-time math
 * removed). Strap is the realtime primary, so its % is what matters. Band
 * backup is mentioned generally without a misleading number.
 */
private fun strapInterpretation(s: WearTimeSnapshot): String {
    val pct = s.result.strapPercent()
    val gapMin = s.result.longestStrapGapMinutes
    return when {
        pct >= 95f && gapMin <= 20 ->
            "Near-perfect strap coverage today. Realtime data is continuous; " +
                "band stands by as batch backup."
        pct >= 95f && gapMin > 20 ->
            "Excellent strap coverage with one ${formatHm(gapMin)} gap — probably " +
                "a shower or charging window. Band may have filled in HRR1 / RHR " +
                "during that gap (see source labels on those cards)."
        pct >= 80f ->
            "Strong strap coverage. Longest gap ${formatHm(gapMin)}. Band backup " +
                "fills HRR1 / RHR readings if any rides or sleep fell in that window."
        pct >= 60f ->
            "Moderate strap coverage. Longest gap ${formatHm(gapMin)}. Stagger " +
                "charging cycles (charge strap while wearing band, or vice versa) " +
                "to keep at least one sensor on at all times."
        else ->
            "Low strap coverage today. Longest gap ${formatHm(gapMin)}. Wear the " +
                "strap continuously to feed the 24/7 autonomic metrics (HRV / CAR / " +
                "orthostatic). Band fills in HRR1 + RHR but not the rest."
    }
}
