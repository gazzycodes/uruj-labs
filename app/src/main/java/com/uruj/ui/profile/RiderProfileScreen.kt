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
                        contentColor = Color.Black,
                    ),
                ) {
                    Text("SAVE", fontWeight = FontWeight.Black, fontSize = 16.sp, letterSpacing = 3.sp)
                }
            }

            Spacer(Modifier.height(20.dp))
        }
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
