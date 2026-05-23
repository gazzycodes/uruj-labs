package com.uruj.ui.biolab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uruj.domain.TrackerType
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText

/**
 * v0.9.39 — Universal tracker entry sheet for the in-app tracker layer (#111).
 *
 * Two-step flow:
 *   Step 1: rider picks WHICH tracker (Mood / Energy / Hydration / Caffeine)
 *   Step 2: type-specific input renders inline → SAVE → BioLabViewModel.saveTrackerEntry
 *
 * Phase 1 (v0.9.39) covers 4 trackers. The sheet is built to extend cleanly
 * as Phase 2-4 trackers ship — just add the TrackerType enum value and add
 * a `when` branch for its input renderer.
 *
 * **Edge cases covered**:
 *   - Multiple entries same day → each save is a new entry (rider can rate
 *     mood multiple times to track diurnal change)
 *   - Empty note → trimmed to null at ViewModel layer
 *   - Cancel mid-entry → no save (state discarded)
 *   - Quick-add hydration presets (250/500/750 ml) AND custom input
 *   - Quick-add caffeine presets (Coffee=95mg, Espresso=63mg, Tea=47mg)
 *     AND custom input
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackerSheet(
    onDismiss: () -> Unit,
    onSave: (TrackerType, Float?, String?, String?) -> Unit,
) {
    var selectedType by remember { mutableStateOf<TrackerType?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (selectedType == null) "Log a tracker entry" else "Log ${selectedType?.displayName}",
                color = UrujText,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (selectedType == null) {
                    Text(
                        "What do you want to log? Pick a tracker:",
                        color = UrujMuted, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TrackerType.entries.forEach { type ->
                            TypeChip(type, onClick = { selectedType = type })
                        }
                    }
                } else {
                    // Render the right input for the chosen tracker
                    when (selectedType) {
                        TrackerType.MOOD -> ScaleInput(
                            scaleLabel = "MOOD (1 = very low, 10 = great)",
                            onSave = { value, note ->
                                onSave(TrackerType.MOOD, value, null, note)
                            },
                        )
                        TrackerType.ENERGY -> ScaleInput(
                            scaleLabel = "ENERGY (1 = exhausted, 10 = peak)",
                            onSave = { value, note ->
                                onSave(TrackerType.ENERGY, value, null, note)
                            },
                        )
                        TrackerType.HYDRATION_ML -> QuantityInput(
                            label = "HYDRATION (ml)",
                            presets = listOf(250 to "Glass (250)", 500 to "Bottle (500)", 750 to "Big (750)"),
                            placeholder = "Custom ml",
                            onSave = { value, note ->
                                onSave(TrackerType.HYDRATION_ML, value.toFloat(), null, note)
                            },
                        )
                        TrackerType.CAFFEINE_MG -> QuantityInput(
                            label = "CAFFEINE (mg)",
                            presets = listOf(
                                63 to "Espresso (63)",
                                95 to "Coffee (95)",
                                47 to "Tea (47)",
                                34 to "Coke (34)",
                            ),
                            placeholder = "Custom mg",
                            onSave = { value, note ->
                                onSave(TrackerType.CAFFEINE_MG, value.toFloat(), null, note)
                            },
                        )
                        null -> Unit
                    }
                }
            }
        },
        confirmButton = {
            if (selectedType == null) {
                TextButton(onClick = onDismiss) {
                    Text("CANCEL", color = UrujMuted,
                        fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                }
            } else {
                TextButton(onClick = { selectedType = null }) {
                    Text("← BACK", color = UrujMuted,
                        fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                }
            }
        },
        containerColor = UrujSurface,
    )
}

@Composable
private fun TypeChip(type: TrackerType, onClick: () -> Unit) {
    val emoji = when (type) {
        TrackerType.MOOD -> "🎭"
        TrackerType.ENERGY -> "⚡"
        TrackerType.HYDRATION_ML -> "💧"
        TrackerType.CAFFEINE_MG -> "☕"
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(UrujSurfaceHigh)
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            "$emoji  ${type.displayName}",
            color = UrujText, fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ScaleInput(
    scaleLabel: String,
    onSave: (value: Float, note: String?) -> Unit,
) {
    var value by remember { mutableFloatStateOf(5f) }
    var note by remember { mutableStateOf("") }
    Column {
        Text(scaleLabel,
            color = UrujMuted, fontWeight = FontWeight.Black,
            fontSize = 10.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value.toInt().toString(),
                color = UrujAccent, fontWeight = FontWeight.Black,
                fontSize = 32.sp,
            )
            Text(" / 10", color = UrujMuted, fontSize = 14.sp)
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = 1f..10f,
            steps = 8,  // 1-10 with steps between
        )
        Spacer(Modifier.height(8.dp))
        Text("NOTE (OPTIONAL)",
            color = UrujMuted, fontWeight = FontWeight.Black,
            fontSize = 10.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { if (it.length <= 100) note = it },
            placeholder = {
                Text("e.g. \"foggy morning\" / \"after sun\"",
                    color = UrujMuted, fontSize = 12.sp)
            },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
            textStyle = TextStyle(color = UrujText, fontSize = 13.sp),
        )
        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = { onSave(value, note) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("SAVE ENTRY", color = UrujAccent,
                fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuantityInput(
    label: String,
    presets: List<Pair<Int, String>>,
    placeholder: String,
    onSave: (value: Int, note: String?) -> Unit,
) {
    var customText by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }

    Column {
        Text(label,
            color = UrujMuted, fontWeight = FontWeight.Black,
            fontSize = 10.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(8.dp))
        Text("Quick presets:", color = UrujMuted, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            presets.forEach { (amount, displayLabel) ->
                val isSelected = selectedPreset == amount
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isSelected) UrujAccent.copy(alpha = 0.2f) else UrujSurfaceHigh,
                        )
                        .border(
                            1.dp,
                            if (isSelected) UrujAccent else UrujSurfaceHigh,
                            RoundedCornerShape(16.dp),
                        )
                        .clickable {
                            selectedPreset = amount
                            customText = ""
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(displayLabel,
                        color = if (isSelected) UrujAccent else UrujText,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("OR CUSTOM:", color = UrujMuted, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = customText,
            onValueChange = {
                customText = it.filter { ch -> ch.isDigit() }
                if (customText.isNotEmpty()) selectedPreset = 0
            },
            placeholder = { Text(placeholder, color = UrujMuted, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(color = UrujText, fontSize = 14.sp),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        Text("NOTE (OPTIONAL)",
            color = UrujMuted, fontWeight = FontWeight.Black,
            fontSize = 10.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { if (it.length <= 100) note = it },
            placeholder = {
                Text("e.g. \"Black coffee + 1 sugar\"",
                    color = UrujMuted, fontSize = 12.sp)
            },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
            textStyle = TextStyle(color = UrujText, fontSize = 13.sp),
        )
        Spacer(Modifier.height(12.dp))
        val effectiveValue = customText.toIntOrNull() ?: selectedPreset
        TextButton(
            onClick = { if (effectiveValue > 0) onSave(effectiveValue, note) },
            enabled = effectiveValue > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (effectiveValue > 0) "SAVE $effectiveValue" else "PICK A VALUE",
                color = if (effectiveValue > 0) UrujAccent else UrujMuted,
                fontWeight = FontWeight.Black, letterSpacing = 1.5.sp,
            )
        }
    }
}
