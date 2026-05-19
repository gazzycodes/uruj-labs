package com.uruj

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uruj.data.RideHistoryRepository
import com.uruj.data.StoredRideSummary
import com.uruj.service.RideRecorderService
import com.uruj.service.RideStateHolder
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.uruj.ui.biolab.BioLabScreen
import com.uruj.ui.checklist.PreRideChecklistScreen
import com.uruj.ui.diagnostics.DiagnosticsScreen
import com.uruj.ui.history.RideHistoryScreen
import com.uruj.ui.hud.HudScreen
import com.uruj.ui.profile.RiderProfileScreen
import com.uruj.ui.routemap.RouteMapScreen
import com.uruj.ui.summary.RideSummaryScreen
import com.uruj.ui.theme.URUJTheme

@androidx.compose.runtime.Composable
private fun ActiveOrphanDialog(
    summary: StoredRideSummary,
    onResume: () -> Unit,
    onEndAndSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    val km = "%.2f".format(summary.totalDistanceMeters / 1000)
    val minutes = summary.movingTimeMs / 60_000
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { /* no-op — user must pick an action */ },
        title = {
            androidx.compose.material3.Text(
                "Ride in progress detected",
                color = com.uruj.ui.theme.UrujText,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
            )
        },
        text = {
            androidx.compose.material3.Text(
                "Your last ride ($km km · ${minutes}m) was interrupted before you " +
                    "tapped STOP. The recording is intact. What do you want to do?\n\n" +
                    "RESUME — continue from where it stopped.\n" +
                    "END & SAVE — finalize as-is, add to RIDES history.\n" +
                    "DISCARD — delete everything from this session.",
                color = com.uruj.ui.theme.UrujText,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onResume) {
                androidx.compose.material3.Text(
                    "RESUME",
                    color = com.uruj.ui.theme.UrujAccent,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                )
            }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                androidx.compose.material3.TextButton(onClick = onEndAndSave) {
                    androidx.compose.material3.Text(
                        "END & SAVE",
                        color = com.uruj.ui.theme.UrujText,
                    )
                }
                androidx.compose.material3.TextButton(onClick = onDiscard) {
                    androidx.compose.material3.Text(
                        "DISCARD",
                        color = com.uruj.ui.theme.UrujMuted,
                    )
                }
            }
        },
        containerColor = com.uruj.ui.theme.UrujSurface,
    )
}

@androidx.compose.runtime.Composable
private fun OrphanRecoveryDialog(
    summary: StoredRideSummary,
    onDismiss: () -> Unit,
    onViewRide: () -> Unit,
) {
    val km = "%.2f".format(summary.totalDistanceMeters / 1000)
    val minutes = summary.movingTimeMs / 60_000
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            androidx.compose.material3.Text(
                "Previous ride recovered",
                color = com.uruj.ui.theme.UrujText,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
            )
        },
        text = {
            androidx.compose.material3.Text(
                "Your ride was interrupted (likely the OS killed the recording service). " +
                    "We rebuilt the data from the saved samples: $km km in ${minutes}m. " +
                    "It's been added to your RIDES history.",
                color = com.uruj.ui.theme.UrujText,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onViewRide) {
                androidx.compose.material3.Text(
                    "VIEW RIDE",
                    color = com.uruj.ui.theme.UrujAccent,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text(
                    "DISMISS",
                    color = com.uruj.ui.theme.UrujMuted,
                )
            }
        },
        containerColor = com.uruj.ui.theme.UrujSurface,
    )
}

private sealed interface AppScreen {
    data object Checklist : AppScreen
    data object Profile : AppScreen
    data object History : AppScreen
    data object Diagnostics : AppScreen
    data object BioLab : AppScreen
    data object OrthostaticTest : AppScreen
    data object HrvTrend : AppScreen
    data object Hrr1Trend : AppScreen
    data object CarTrend : AppScreen
    data object OrthostaticTrend : AppScreen
    // v0.9.6 — disk-snapshot trend screens for RHR / VO2 / TSB
    data object RhrTrend : AppScreen
    data object Vo2Trend : AppScreen
    data object TsbTrend : AppScreen
    // v0.9.8 — sleep-hours trend (driven by new SleepSnapshotRepository)
    data object SleepTrend : AppScreen
    data object LiveData : AppScreen
    data class ViewingPastRide(val summary: StoredRideSummary) : AppScreen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v0.8.0 — force LIGHT system bar icons (white time/battery/signal)
        // because URUJ is always a dark-themed app. Default `enableEdgeToEdge`
        // tries to auto-detect via the activity's background, which on
        // some OEMs (OnePlus / OxygenOS) misreads and leaves icons dark on
        // a dark background → invisible status bar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        // v0.6.0 — start 24/7 biometric service on app launch if user has
        // toggled it on. Service is foreground + sticky, so once running it
        // survives app close, screen off, and most OEM Doze. Reboot doesn't
        // auto-resume (Android limit); next launch of URUJ re-starts it.
        lifecycleScope.launch(Dispatchers.IO) {
            val settings = com.uruj.data.BiometricSettingsStore(this@MainActivity).current()
            if (settings.enabled) {
                com.uruj.service.BiometricService.start(this@MainActivity)
            }
        }
        setContent {
            val rideState by RideStateHolder.state.collectAsStateWithLifecycle()
            val completed by RideStateHolder.completedRide.collectAsStateWithLifecycle()
            var screen by remember { mutableStateOf<AppScreen>(AppScreen.Checklist) }
            // Route map overlays whichever summary launched it. Held at top level
            // so back from map returns to the right summary without losing state.
            var routeMapSession by remember { mutableStateOf<String?>(null) }
            // v0.3.7: passive orphan recovery (NDJSON without summary, e.g. pre-marker rides).
            // v0.3.8: ACTIVE orphan detection (sessions with `.active` marker still on disk).
            //         These get a Resume/End/Discard dialog BEFORE the passive recovery runs.
            var recoveredRide by remember { mutableStateOf<StoredRideSummary?>(null) }
            var activeOrphan by remember { mutableStateOf<StoredRideSummary?>(null) }
            val ioScope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                val repo = RideHistoryRepository(this@MainActivity)
                val active = withContext(Dispatchers.IO) { repo.findActiveOrphans() }
                if (active.isNotEmpty()) {
                    activeOrphan = active.maxByOrNull { it.endedAtMs }
                    // Don't run passive recovery yet — let user choose first.
                    return@LaunchedEffect
                }
                val recovered = withContext(Dispatchers.IO) { repo.recoverOrphanRides() }
                recoveredRide = recovered.maxByOrNull { it.endedAtMs }
            }

            LaunchedEffect(rideState.isRecording) {
                setShowWhenLocked(rideState.isRecording)
                setTurnScreenOn(rideState.isRecording)
                if (rideState.isRecording) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            URUJTheme {
                // ACTIVE orphan: ride was interrupted, .active marker still present.
                // Offer the rider a choice before any auto-recovery.
                val active = activeOrphan
                if (active != null) {
                    ActiveOrphanDialog(
                        summary = active,
                        onResume = {
                            startRideResume(active.sessionId)
                            activeOrphan = null
                        },
                        onEndAndSave = {
                            // v0.8.5 — bulletproof END & SAVE flow.
                            //
                            // Pre-v0.8.5: kicked off finalizeActiveOrphan, set
                            // recoveredRide=active. User then saw the
                            // "Previous ride recovered" dialog (which has wording
                            // implying the OS auto-recovered the ride — incorrect
                            // for this path where the user EXPLICITLY chose end &
                            // save). Tapping VIEW RIDE attempted to navigate to
                            // ViewingPastRide but could land on HUD if Android
                            // had auto-restarted the killed RideRecorderService
                            // (START_STICKY) and rideState.isRecording was still
                            // true — the navigation `when` block routes
                            // isRecording=true to HudScreen BEFORE checking the
                            // `screen` state.
                            //
                            // v0.8.5 fixes both issues:
                            //   1. Defensively send ACTION_STOP to the service
                            //      (in case Android auto-restarted it).
                            //   2. Explicitly reset RideStateHolder so the nav
                            //      chain sees isRecording=false immediately
                            //      (no race with the service's STOP intent).
                            //   3. Navigate DIRECTLY to ViewingPastRide; skip
                            //      the OrphanRecoveryDialog (wrong wording for
                            //      this user-initiated path).
                            //   4. Reload the freshly-finalized summary so the
                            //      displayed stats reflect the NDJSON rebuild.
                            val sessionId = active.sessionId
                            ioScope.launch {
                                stopRide()
                                RideStateHolder.reset()
                                val final = withContext(Dispatchers.IO) {
                                    val repo = RideHistoryRepository(this@MainActivity)
                                    repo.finalizeActiveOrphan(sessionId)
                                    repo.load(sessionId)
                                } ?: active
                                screen = AppScreen.ViewingPastRide(final)
                                activeOrphan = null
                                recoveredRide = null
                            }
                        },
                        onDiscard = {
                            ioScope.launch {
                                // Defensive: ensure service is stopped if Android
                                // auto-restarted it post-kill, before deleting
                                // the NDJSON it might still be appending to.
                                stopRide()
                                RideStateHolder.reset()
                                withContext(Dispatchers.IO) {
                                    RideHistoryRepository(this@MainActivity)
                                        .discardActiveOrphan(active.sessionId)
                                }
                                activeOrphan = null
                            }
                        },
                    )
                }
                // PASSIVE recovery banner — only shown when there were NO active orphans
                // requiring user choice (or after End-and-save converted one into recovered).
                val recovered = recoveredRide
                if (recovered != null && activeOrphan == null) {
                    OrphanRecoveryDialog(
                        summary = recovered,
                        onDismiss = { recoveredRide = null },
                        onViewRide = {
                            screen = AppScreen.ViewingPastRide(recovered)
                            recoveredRide = null
                        },
                    )
                }
                val activeMapSession = routeMapSession
                when {
                    activeMapSession != null -> RouteMapScreen(
                        sessionId = activeMapSession,
                        onBack = { routeMapSession = null },
                    )
                    rideState.isRecording -> HudScreen(onStopRide = ::stopRide)
                    completed != null -> RideSummaryScreen(
                        state = completed!!,
                        onDone = { RideStateHolder.dismissCompleted() },
                        onOpenMap = { sid -> routeMapSession = sid },
                    )
                    else -> when (val current = screen) {
                        AppScreen.Checklist -> PreRideChecklistScreen(
                            onStartRide = ::startRide,
                            onOpenProfile = { screen = AppScreen.Profile },
                            onOpenHistory = { screen = AppScreen.History },
                            onOpenDiagnostics = { screen = AppScreen.Diagnostics },
                            onOpenBioLab = { screen = AppScreen.BioLab },
                            onOpenLive = { screen = AppScreen.LiveData },
                        )
                        AppScreen.LiveData -> com.uruj.ui.live.LiveScreen(
                            onBack = { screen = AppScreen.Checklist },
                        )
                        AppScreen.Profile -> RiderProfileScreen(
                            onBack = { screen = AppScreen.Checklist },
                        )
                        AppScreen.History -> RideHistoryScreen(
                            onBack = { screen = AppScreen.Checklist },
                            onOpenRide = { summary ->
                                screen = AppScreen.ViewingPastRide(summary)
                            },
                        )
                        AppScreen.Diagnostics -> DiagnosticsScreen(
                            onBack = { screen = AppScreen.Checklist },
                        )
                        AppScreen.BioLab -> BioLabScreen(
                            onBack = { screen = AppScreen.Checklist },
                            onOpenOrthostatic = { screen = AppScreen.OrthostaticTest },
                            onOpenHrvTrend = { screen = AppScreen.HrvTrend },
                            onOpenHrr1Trend = { screen = AppScreen.Hrr1Trend },
                            onOpenCarTrend = { screen = AppScreen.CarTrend },
                            onOpenOrthostaticTrend = { screen = AppScreen.OrthostaticTrend },
                            onOpenRhrTrend = { screen = AppScreen.RhrTrend },
                            onOpenVo2Trend = { screen = AppScreen.Vo2Trend },
                            onOpenTsbTrend = { screen = AppScreen.TsbTrend },
                            onOpenSleepTrend = { screen = AppScreen.SleepTrend },
                        )
                        AppScreen.OrthostaticTest -> com.uruj.ui.orthostatic.OrthostaticTestScreen(
                            onBack = { screen = AppScreen.BioLab },
                        )
                        AppScreen.HrvTrend -> com.uruj.ui.trend.HrvTrendScreen(
                            onBack = { screen = AppScreen.BioLab },
                        )
                        AppScreen.Hrr1Trend -> com.uruj.ui.trend.Hrr1TrendScreen(
                            onBack = { screen = AppScreen.BioLab },
                        )
                        AppScreen.CarTrend -> com.uruj.ui.trend.CarTrendScreen(
                            onBack = { screen = AppScreen.BioLab },
                        )
                        AppScreen.OrthostaticTrend -> com.uruj.ui.trend.OrthostaticTrendScreen(
                            onBack = { screen = AppScreen.BioLab },
                        )
                        AppScreen.RhrTrend -> com.uruj.ui.trend.RhrTrendScreen(
                            onBack = { screen = AppScreen.BioLab },
                        )
                        AppScreen.Vo2Trend -> com.uruj.ui.trend.Vo2TrendScreen(
                            onBack = { screen = AppScreen.BioLab },
                        )
                        AppScreen.TsbTrend -> com.uruj.ui.trend.TsbTrendScreen(
                            onBack = { screen = AppScreen.BioLab },
                        )
                        AppScreen.SleepTrend -> com.uruj.ui.trend.SleepTrendScreen(
                            onBack = { screen = AppScreen.BioLab },
                        )
                        is AppScreen.ViewingPastRide -> RideSummaryScreen(
                            state = current.summary.toRideState(),
                            onDone = { screen = AppScreen.History },
                            onOpenMap = { sid -> routeMapSession = sid },
                        )
                    }
                }
            }
        }
    }

    private fun startRide() {
        val intent = Intent(this, RideRecorderService::class.java).apply {
            action = RideRecorderService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun startRideResume(sessionId: String) {
        val intent = Intent(this, RideRecorderService::class.java).apply {
            action = RideRecorderService.ACTION_RESUME
            putExtra(RideRecorderService.EXTRA_RESUME_SESSION_ID, sessionId)
        }
        startForegroundService(intent)
    }

    private fun stopRide() {
        val intent = Intent(this, RideRecorderService::class.java).apply {
            action = RideRecorderService.ACTION_STOP
        }
        startService(intent)
    }
}
