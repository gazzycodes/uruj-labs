package com.uruj.ui.trend

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import com.uruj.data.ContinuousBiometricRepository
import com.uruj.data.LastSleepReader
import com.uruj.power.HrvCalculator
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujZone1
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone4
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * v0.7.4 follow-up — overnight HRV trend now sliced by Samsung's ACTUAL sleep
 * windows (via LastSleepReader.listLastNDays), not the 22:00-09:00 heuristic
 * the v0.7.3 first implementation used. Matches the Bio Lab Autonomic card
 * which always used the Samsung window, so the numbers agree across surfaces.
 *
 * The heuristic-window approach was producing artifically-lower RMSSD because
 * the wider window (22:00-09:00) included pre-sleep + post-wake periods where
 * HRV is naturally lower. Fix: use the real sleep window per night.
 */
@Composable
fun HrvTrendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val continuousRepo = remember { ContinuousBiometricRepository(context) }
    val sleepReader = remember { LastSleepReader() }
    var nightlyHrv by remember {
        mutableStateOf<List<NightlyHrvPoint>?>(null)
    }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        nightlyHrv = withContext(Dispatchers.IO) {
            buildNightlyHrv(context, continuousRepo, sleepReader, days = 90)
        }
        loading = false
    }

    val points = nightlyHrv?.sortedBy { it.sleepEndMs }?.map {
        TrendPoint(labelMs = it.sleepEndMs, y = it.rmssdMs)
    } ?: emptyList()
    val rmssdMax = (points.maxOfOrNull { it.y } ?: 50f).coerceAtLeast(50f) + 10f

    TrendShell(
        spec = TrendSpec(
            eyebrow = "OVERNIGHT HRV TREND",
            title = "Recovery curve",
            intro = "One reading per night, sliced from Samsung's actual sleep " +
                "window — same source the Bio Lab Autonomic card uses. Higher = " +
                "more parasympathetic recovery. Trend across days matters more " +
                "than any single value.",
            points = points,
            yMin = 0f,
            yMax = rmssdMax,
            yTicks = listOf(20f, 30f, 50f, 80f).filter { it <= rmssdMax },
            tierBands = listOf(
                TierBand(80f, rmssdMax, UrujZone1, "Elite"),
                TierBand(50f, 80f, UrujZone2, "Trained"),
                TierBand(30f, 50f, UrujZone3, "Average"),
                TierBand(20f, 30f, UrujZone4, "Below avg"),
                TierBand(0f, 20f, UrujZone5, "Suppressed"),
            ),
            lineColor = UrujAccent,
            valueFormatter = { "${"%.1f".format(it)} ms" },
            detailFormatter = { p ->
                val entry = nightlyHrv?.firstOrNull { it.sleepEndMs == p.labelMs }
                "${entry?.windowCount ?: 0} windows"
            },
            higherIsBetter = true,
            emptyTitle = "No overnight readings yet",
            emptyBody = "Wear the strap to sleep with Samsung Health tracking sleep " +
                "AND the 24/7 monitoring service enabled. First reading appears " +
                "tomorrow morning after Samsung writes the sleep record + 24/7 NDJSON " +
                "captures the window.",
            methodologyFootnote = "Each point = one night's median-of-5-min-windows " +
                "RMSSD from the BLE chest strap, NATURAL breathing during the actual " +
                "Samsung-detected sleep window (start to wake). NOT a paced morning " +
                "reading — see Bio Lab Autonomic card for the paced-vs-natural " +
                "breathing distinction. Tier bands: Plews et al. + Shaffer & Ginsberg " +
                "2017 athletic norms.",
            loading = loading,
        ),
        onBack = onBack,
    )
}

/** One night's HRV plus the metadata the chart needs. */
private data class NightlyHrvPoint(
    val sleepEndMs: Long,
    val rmssdMs: Float,
    val windowCount: Int,
)

/** Slice the last N days of Samsung sleep windows, compute HRV per window. */
private suspend fun buildNightlyHrv(
    context: android.content.Context,
    continuousRepo: ContinuousBiometricRepository,
    sleepReader: LastSleepReader,
    days: Int,
): List<NightlyHrvPoint> {
    val sdkOk = HealthConnectClient.getSdkStatus(context) ==
        HealthConnectClient.SDK_AVAILABLE
    if (!sdkOk) return emptyList()
    val client = runCatching { HealthConnectClient.getOrCreate(context) }
        .getOrNull() ?: return emptyList()
    val granted = runCatching { client.permissionController.getGrantedPermissions() }
        .getOrDefault(emptySet())
    val sessions = sleepReader.listLastNDays(client, granted, days)
    return sessions.mapNotNull { s ->
        val hrv = continuousRepo.computeHrvForWindow(s.startedAt, s.endedAt) ?: return@mapNotNull null
        NightlyHrvPoint(
            sleepEndMs = s.endedAt.toEpochMilli(),
            rmssdMs = hrv.rmssdMs,
            windowCount = hrv.windowCount,
        )
    }
}
