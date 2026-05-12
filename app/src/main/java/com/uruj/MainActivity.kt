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
import com.uruj.data.StoredRideSummary
import com.uruj.service.RideRecorderService
import com.uruj.service.RideStateHolder
import com.uruj.ui.checklist.PreRideChecklistScreen
import com.uruj.ui.diagnostics.DiagnosticsScreen
import com.uruj.ui.history.RideHistoryScreen
import com.uruj.ui.hud.HudScreen
import com.uruj.ui.profile.RiderProfileScreen
import com.uruj.ui.summary.RideSummaryScreen
import com.uruj.ui.theme.URUJTheme

private sealed interface AppScreen {
    data object Checklist : AppScreen
    data object Profile : AppScreen
    data object History : AppScreen
    data object Diagnostics : AppScreen
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
                when {
                    rideState.isRecording -> HudScreen(onStopRide = ::stopRide)
                    completed != null -> RideSummaryScreen(
                        state = completed!!,
                        onDone = { RideStateHolder.dismissCompleted() },
                    )
                    else -> when (val current = screen) {
                        AppScreen.Checklist -> PreRideChecklistScreen(
                            onStartRide = ::startRide,
                            onOpenProfile = { screen = AppScreen.Profile },
                            onOpenHistory = { screen = AppScreen.History },
                            onOpenDiagnostics = { screen = AppScreen.Diagnostics },
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
                        is AppScreen.ViewingPastRide -> RideSummaryScreen(
                            state = current.summary.toRideState(),
                            onDone = { screen = AppScreen.History },
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
