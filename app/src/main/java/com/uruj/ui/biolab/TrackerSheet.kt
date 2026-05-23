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
                        TrackerType.SUPPLEMENTS -> SupplementInput(
                            onSave = { name, doseMg, note ->
                                onSave(TrackerType.SUPPLEMENTS, doseMg, name, note)
                            },
                        )
                        TrackerType.BRISTOL -> BristolInput(
                            onSave = { value, note ->
                                onSave(TrackerType.BRISTOL, value.toFloat(), null, note)
                            },
                        )
                        TrackerType.SLEEP_QUALITY -> ScaleInput(
                            scaleLabel = "SLEEP QUALITY (1 = terrible, 10 = perfect)",
                            onSave = { value, note ->
                                onSave(TrackerType.SLEEP_QUALITY, value, null, note)
                            },
                        )
                        TrackerType.SORENESS -> SorenessInput(
                            onSave = { value, location, note ->
                                onSave(TrackerType.SORENESS, value, location, note)
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
        TrackerType.SUPPLEMENTS -> "💊"
        TrackerType.BRISTOL -> "🌀"
        TrackerType.SLEEP_QUALITY -> "🌙"
        TrackerType.SORENESS -> "🦵"
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

/**
 * v0.9.40 — Supplement entry input. Supplement NAME (text) is required;
 * dose (mg) is optional. Preset chips for common biohacker supplements
 * the rider has been taking (magnesium glycinate / melatonin / vitamin D
 * etc.); freeform input for anything else.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupplementInput(
    onSave: (name: String, doseMg: Float?, note: String?) -> Unit,
) {
    val presets = listOf(
        "Magnesium glycinate" to 200,
        "Melatonin" to 1,
        "Vitamin D3" to 2000,
        "Vitamin C" to 1000,
        "Omega-3" to 1000,
        "Creatine" to 5000,
        "Zinc" to 15,
        "Ashwagandha" to 600,
    )
    var name by remember { mutableStateOf("") }
    var doseText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    Column {
        Text("SUPPLEMENT", color = UrujMuted, fontWeight = FontWeight.Black,
            fontSize = 10.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(8.dp))
        Text("Quick presets:", color = UrujMuted, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            presets.forEach { (presetName, defaultDose) ->
                val isSelected = name == presetName
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
                            name = presetName
                            if (doseText.isEmpty()) doseText = defaultDose.toString()
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(presetName,
                        color = if (isSelected) UrujAccent else UrujText,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("OR CUSTOM NAME:", color = UrujMuted, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= 60) name = it },
            placeholder = { Text("e.g. \"L-Theanine\"", color = UrujMuted, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(color = UrujText, fontSize = 14.sp),
        )
        Spacer(Modifier.height(10.dp))
        Text("DOSE (mg, optional):", color = UrujMuted, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = doseText,
            onValueChange = { doseText = it.filter { ch -> ch.isDigit() }.take(6) },
            placeholder = { Text("e.g. 200", color = UrujMuted, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(color = UrujText, fontSize = 14.sp),
        )
        Spacer(Modifier.height(10.dp))
        Text("NOTE (OPTIONAL)", color = UrujMuted, fontWeight = FontWeight.Black,
            fontSize = 10.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { if (it.length <= 100) note = it },
            placeholder = { Text("e.g. \"before bed\"", color = UrujMuted, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
            textStyle = TextStyle(color = UrujText, fontSize = 13.sp),
        )
        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = {
                val trimmed = name.trim()
                if (trimmed.isNotEmpty()) {
                    onSave(trimmed, doseText.toFloatOrNull(), note)
                }
            },
            enabled = name.trim().isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (name.trim().isNotEmpty()) "SAVE" else "PICK A SUPPLEMENT",
                color = if (name.trim().isNotEmpty()) UrujAccent else UrujMuted,
                fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
        }
    }
}

/**
 * v0.9.40 — Bristol stool scale input. Classic biohacker gut-health metric.
 * 1 = hard pellets (constipation) → 4 = ideal sausage shape → 7 = liquid.
 * Single tap per day typically.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BristolInput(
    onSave: (value: Int, note: String?) -> Unit,
) {
    val descriptions = listOf(
        1 to "1 - Hard pellets",
        2 to "2 - Lumpy sausage",
        3 to "3 - Cracked sausage",
        4 to "4 - Smooth sausage ✓ ideal",
        5 to "5 - Soft blobs",
        6 to "6 - Mushy",
        7 to "7 - Liquid",
    )
    var selected by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }

    Column {
        Text("BRISTOL STOOL SCALE", color = UrujMuted, fontWeight = FontWeight.Black,
            fontSize = 10.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(4.dp))
        Text("Type 4 = ideal. Track over weeks for gut-health pattern.",
            color = UrujMuted, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        descriptions.forEach { (num, desc) ->
            val isSelected = selected == num
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) UrujAccent.copy(alpha = 0.2f) else UrujSurfaceHigh.copy(alpha = 0.5f),
                    )
                    .border(
                        1.dp,
                        if (isSelected) UrujAccent else UrujSurfaceHigh,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { selected = num }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(desc,
                    color = if (isSelected) UrujAccent else UrujText,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { if (it.length <= 100) note = it },
            placeholder = { Text("Note (optional)", color = UrujMuted, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
            textStyle = TextStyle(color = UrujText, fontSize = 13.sp),
        )
        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = { if (selected > 0) onSave(selected, note) },
            enabled = selected > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (selected > 0) "SAVE TYPE $selected" else "PICK A TYPE",
                color = if (selected > 0) UrujAccent else UrujMuted,
                fontWeight = FontWeight.Black, letterSpacing = 1.5.sp,
            )
        }
    }
}

/**
 * v0.9.40 — Soreness 1-10 + optional body location chip. Critical for athletes
 * tracking training adaptation curve. Persistent legs/back soreness signals
 * incomplete recovery or training overload.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SorenessInput(
    onSave: (value: Float, location: String?, note: String?) -> Unit,
) {
    val locations = listOf("Legs", "Lower back", "Upper back", "Shoulders", "Glutes", "Whole body")
    var value by remember { mutableFloatStateOf(5f) }
    var selectedLocation by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }

    Column {
        Text("SORENESS (1 = none, 10 = severe)",
            color = UrujMuted, fontWeight = FontWeight.Black,
            fontSize = 10.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value.toInt().toString(),
                color = UrujAccent, fontWeight = FontWeight.Black, fontSize = 32.sp)
            Text(" / 10", color = UrujMuted, fontSize = 14.sp)
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = 1f..10f,
            steps = 8,
        )
        Spacer(Modifier.height(8.dp))
        Text("LOCATION (OPTIONAL)", color = UrujMuted, fontWeight = FontWeight.Black,
            fontSize = 10.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            locations.forEach { loc ->
                val isSelected = selectedLocation == loc
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
                            selectedLocation = if (isSelected) null else loc
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(loc,
                        color = if (isSelected) UrujAccent else UrujText,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { if (it.length <= 100) note = it },
            placeholder = { Text("Note (optional)", color = UrujMuted, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
            textStyle = TextStyle(color = UrujText, fontSize = 13.sp),
        )
        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = { onSave(value, selectedLocation, note) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("SAVE ENTRY", color = UrujAccent,
                fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
        }
    }
}
