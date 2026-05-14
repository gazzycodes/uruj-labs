package com.uruj

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uruj.data.RideHistoryRepository
import com.uruj.data.StoredRideSummary
import com.uruj.service.RideRecorderService
import com.uruj.service.RideStateHolder
import kotlinx.coroutines.Dispatchers
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
    data class ViewingPastRide(val summary: StoredRideSummary) : AppScreen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val rideState by RideStateHolder.state.collectAsStateWithLifecycle()
            val completed by RideStateHolder.completedRide.collectAsStateWithLifecycle()
            var screen by remember { mutableStateOf<AppScreen>(AppScreen.Checklist) }
            // Route map overlays whichever summary launched it. Held at top level
            // so back from map returns to the right summary without losing state.
            var routeMapSession by remember { mutableStateOf<String?>(null) }
            // Orphan-ride recovery banner. Runs once on cold-start to detect rides
            // whose NDJSON exists but summary doesn't — typically from OS killing
            // the service mid-ride. v0.3.7 fix — user reported app killed on
            // backgrounding + opening URUJ showed blank state. Now we auto-finalize
            // those rides and surface a banner so the user sees their data is safe.
            var recoveredRide by remember { mutableStateOf<StoredRideSummary?>(null) }
            LaunchedEffect(Unit) {
                val recovered = withContext(Dispatchers.IO) {
                    RideHistoryRepository(this@MainActivity).recoverOrphanRides()
                }
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
                // Surface the orphan-ride recovery banner once before normal nav.
                val recovered = recoveredRide
                if (recovered != null) {
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

    private fun stopRide() {
        val intent = Intent(this, RideRecorderService::class.java).apply {
            action = RideRecorderService.ACTION_STOP
        }
        startService(intent)
    }
}
