package com.uruj.ui.profile

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uruj.domain.RiderProfile
import com.uruj.domain.RidingPosition
import com.uruj.domain.TireType
import com.uruj.ui.theme.UrujOnAccent
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText

@Composable
fun RiderProfileScreen(
    onBack: () -> Unit,
    viewModel: RiderProfileViewModel = viewModel(),
) {
    val saved by viewModel.profile.collectAsStateWithLifecycle()
    var draft by remember(saved) { mutableStateOf(saved) }
    // v0.9.13 — last successful HC weight sync timestamp from WeightAutoSync.
    // Surfaces "synced from Samsung X ago" so the rider knows the field
    // isn't stale (foundation of v0.9.12 was plumbed but the UI wasn't
    // wired). Null when HC has never synced (manual-only profile).
    val lastWeightSyncMs by viewModel.lastWeightSyncMs.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(
                text = "PROFILE",
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
            )
            Text(
                text = "Athlete",
                color = UrujText,
                fontWeight = FontWeight.Black,
                fontSize = 32.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "These feed the live power model + zones. Update anytime; new rides use the latest values.",
                color = UrujMuted,
                fontSize = 13.sp,
            )

            Spacer(Modifier.height(20.dp))

            Section("Body & Bike") {
                NumberField("Rider weight (kg)", draft.riderWeightKg) {
                    draft = draft.copy(riderWeightKg = it)
                }
                // v0.9.13 — freshness label for HC-synced weight. Shows the
                // rider when the value last came from Samsung Health so manual
                // overrides vs sync staleness is visible. Hidden if HC sync
                // has never run (no timestamp to display).
                lastWeightSyncMs?.let { ms ->
                    val ageSec = (System.currentTimeMillis() - ms) / 1000L
                    val ageLabel = when {
                        ageSec < 60 -> "just now"
                        ageSec < 3600 -> "${ageSec / 60} min ago"
                        ageSec < 86400 -> "${ageSec / 3600} hr ago"
                        else -> "${ageSec / 86400} day(s) ago"
                    }
                    Text(
                        text = "↻ synced from Samsung Health $ageLabel",
                        color = UrujAccent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                }
                NumberField("Bike weight (kg)", draft.bikeWeightKg) {
                    draft = draft.copy(bikeWeightKg = it)
                }
                IntField("Height (cm)", draft.heightCm) {
                    draft = draft.copy(heightCm = it)
                }
                IntField("Age (years)", draft.ageYears) {
                    draft = draft.copy(ageYears = it, maxHrBpm = 220 - it)
                }
            }

            Spacer(Modifier.height(16.dp))

            Section("Training Zones") {
                IntField(
                    label = "FTP (watts) — your 20min best × 0.95",
                    value = draft.ftpWatts,
                ) { draft = draft.copy(ftpWatts = it) }
                IntField(
                    label = "Max HR (bpm) — auto-defaults from age",
                    value = draft.maxHrBpm,
                ) { draft = draft.copy(maxHrBpm = it) }
            }

            Spacer(Modifier.height(16.dp))

            Section("Setup") {
                EnumDropdown(
                    label = "Tire type",
                    selected = draft.tireType,
                    options = TireType.entries,
                    label2 = { it.displayName },
                ) { draft = draft.copy(tireType = it) }
                EnumDropdown(
                    label = "Riding position",
                    selected = draft.ridingPosition,
                    options = RidingPosition.entries,
                    label2 = { it.displayName },
                ) { draft = draft.copy(ridingPosition = it) }
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Text("CANCEL", color = UrujMuted, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                }
                Button(
                    onClick = {
                        viewModel.save(draft)
                        onBack()
                    },
                    modifier = Modifier.weight(2f).height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = UrujAccent,
                        contentColor = UrujOnAccent,
                    ),
                ) {
                    Text("SAVE", fontWeight = FontWeight.Black, fontSize = 16.sp, letterSpacing = 3.sp)
                }
            }

            Spacer(Modifier.height(24.dp))

            // Personal-records management. Hidden behind a confirm dialog because
            // it's destructive — wipes 60s / 5min / 20min power ceilings. Needed
            // when pre-v0.1 test rides set fake-high PRs (indoor walking before
            // the GPS-quality fix registered as kilowatt spikes) and today's real
            // efforts can't beat the polluted ceilings.
            PersonalRecordsResetSection(
                onReset = { viewModel.resetPrs() },
            )

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun PersonalRecordsResetSection(onReset: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    var resetDone by remember { mutableStateOf(false) }

    Section("Personal Records") {
        Text(
            "Wipes the 60s / 5min / 20min power ceilings. New rides will build fresh PRs " +
                "from scratch. Use this if early test rides set unrealistic ceilings.",
            color = UrujMuted,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { showConfirm = true },
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(
                if (resetDone) "✓ PRs cleared" else "RESET ALL PERSONAL RECORDS",
                color = if (resetDone) UrujAccent else Color(0xFFFF5252),
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
        }
    }

    if (showConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Reset Personal Records?", color = UrujText) },
            text = {
                Text(
                    "All current PR ceilings will be cleared. The next sustained 60s / 5min / 20min " +
                        "efforts will become your new PRs. This cannot be undone.",
                    color = UrujMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onReset()
                    resetDone = true
                    showConfirm = false
                }) {
                    Text("RESET", color = Color(0xFFFF5252), fontWeight = FontWeight.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("CANCEL", color = UrujMuted)
                }
            },
            containerColor = UrujSurface,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title.uppercase(),
            color = UrujMuted,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(UrujSurface, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun NumberField(label: String, value: Float, onChange: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.toFloatOrNull()?.let(onChange)
        },
        label = { Text(label, color = UrujMuted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = UrujText,
            unfocusedTextColor = UrujText,
            focusedBorderColor = UrujAccent,
            unfocusedBorderColor = UrujSurfaceHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun IntField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.toIntOrNull()?.let(onChange)
        },
        label = { Text(label, color = UrujMuted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = UrujText,
            unfocusedTextColor = UrujText,
            focusedBorderColor = UrujAccent,
            unfocusedBorderColor = UrujSurfaceHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun <T> EnumDropdown(
    label: String,
    selected: T,
    options: List<T>,
    label2: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = label2(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label, color = UrujMuted) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = UrujText,
                unfocusedTextColor = UrujText,
                focusedBorderColor = UrujAccent,
                unfocusedBorderColor = UrujSurfaceHigh,
            ),
            modifier = Modifier
                .fillMaxWidth(),
            trailingIcon = {
                TextButton(onClick = { expanded = !expanded }) {
                    Text("CHANGE", color = UrujAccent, fontWeight = FontWeight.Black, fontSize = 11.sp)
                }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(label2(opt)) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    },
                )
            }
        }
    }
}
