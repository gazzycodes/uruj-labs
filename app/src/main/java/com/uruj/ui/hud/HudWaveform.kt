package com.uruj.ui.hud

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uruj.data.RiderProfileStore
import com.uruj.power.KarvonenZonesCalculator
import com.uruj.service.LiveStateHolder
import com.uruj.ui.live.HrWaveform

/**
 * v0.8.1 — compact HUD inline HR waveform. Last 30 sec of beats, ~80dp tall.
 * Tier-color shaded per current zone. Sits between the SessionIntentBar
 * and the StrapRow when a paired strap is present.
 *
 * Reads from LiveStateHolder (singleton populated by RideRecorderService's
 * BLE collect). No separate BLE subscription.
 */
@Composable
fun HudWaveform() {
    val context = LocalContext.current
    val live by LiveStateHolder.state.collectAsStateWithLifecycle()
    val profileStore = remember { RiderProfileStore(context) }
    val profile by profileStore.profile.collectAsStateWithLifecycle(
        initialValue = com.uruj.domain.RiderProfile(),
    )
    val zones = remember(profile.maxHrBpm, profile.restingHrBpm) {
        KarvonenZonesCalculator().compute(profile.maxHrBpm, profile.restingHrBpm.coerceAtLeast(40))
    }
    HrWaveform(
        beats = live.recentBeats,
        windowMs = 30_000L,
        zones = zones,
        height = 80.dp,
        showYAxisLabels = false,
        showCurrentBpm = false,
    )
}
