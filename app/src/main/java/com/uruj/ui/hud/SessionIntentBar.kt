package com.uruj.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uruj.data.SessionIntentStore
import com.uruj.domain.SessionIntent
import com.uruj.service.RideState
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import kotlinx.coroutines.launch

/**
 * v0.8.0 — HUD strip showing the current SessionIntent + a CHANGE button.
 * Tapping CHANGE opens a dialog with all six options. Selection writes
 * to SessionIntentStore; RideRecorderService observes the flow and the
 * audio coach's onIntentChanged() is fired (60s grace before any new cues).
 *
 * Compact one-line layout so it doesn't crowd the speed hero above it.
 * Hidden for EXPLORATORY when on Exploratory the user hasn't opted into
 * coaching — the UI doesn't push them. Still tappable to switch ON.
 */
@Composable
fun SessionIntentBar(state: RideState) {
    val context = LocalContext.current
    val store = remember { SessionIntentStore(context) }
    val scope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }
    val intent = state.sessionIntent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(UrujSurface.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "SESSION",
            color = UrujMuted,
            fontWeight = FontWeight.Black,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.padding(horizontal = 6.dp))
        Text(
            intent.shortLabel,
            color = if (intent.hasTarget()) UrujAccent else UrujMuted,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
        )
        if (intent.hasTarget()) {
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(
                "TARGET Z${intent.targetZoneMin}",
                color = UrujText,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = { showPicker = true }) {
            Text(
                "CHANGE",
                color = UrujAccent,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
            )
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text("CLOSE", color = UrujAccent, fontWeight = FontWeight.Bold)
                }
            },
            title = {
                Text("Change session intent", color = UrujText,
                    fontWeight = FontWeight.Black, fontSize = 16.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Switching mid-ride is fine. Audio coach honors the new " +
                            "target after a 60-second grace period (no immediate nagging).",
                        color = UrujMuted, fontSize = 11.sp,
                    )
                    Spacer(Modifier.padding(vertical = 2.dp))
                    for (option in SessionIntent.entries) {
                        val isCurrent = option == intent
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isCurrent) UrujAccent.copy(alpha = 0.15f)
                                    else UrujSurfaceHigh.copy(alpha = 0.3f),
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    option.displayLabel,
                                    color = if (isCurrent) UrujAccent else UrujText,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                )
                                Text(
                                    option.description,
                                    color = UrujMuted, fontSize = 10.sp,
                                )
                            }
                            TextButton(
                                onClick = {
                                    scope.launch { store.set(option) }
                                    showPicker = false
                                },
                                enabled = !isCurrent,
                            ) {
                                Text(
                                    if (isCurrent) "ACTIVE" else "PICK",
                                    color = if (isCurrent) UrujMuted else UrujAccent,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            },
            containerColor = UrujSurface,
        )
    }
}
