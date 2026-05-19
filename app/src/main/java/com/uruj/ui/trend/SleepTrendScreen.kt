package com.uruj.ui.trend

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import com.uruj.data.LastSleepReader
import com.uruj.data.SleepSnapshot
import com.uruj.data.SleepSnapshotRepository
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * v0.9.8 — overnight sleep-hours trend over time.
 *
 * Data: [SleepSnapshotRepository] daily snapshots saved at Readiness/Bio
 * Lab compute time. Reads disk only — HC's 30d retention can't erase the
 * trend.
 *
 * Tier bands (rough — endurance athlete defaults):
 *   <5h   Severe deficit
 *   5–6h  Under-slept
 *   6–7h  Acceptable but suboptimal
 *   7–9h  OPTIMAL ← live here
 *   9–10h Slightly over
 *   10+h  Catching up / fighting illness
 */
@Composable
fun SleepTrendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { SleepSnapshotRepository(context) }
    var snapshots by remember { mutableStateOf<List<SleepSnapshot>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        snapshots = withContext(Dispatchers.IO) {
            // v0.9.9 — lazy one-time backfill on first open. If no past-date
            // snapshots exist (rider just installed v0.9.8+), pull up to 30
            // days of sleep history from HC's retention window in one shot.
            // Idempotent: re-running is a no-op because past dates are
            // immutable per [[reference_snapshot_persistence_architecture]].
            val existing = repo.listAll()
            val hasPastSnapshots = existing.any {
                it.dateIsoLocal != LocalDate.now(ZoneId.systemDefault()).toString()
            }
            if (!hasPastSnapshots) {
                val sdkOk = HealthConnectClient.getSdkStatus(context) ==
                    HealthConnectClient.SDK_AVAILABLE
                if (sdkOk) {
                    runCatching {
                        val client = HealthConnectClient.getOrCreate(context)
                        val granted = client.permissionController.getGrantedPermissions()
                        repo.backfillFromHc(client, granted, LastSleepReader(), days = 30)
                    }
                    return@withContext repo.listAll()
                }
            }
            existing
        }
        loading = false
    }

    val zone = ZoneId.systemDefault()
    val todayIso = LocalDate.now(zone).toString()
    val points = snapshots.map { snap ->
        TrendPoint(
            labelMs = snap.computedAtMs,
            y = snap.hoursTotal,
            isToday = snap.dateIsoLocal == todayIso,
        )
    }

    val yMin = 3f
    val yMax = (points.maxOfOrNull { it.y } ?: 10f).coerceAtLeast(10f) + 0.5f

    TrendShell(
        spec = TrendSpec(
            eyebrow = "SLEEP TREND",
            title = "Hours per night",
            intro = "Largest input to Readiness (35% weight). Sleep is when hormones " +
                "repair muscle damage and refill glycogen. Endurance athletes need " +
                "consistent 7–9h. The TREND across weeks matters more than a single " +
                "night — single bad nights happen; a falling trend is the signal.",
            points = points,
            yMin = yMin,
            yMax = yMax.coerceAtMost(13f),
            yTicks = listOf(5f, 6f, 7f, 9f),
            tierBands = listOf(
                TierBand(7f, 9f, UrujZone2, "Optimal"),
                TierBand(9f, yMax.coerceAtMost(13f), UrujZone3, "Slightly over"),
                TierBand(6f, 7f, UrujZone3, "Acceptable"),
                TierBand(5f, 6f, UrujZone3, "Under-slept"),
                TierBand(yMin, 5f, UrujZone5, "Severe deficit"),
            ),
            lineColor = UrujAccent,
            valueFormatter = { "${"%.1f".format(it)}h" },
            detailFormatter = { p ->
                val s = snapshots.firstOrNull { it.computedAtMs == p.labelMs }
                if (s != null) s.source else ""
            },
            higherIsBetter = true,
            emptyTitle = "No sleep snapshots yet",
            emptyBody = "URUJ saves one sleep snapshot per day at Readiness " +
                "compute. Wear band overnight + open URUJ next morning — the " +
                "snapshot lands automatically. Disk-persisted forever; won't " +
                "evaporate when HC's 30-day window rolls off.",
            methodologyFootnote = "Hours = Samsung SleepSessionRecord endTime − " +
                "startTime (matches Samsung's 'Sleep Time' display). Sleep stages " +
                "(REM / Deep / Light / Awake) live in Samsung Health — URUJ tracks " +
                "duration only per the v0.4.0 cut decision. Methodology " +
                "${SleepSnapshotRepository.METHODOLOGY_VERSION}. Today's reading " +
                "renders as a brighter dot.",
            loading = loading,
        ),
        onBack = onBack,
    )
}
