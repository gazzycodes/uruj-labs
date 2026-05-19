package com.uruj.ui.trend

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.uruj.data.ContinuousBiometricRepository
import com.uruj.data.SleepSnapshot
import com.uruj.data.SleepSnapshotRepository
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujZone1
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone4
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * v0.7.4 — overnight HRV trend.
 *
 * v0.9.9 — refactored to DISK-ONLY at render time, honoring the
 * [[reference_snapshot_persistence_architecture]] rule. Pre-v0.9.9 this
 * screen called `LastSleepReader.listLastNDays(client, granted, 90)`
 * directly on render — ~90 HC reads per open. Now it reads sleep windows
 * from [SleepSnapshotRepository] (disk) and slices the URUJ continuous
 * NDJSON for the HRV per window. ZERO HC reads at render time.
 *
 * Bio Lab Autonomic card still uses live HC sleep window for TONIGHT's
 * single value (one-shot read, not 90 days). This trend screen is the
 * longitudinal view → all disk.
 */
@Composable
fun HrvTrendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val continuousRepo = remember { ContinuousBiometricRepository(context) }
    val sleepSnapshots = remember { SleepSnapshotRepository(context) }
    var nightlyHrv by remember {
        mutableStateOf<List<NightlyHrvPoint>?>(null)
    }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        nightlyHrv = withContext(Dispatchers.IO) {
            buildNightlyHrvFromSnapshots(continuousRepo, sleepSnapshots)
        }
        loading = false
    }

    val zone = ZoneId.systemDefault()
    val todayIso = LocalDate.now(zone).toString()
    val points = nightlyHrv?.sortedBy { it.sleepEndMs }?.map {
        TrendPoint(
            labelMs = it.sleepEndMs,
            y = it.rmssdMs,
            isToday = it.dateIsoLocal == todayIso,
        )
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
                "captures the window. Sleep snapshots persist to disk forever, so this " +
                "trend chart builds without further HC reads.",
            methodologyFootnote = "Each point = one night's median-of-5-min-windows " +
                "RMSSD from the BLE chest strap, NATURAL breathing during the actual " +
                "Samsung-detected sleep window. Sleep windows read from URUJ's local " +
                "SleepSnapshotRepository (v0.9.8+) — no HC reads at chart render. " +
                "Tier bands: Plews et al. + Shaffer & Ginsberg 2017 athletic norms.",
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
    val dateIsoLocal: String,
)

/**
 * v0.9.9 — read sleep windows from disk-persisted [SleepSnapshot]s and
 * slice the URUJ continuous NDJSON per window. Zero HC reads.
 *
 * Edge cases:
 * - No snapshots yet → returns empty (chart shows empty state with guidance
 *   to wear strap + open URUJ daily)
 * - Sleep snapshot exists but NDJSON missing for that window → HRV computes
 *   null → night dropped (consistent with prior behavior)
 * - Today's snapshot present → renders with brighter `isToday` dot per v0.9.6
 */
private suspend fun buildNightlyHrvFromSnapshots(
    continuousRepo: ContinuousBiometricRepository,
    sleepSnapshots: SleepSnapshotRepository,
): List<NightlyHrvPoint> {
    val snapshots: List<SleepSnapshot> = sleepSnapshots.listAll()
    if (snapshots.isEmpty()) return emptyList()
    return snapshots.mapNotNull { s ->
        val start = Instant.ofEpochMilli(s.sessionStartMs)
        val end = Instant.ofEpochMilli(s.sessionEndMs)
        val hrv = continuousRepo.computeHrvForWindow(start, end) ?: return@mapNotNull null
        NightlyHrvPoint(
            sleepEndMs = s.sessionEndMs,
            rmssdMs = hrv.rmssdMs,
            windowCount = hrv.windowCount,
            dateIsoLocal = s.dateIsoLocal,
        )
    }
}
