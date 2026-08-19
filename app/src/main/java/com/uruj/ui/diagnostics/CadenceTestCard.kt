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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uruj.data.BleSettingsStore
import com.uruj.data.PairedBleDevice
import com.uruj.sensor.CadenceSample
import com.uruj.sensor.android.BleCadenceSource
import com.uruj.ui.theme.UrujOnAccent
import com.uruj.ui.theme.UrujCadence
import com.uruj.ui.theme.UrujMuted
import com.uruj.ui.theme.UrujSurface
import com.uruj.ui.theme.UrujSurfaceHigh
import com.uruj.ui.theme.UrujText
import com.uruj.ui.theme.UrujZone2
import com.uruj.ui.theme.UrujZone3
import com.uruj.ui.theme.UrujZone5
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * v0.9.78 — Cadence sensor pair + test card, sibling of [StrapTestCard].
 *
 * This is where the Magene S314 gets paired, and — more usefully — where it gets
 * *audited*. Cadence has a specific failure mode that a bare rpm number hides:
 * a dual-mode sensor mounted on the crank but still configured as a SPEED sensor
 * streams perfectly valid packets that contain no crank data at all. So this
 * card shows the sensor's own answers — CSC Feature bits, Sensor Location
 * ("Left crank"), live crank revolutions — rather than only the derived number.
 * If cadence is ever wrong on the road, this screen says which layer lied.
 */
@Composable
fun CadenceTestCard() {
    val context = LocalContext.current
    val source = remember { BleCadenceSource(context) }
    val bleStore = remember { BleSettingsStore(context) }
    val state by source.state.collectAsStateWithLifecycle()
    val battery by source.battery.collectAsStateWithLifecycle()
    val deviceInfo by source.deviceInfo.collectAsStateWithLifecycle()
    val coScope = rememberCoroutineScope()

    val paired by bleStore.pairedCadence.collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(source) {
        source.onPaired = { info ->
            coScope.launch {
                bleStore.saveCadence(
                    PairedBleDevice(
                        address = info.address,
                        name = info.name,
                        manufacturer = info.manufacturer,
                        model = info.model,
                        firmware = info.firmware,
                        lastConnectedAtMs = System.currentTimeMillis(),
                        lastBatteryPct = source.battery.value,
                        lastBatteryAtMs = source.battery.value?.let { System.currentTimeMillis() },
                    ),
                )
            }
        }
    }

    var streaming by remember { mutableStateOf(false) }
    var latest by remember { mutableStateOf<CadenceSample?>(null) }
    var packetsSeen by remember { mutableStateOf(0) }
    var peakRpm by remember { mutableStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted -> if (granted.values.all { it }) streaming = true }

    DisposableEffect(streaming) {
        if (streaming) {
            // Direct-connect through the saved MAC when we have one: a sleeping
            // cadence sensor does not advertise, so a scan can find nothing at
            // all while an autoConnect attaches the moment the crank turns.
            source.samples(directAddress = paired?.address)
                .onEach { sample ->
                    latest = sample
                    packetsSeen += 1
                    val rpm = sample.cadenceRpm.toInt()
                    if (rpm > peakRpm) peakRpm = rpm
                }
                .launchIn(coScope)
        }
        onDispose { /* flow auto-cancels when the scope dies */ }
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
                "BLE CADENCE SENSOR",
                color = UrujCadence,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.weight(1f))
            CadenceStateBadge(state)
        }
        Spacer(Modifier.height(8.dp))

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
                Text(
                    text = pairedSnapshot.model ?: pairedSnapshot.name ?: pairedSnapshot.address,
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
                    Text("Firmware: $it", color = UrujMuted, fontSize = 10.sp)
                }
                // Last-seen battery, so a sensor that finished the last ride at
                // 6% gets caught in the kitchen instead of at km 40.
                pairedSnapshot.lastBatteryPct?.let { pct ->
                    val color = when {
                        pct >= 30 -> UrujMuted
                        pct >= 15 -> UrujZone3
                        else -> UrujZone5
                    }
                    Text(
                        text = "Battery at last connect: $pct%" +
                            if (pct < 20) "  ⚠ replace the CR2032 soon" else "",
                        color = color,
                        fontWeight = if (pct < 20) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 10.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(
                        onClick = { permLauncher.launch(blePermissions()) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = UrujCadence,
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
                        onClick = { coScope.launch { bleStore.forgetCadence() } },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = UrujSurfaceHigh,
                            contentColor = UrujZone5,
                        ),
                    ) {
                        Text(
                            "FORGET",
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            letterSpacing = 1.5.sp,
                        )
                    }
                }
            }
            return@Column
        }

        if (!streaming) {
            Text(
                "Pair your crank cadence sensor (Magene S314 / Garmin / Wahoo RPM / " +
                    "CooSpo / Xoss). URUJ scans for the standard Cycling Speed and " +
                    "Cadence service (0x1816). Spin the crank a few turns first — " +
                    "these sensors sleep when the bike is parked and only advertise " +
                    "once they feel movement.",
                color = UrujMuted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { permLauncher.launch(blePermissions()) },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = UrujCadence,
                    contentColor = UrujOnAccent,
                ),
            ) {
                Text(
                    "🔍  SCAN & PAIR CADENCE",
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    letterSpacing = 2.sp,
                )
            }
            return@Column
        }

        // ── streaming view ──
        val sample = latest
        if (sample == null) {
            Text(
                when (state) {
                    BleCadenceSource.State.SCANNING -> "Scanning for CSC service (0x1816)…"
                    BleCadenceSource.State.CONNECTING ->
                        "Connecting… spin the crank to wake the sensor."
                    BleCadenceSource.State.CONNECTED -> "Connected — waiting for the first crank event…"
                    BleCadenceSource.State.ERROR -> "Error — check permissions + Bluetooth + sensor battery"
                    else -> "…"
                },
                color = UrujMuted,
                fontSize = 12.sp,
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                sample.cadenceRpm.toInt().toString(),
                color = UrujCadence,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                fontSize = 64.sp,
                letterSpacing = (-2).sp,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.padding(bottom = 14.dp)) {
                Text("rpm", color = UrujMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("live from crank", color = UrujMuted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(6.dp))

        if (!sample.hasCrankData) {
            Text(
                "⚠ This sensor is streaming WHEEL data only — it is configured as a " +
                    "SPEED sensor, not a cadence sensor. Re-pair it in the Magene app " +
                    "(or re-mount it on the crank and let it re-detect) to get cadence.",
                color = UrujZone5,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(UrujSurfaceHigh.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(10.dp),
        ) {
            Text(
                "RAW CSC STREAM",
                color = UrujMuted,
                fontWeight = FontWeight.Black,
                fontSize = 9.sp,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(2.dp))
            MonoLine("crank revolutions: ${sample.cumulativeCrankRevs}")
            MonoLine("crank data present: ${if (sample.hasCrankData) "yes" else "NO (wheel mode)"}")
            MonoLine("packets: $packetsSeen · peak: $peakRpm rpm")
            Spacer(Modifier.height(4.dp))
            Text(
                "Cadence = crank revolutions ÷ the sensor's own event-time clock, " +
                    "measured over a ${com.uruj.power.CadenceTracker.WINDOW_MS / 1000f}s window. " +
                    "0 rpm means freewheeling, not missing data.",
                color = UrujMuted,
                fontSize = 9.sp,
            )
        }

        Spacer(Modifier.height(8.dp))
        CadenceDeviceMetaRow(deviceInfo = deviceInfo, batteryPct = battery)
    }
}

@Composable
private fun MonoLine(text: String) {
    Text(text, color = UrujText, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
}

@Composable
private fun CadenceStateBadge(state: BleCadenceSource.State) {
    val (label, color) = when (state) {
        BleCadenceSource.State.IDLE -> "IDLE" to UrujMuted
        BleCadenceSource.State.SCANNING -> "SCANNING" to UrujZone3
        BleCadenceSource.State.CONNECTING -> "CONNECTING" to UrujZone3
        BleCadenceSource.State.CONNECTED -> "CONNECTED" to UrujZone2
        BleCadenceSource.State.DISCONNECTED -> "DISCONNECTED" to UrujZone5
        BleCadenceSource.State.ERROR -> "ERROR" to UrujZone5
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontWeight = FontWeight.Black, fontSize = 10.sp, letterSpacing = 1.5.sp)
    }
}

@Composable
private fun CadenceDeviceMetaRow(
    deviceInfo: BleCadenceSource.DeviceInfo?,
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
        deviceInfo?.let {
            it.name?.let { n -> MonoLine("name: $n") }
            MonoLine("address: ${it.address}")
            it.manufacturer?.let { m -> MonoLine("manufacturer: $m") }
            it.model?.let { m -> MonoLine("model: $m") }
            it.firmware?.let { f -> MonoLine("firmware: $f") }
            // The sensor's own claim about where it is bolted — the fastest way
            // to confirm a crank mount really registered as a crank mount.
            it.sensorLocation?.let { loc -> MonoLine("mounted at: $loc") }
            it.supportsCrankData?.let { c -> MonoLine("crank data supported: ${if (c) "yes" else "no"}") }
        }
        batteryPct?.let { MonoLine("battery: $it%") }
    }
}

private fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
