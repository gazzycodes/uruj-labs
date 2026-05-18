package com.uruj.ui.checklist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uruj.data.SessionIntentStore
import com.uruj.domain.SessionIntent
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import kotlinx.coroutines.launch

/**
 * v0.8.0 — pre-ride session intent picker. Six options in a vertical list of
 * tappable rows. Selected row highlighted with the URUJ accent border.
 *
 * Default selection comes from the persisted last-picked intent (defaults
 * to EXPLORATORY for first-time users). Tapping a row writes to DataStore
 * immediately; RideRecorderService reads at ride start + observes for
 * mid-ride changes.
 */
@Composable
fun SessionIntentPicker() {
    val context = LocalContext.current
    val store = remember { SessionIntentStore(context) }
    val current by store.intent.collectAsState(initial = SessionIntent.EXPLORATORY)
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "TODAY'S SESSION",
            color = UrujMuted,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
        )
        Text(
            "Audio coach gives zone-discipline cues during the ride based on " +
                "this. Switch any time from the HUD if conditions change.",
            color = UrujMuted, fontSize = 11.sp,
        )
        Spacer(Modifier.height(4.dp))
        for (intent in SessionIntent.entries) {
            SessionIntentRow(
                intent = intent,
                selected = intent == current,
                onSelect = { scope.launch { store.set(intent) } },
            )
        }
    }
}

@Composable
private fun SessionIntentRow(
    intent: SessionIntent,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val border = if (selected) {
        Modifier.border(2.dp, UrujAccent, RoundedCornerShape(12.dp))
    } else {
        Modifier.border(1.dp, UrujSurfaceHigh, RoundedCornerShape(12.dp))
    }
    val labelColor = if (selected) UrujAccent else UrujText
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(UrujSurface)
            .then(border)
            .clickable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                intent.displayLabel,
                color = labelColor,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Text(
                intent.description,
                color = UrujMuted,
                fontSize = 11.sp,
            )
        }
        if (intent.hasTarget()) {
            Text(
                "Z${intent.targetZoneMin}",
                color = labelColor,
                fontWeight = FontWeight.Black,
                fontSize = 16.sp,
            )
        } else {
            Text(
                "—",
                color = UrujMuted,
                fontSize = 14.sp,
            )
        }
    }
}
