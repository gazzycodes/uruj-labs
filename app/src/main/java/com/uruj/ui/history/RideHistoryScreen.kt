package com.uruj.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uruj.data.StoredRideSummary
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RideHistoryScreen(
    onBack: () -> Unit,
    onOpenRide: (StoredRideSummary) -> Unit,
    viewModel: RideHistoryViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    val rides by viewModel.rides.collectAsStateWithLifecycle()
    // v0.9.22 — weekly polarized compliance summary for the current ISO week.
    // Null while loading, present once the per-ride HR samples have been
    // read + aggregated.
    val weeklyPolarized by viewModel.weeklyPolarized.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "RIDES",
                    color = UrujMuted,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onBack) {
                    Text(
                        "CLOSE",
                        color = UrujAccent,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                    )
                }
            }
            Text(
                text = "History",
                color = UrujText,
                fontWeight = FontWeight.Black,
                fontSize = 32.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (rides.isEmpty()) "No rides yet. Go earn one."
                else "${rides.size} ride${if (rides.size == 1) "" else "s"} on record.",
                color = UrujMuted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // v0.9.22 — weekly polarized compliance card at the top.
                // v0.9.23 — skeleton while loading (pre-fix the card was
                // hidden until results landed → rider had no idea if data
                // was coming).
                item(key = "weekly-polarized") {
                    val week = weeklyPolarized
                    if (week != null) {
                        WeeklyPolarizedCard(week = week)
                    } else {
                        com.uruj.ui.components.WeeklyPolarizedSkeleton()
                    }
                }
                items(rides, key = { it.sessionId }) { ride ->
                    RideCard(ride = ride, onClick = { onOpenRide(ride) })
                }
            }
        }
    }
}

@Composable
private fun RideCard(ride: StoredRideSummary, onClick: () -> Unit) {
    val dateFormatter = SimpleDateFormat("EEE, d MMM · HH:mm", Locale.getDefault())
    val dateLabel = dateFormatter.format(Date(ride.startedAtMs))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UrujSurface, RoundedCornerShape(14.dp))
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = dateLabel,
                color = UrujMuted,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "%.2f".format(ride.totalDistanceMeters / 1000),
                    color = UrujText,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    fontSize = 26.sp,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "km",
                    color = UrujMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = formatDuration(ride.movingTimeMs),
                    color = UrujText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "%.1f kph avg · ≈${ride.averagePowerWatts.toInt()}W · ${ride.totalElevGainMeters.toInt()}m ↑"
                    .format(ride.averageSpeedKph),
                color = UrujMuted,
                fontSize = 12.sp,
            )
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%d:%02d:%02d".format(h, m, s)
}
