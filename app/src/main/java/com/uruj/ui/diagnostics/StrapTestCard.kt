package com.uruj.ui.diagnostics

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uruj.data.BleSettingsStore
import com.uruj.data.PairedStrap
import com.uruj.sensor.HrSample
import com.uruj.sensor.android.BleHrSource
import com.uruj.sensor.android.StrapModels
import kotlinx.coroutines.launch
import com.uruj.ui.theme.UrujOnAccent
import com.uruj.ui.theme.UrujAccent
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope

/**
 * v0.5.0 Strap Test card — embedded in Diagnostics screen so the rider can
 * verify their BLE chest strap (Magene H613 etc.) is being seen, paired, and
 * streaming HR + RR intervals before relying on it during a ride.
 *
 * Self-contained: holds a BleHrSource instance + samples Flow internally.
 * Lifecycle scoped to the composition — stops scanning + closes connection
 * when the card leaves composition.
 *
 * Per lab-level rule 8 (verbose, debuggable), shows raw RR-interval bytes
 * + contact-detected flag so the rider can audit the data stream visually.
 */
@Composable
fun StrapTestCard() {
    val context = LocalContext.current
    val source = remember { BleHrSource(context) }
    val bleStore = remember { BleSettingsStore(context) }
    val state by source.state.collectAsStateWithLifecycle()
    val battery by source.battery.collectAsStateWithLifecycle()
    val deviceInfo by source.deviceInfo.collectAsStateWithLifecycle()
    val coScope = rememberCoroutineScope()

    // v0.5.1 — current paired-strap snapshot for FORGET button + "previously
    // paired" display. Re-collects when bleStore.paired emits a new value
    // (after pair / forget).
    val paired by bleStore.paired.collectAsStateWithLifecycle(initialValue = null)

    // v0.5.1 — persist the paired strap on first successful pairing so the
    // next ride start can skip scan and direct-connect via saved MAC address.
    LaunchedEffect(source) {
        source.onPaired = { info ->
            coScope.launch {
                bleStore.save(
                    PairedStrap(
                        address = info.address,
                        name = info.name,
                        manufacturer = info.manufacturer,
                        model = info.model,
                        firmware = info.firmware,
                        lastConnectedAtMs = System.currentTimeMillis(),
                    )
                )
            }
        }
    }
    var streaming by remember { mutableStateOf(false) }
    var latest by remember { mutableStateOf<HrSample?>(null) }
    var samplesSeen by remember { mutableStateOf(0) }
    var rrTotal by remember { mutableStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) {
            streaming = true
        }
    }

    DisposableEffect(streaming) {
        if (streaming) {
            source.samples()
                .onEach { sample ->
                    latest = sample
                    samplesSeen += 1
                    rrTotal += sample.rrIntervalsMs.size
                }
                .launchIn(coScope)
        }
        onDispose { /* flow auto-cancels when scope dies */ }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UrujSurface, RoundedCornerShape(16.dp))
            .border(1.dp, UrujSurfaceHigh, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "BLE CHEST STRAP",
                color = UrujAccent,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.weight(1f))
            StateBadge(state)
        }
        Spacer(Modifier.height(8.dp))

        // v0.5.1 — show paired-device summary + FORGET button when a strap is
        // saved. RideRecorderService uses this saved MAC for direct-connect at
        // ride start (no scan), so this row is the rider's view of "what gets
        // auto-connected for my next ride."
        val pairedSnapshot = paired
        if (pairedSnapshot != null && !streaming) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UrujSurfaceHigh.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                Text(
                    "PAIRED — AUTO-CONNECTS ON RIDE START",
                    color = UrujMuted,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.sp,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(4.dp))
                // v0.6.0 — show friendly model name (e.g. "H613 (Magene)")
                // instead of the cryptic internal SKU "340" the strap reports.
                val displayName = StrapModels.friendlyName(
                    manufacturer = pairedSnapshot.manufacturer,
                    model = pairedSnapshot.model,
                ) ?: pairedSnapshot.name ?: pairedSnapshot.address
                Text(
                    text = displayName,
                    color = UrujText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
                Text(
                    text = "MAC: ${pairedSnapshot.address}",
                    color = UrujMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                pairedSnapshot.firmware?.let {
                    Text(
                        text = "Firmware: $it",
                        color = UrujMuted,
                        fontSize = 10.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                // v0.6.0 — when paired, replace the "SCAN & PAIR" button with
                // "TEST LIVE STREAM" (direct-connect via saved MAC) + FORGET.
                Row {
                    Button(
                        onClick = {
                            // Permission gate same as fresh-pair flow.
                            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                arrayOf(
                                    Manifest.permission.BLUETOOTH_SCAN,
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                )
                            } else {
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                            permLauncher.launch(perms)
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = UrujAccent,
                            contentColor = UrujOnAccent,
                        ),
                    ) {
                        Text(
                            "TEST LIVE STREAM",
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            letterSpacing = 1.5.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            coScope.launch { bleStore.forget() }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = UrujSurfaceHigh,
                            contentColor = UrujZone5,
                        ),
                    ) {
                        Text(
                            "FORGET STRAP",
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            letterSpacing = 1.5.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // v0.6.1 bug fix — when paired-but-not-streaming, the streaming view
        // below would fall through and render the IDLE "..." placeholder.
        // Now: hide everything below entirely unless actively streaming OR
        // (unpaired AND showing the SCAN & PAIR prompt).
        if (!streaming) {
            if (pairedSnapshot != null) {
                // Paired + not streaming → already showed everything in the
                // PAIRED section above. Nothing more to render here.
                return@Column
            }
            // Unpaired → show the SCAN & PAIR onboarding section below.
        }
        if (!streaming && pairedSnapshot == null) {
            Text(
                "Pair + subscribe to your chest strap (Magene H613 / Polar / Wahoo / CooSpo). " +
                    "Tap below — URUJ will scan for any standards-compliant Bluetooth Heart Rate " +
                    "Profile device and stream live HR + RR intervals.",
                color = UrujMuted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                        )
                    } else {
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    permLauncher.launch(perms)
                },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = UrujAccent,
                    contentColor = UrujOnAccent,
                ),
            ) {
                Text(
                    "🔍  SCAN & PAIR",
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    letterSpacing = 2.sp,
                )
            }
            return@Column
        }

        // Streaming view — live HR + RR + device info
        val sample = latest
        if (sample == null) {
            Text(
                when (state) {
                    BleHrSource.State.SCANNING -> "Scanning for HR Service (0x180D)..."
                    BleHrSource.State.CONNECTING -> "Connecting + discovering services..."
                    BleHrSource.State.CONNECTED -> "Connected — waiting for first beat..."
                    BleHrSource.State.ERROR -> "Error — check permissions + Bluetooth + strap"
                    else -> "..."
                },
                color = UrujMuted,
                fontSize = 12.sp,
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    sample.bpm.toString(),
                    color = UrujAccent,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    fontSize = 64.sp,
                    letterSpacing = (-2).sp,
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.padding(bottom = 14.dp)) {
                    Text(
                        "bpm",
                        color = UrujMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                    Text(
                        "live from strap",
                        color = UrujMuted,
                        fontSize = 10.sp,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            val contact = sample.contactDetected
            val contactText = when (contact) {
                true -> "✓ contact detected"
                false -> "⚠ no contact — wet electrodes / tighten strap"
                null -> "contact status not reported"
            }
            val contactColor = when (contact) {
                true -> UrujZone2
                false -> UrujZone5
                null -> UrujMuted
            }
            Text(contactText, color = contactColor, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UrujSurfaceHigh.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                Text(
                    "RR INTERVALS (THIS NOTIFICATION)",
                    color = UrujMuted,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.sp,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (sample.rrIntervalsMs.isEmpty()) {
                        "(none — flag bit 4 not set, real HRV unavailable)"
                    } else {
                        sample.rrIntervalsMs.joinToString(" · ") { "${it}ms" }
                    },
                    color = if (sample.rrIntervalsMs.isEmpty()) UrujZone5 else UrujText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Totals: $samplesSeen notifications · $rrTotal RR intervals captured",
                    color = UrujMuted,
                    fontSize = 9.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            DeviceMetaRow(deviceInfo = deviceInfo, batteryPct = battery)
        }
    }
}

@Composable
private fun StateBadge(state: BleHrSource.State) {
    val (label, color) = when (state) {
        BleHrSource.State.IDLE -> "IDLE" to UrujMuted
        BleHrSource.State.SCANNING -> "SCANNING" to UrujZone3
        BleHrSource.State.CONNECTING -> "CONNECTING" to UrujZone3
        BleHrSource.State.CONNECTED -> "CONNECTED" to UrujZone2
        BleHrSource.State.DISCONNECTED -> "DISCONNECTED" to UrujZone5
        BleHrSource.State.ERROR -> "ERROR" to UrujZone5
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 1.5.sp)
    }
}

@Composable
private fun DeviceMetaRow(
    deviceInfo: BleHrSource.DeviceInfo?,
    batteryPct: Int?,
) {
    if (deviceInfo == null && batteryPct == null) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UrujSurfaceHigh.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(
            "DEVICE",
            color = UrujMuted,
            fontWeight = FontWeight.Black,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(4.dp))
        deviceInfo?.name?.let {
            Text("name: $it", color = UrujText, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        deviceInfo?.let {
            Text("address: ${it.address}", color = UrujMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            it.manufacturer?.let { m -> Text("manufacturer: $m", color = UrujText, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            it.model?.let { m -> Text("model: $m", color = UrujText, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            it.firmware?.let { fw -> Text("firmware: $fw", color = UrujText, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
        batteryPct?.let {
            Text("battery: $it%", color = UrujText, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}
