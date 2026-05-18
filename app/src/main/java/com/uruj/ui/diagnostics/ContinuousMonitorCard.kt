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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uruj.data.BiometricSettingsStore
import com.uruj.data.ContinuousBiometricRecorder
import com.uruj.service.BiometricService
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * v0.6.0 — 24/7 continuous monitoring toggle + status. Lives at the top of
 * the Diagnostics screen.
 *
 * Behavior:
 *   - Toggle reads/writes BiometricSettings.enabled in DataStore
 *   - Flipping ON → starts BiometricService (foreground, persistent BLE)
 *   - Flipping OFF → stops the service, NDJSON writer closes cleanly
 *   - Shows sample count in today's file + how recent the last sample was,
 *     so the rider can confirm capture is alive
 *
 * Battery cost honesty: warns "uses additional battery — toggle off when
 * not wearing strap" so the rider isn't surprised by an extra ~5-10%/day
 * battery drain.
 */
@Composable
fun ContinuousMonitorCard(refreshTrigger: Long = 0L) {
    val context = LocalContext.current
    val settingsStore = remember { BiometricSettingsStore(context) }
    val recorder = remember { ContinuousBiometricRecorder(context) }
    val coScope = rememberCoroutineScope()

    val enabled by settingsStore.settings
        .map { it.enabled }
        .collectAsState(initial = false)

    // Live-ticking sample count so rider can see capture is alive overnight.
    var samplesToday by remember { mutableStateOf(0L) }
    LaunchedEffect(enabled) {
        // Refresh every 10s while monitoring is on
        while (enabled) {
            samplesToday = runCatching { recorder.samplesTodayCount() }.getOrDefault(0L)
            delay(10_000L)
        }
        samplesToday = runCatching { recorder.samplesTodayCount() }.getOrDefault(0L)
    }
    // v0.7.10 — also re-fetch on global refresh button taps
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0L) {
            samplesToday = runCatching { recorder.samplesTodayCount() }.getOrDefault(0L)
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
                "24/7 MONITORING",
                color = UrujAccent,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.weight(1f))
            StatusDot(enabled)
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (enabled) "ON" else "OFF",
                color = if (enabled) UrujZone2 else UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { newValue ->
                    coScope.launch {
                        settingsStore.setEnabled(newValue)
                        if (newValue) {
                            BiometricService.start(context)
                        } else {
                            BiometricService.stop(context)
                        }
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = UrujAccent,
                    checkedTrackColor = UrujZone2,
                    uncheckedThumbColor = UrujMuted,
                    uncheckedTrackColor = UrujSurfaceHigh,
                ),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (enabled) {
                "BLE chest strap stays connected 24/7. Every beat + RR interval is captured " +
                    "to a daily NDJSON file. Feeds future sleep HRV, CAR, postprandial, " +
                    "stress event detection. Sleep with strap on → wake with overnight data."
            } else {
                "Pair a chest strap via SCAN & PAIR below, then turn ON. Persistent BLE " +
                    "connection independent of rides. Captures HR + RR intervals 24/7 to NDJSON. " +
                    "Costs ~5-10% extra phone battery per day (strap battery drains separately, ~50h per full charge)."
            },
            color = UrujMuted,
            fontSize = 11.sp,
        )
        if (enabled) {
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UrujSurfaceHigh.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                Text(
                    "TODAY'S CAPTURE",
                    color = UrujMuted,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.sp,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "$samplesToday samples written",
                    color = UrujText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Text(
                    text = "Notification in your panel shows live status. " +
                        "Tap toggle OFF before riding if you want to free up BLE.",
                    color = UrujMuted,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(enabled: Boolean) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (enabled) UrujZone2 else UrujZone5),
    )
}
