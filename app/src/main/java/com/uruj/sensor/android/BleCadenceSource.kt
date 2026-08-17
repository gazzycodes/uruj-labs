package com.uruj.sensor.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.uruj.power.CadenceTracker
import com.uruj.power.CscParser
import com.uruj.power.CscSensorLocation
import com.uruj.sensor.CadenceSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/**
 * v0.9.78 — BLE cadence source for the Magene S314 (and any standards-compliant
 * Cycling Speed and Cadence sensor: Garmin, Wahoo RPM, CooSpo, Xoss).
 *
 * Reads the Bluetooth SIG **Cycling Speed and Cadence service (0x1816)**:
 *   - CSC Measurement (0x2A5B) NOTIFY → cumulative crank revs + event time
 *   - CSC Feature (0x2A5C) READ      → does it actually do crank data?
 *   - Sensor Location (0x2A5D) READ  → "Left crank" etc., shown in Diagnostics
 *   - Battery Service (0x180F)       → battery level + notify
 *   - Device Information (0x180A)    → manufacturer / model / firmware
 *
 * Deliberately a sibling of [BleHrSource] rather than a generalisation of it:
 * the chained one-GATT-op-at-a-time state machine is identical (Android only
 * allows a single outstanding GATT operation; ops submitted while another is in
 * flight silently return false), but the connection *policy* differs in one
 * important way, below.
 *
 * ## Why `autoConnect = true` here and `false` for the HR strap
 *
 * A chest strap is worn continuously and is awake whenever the rider is; a fast
 * direct connect is what you want. A crank cadence sensor is the opposite: it
 * sleeps within minutes of the bike being parked and stops advertising
 * entirely. With `autoConnect = false` every reconnect attempt burns a ~30 s
 * GATT timeout against a device that simply is not listening, so the sensor
 * would appear "dead" for up to a minute after the rider starts pedalling again.
 *
 * `autoConnect = true` hands that waiting to the Bluetooth controller: the
 * connection is parked and completes the instant the sensor wakes and
 * advertises — which is exactly the moment the crank turns. It is slower on a
 * cold first connect and costs nothing while parked, which is the correct
 * trade for a device that spends most of its life asleep.
 *
 * ## Ownership of the cadence math
 *
 * This class owns the single [CadenceTracker] for the app. Both consumers (the
 * ride recorder's 1 Hz tick and the Diagnostics card) read rpm through it, so
 * there is exactly one place where "what cadence is it right now" is decided —
 * same single-source-of-truth rule as [com.uruj.power.TrainingLoad] and
 * [com.uruj.power.HrvReadiness].
 */
class BleCadenceSource(context: Context) {

    private val appContext = context.applicationContext

    /** Sensor battery level (0-100), null when not yet read. */
    private val _battery = MutableStateFlow<Int?>(null)
    val battery: StateFlow<Int?> = _battery

    /** Connection state for the HUD chip + Diagnostics badge. */
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    /** Identifies the connected sensor — surfaced in Diagnostics. */
    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo

    /**
     * The one cadence tracker. Public so the ride service's 1 Hz ticker can ask
     * `currentRpm(now)` without waiting for a notification — cadence sensors go
     * silent while coasting, so a readout driven only by packet arrival would
     * freeze at the last pedalling value.
     */
    val tracker = CadenceTracker()

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

    data class DeviceInfo(
        val name: String?,
        val address: String,
        val manufacturer: String? = null,
        val model: String? = null,
        val firmware: String? = null,
        /** Human-readable mount point from Sensor Location (0x2A5D). */
        val sensorLocation: String? = null,
        /** True when CSC Feature (0x2A5C) advertises crank-revolution support. */
        val supportsCrankData: Boolean? = null,
    )

    /** Stages of the chained-setup state machine. One GATT op in flight at a time. */
    private enum class Stage {
        IDLE,
        READING_MANUFACTURER,
        READING_MODEL,
        READING_FIRMWARE,
        READING_CSC_FEATURE,
        READING_SENSOR_LOCATION,
        READING_BATTERY_LEVEL,
        SUBSCRIBING_BATTERY,
        SUBSCRIBING_CSC,
        READY,
    }

    /** Fired once the setup chain completes, so the caller can persist the pairing. */
    var onPaired: ((DeviceInfo) -> Unit)? = null

    /**
     * Flow of cadence samples.
     *
     * @param directAddress when non-null, skip the scan and connect straight to
     *   this MAC (the normal path once paired — the sensor may well be asleep
     *   and not advertising, which makes a scan useless but a parked
     *   `autoConnect` connection perfect).
     */
    @SuppressLint("MissingPermission") // Permissions checked at flow entry
    fun samples(directAddress: String? = null): Flow<CadenceSample> = callbackFlow {
        if (!hasPermissions()) {
            Log.w(TAG, "BLE permissions not granted; closing cadence flow")
            _state.value = State.ERROR
            close()
            return@callbackFlow
        }
        val btManager = appContext.getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter? = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "BluetoothAdapter null or disabled; closing cadence flow")
            _state.value = State.ERROR
            close()
            return@callbackFlow
        }

        // Counters restart on every connection — the sensor's own cumulative
        // values are only meaningful within one session.
        tracker.reset()

        var activeGatt: BluetoothGatt? = null

        if (directAddress != null) {
            val remote = runCatching { adapter.getRemoteDevice(directAddress) }.getOrNull()
            if (remote != null) {
                Log.d(TAG, "[cad] direct-connect (autoConnect) to $directAddress")
                _state.value = State.CONNECTING
                activeGatt = connectAndSubscribe(
                    device = remote,
                    onDisconnect = { close() },
                    onSample = { sample -> trySend(sample) },
                )
                awaitClose {
                    Log.d(TAG, "[cad] consumer cancelled — cleaning up GATT")
                    activeGatt?.let {
                        runCatching { it.disconnect() }
                        runCatching { it.close() }
                    }
                    _state.value = State.IDLE
                }
                return@callbackFlow
            }
            Log.w(TAG, "[cad] getRemoteDevice($directAddress) returned null — falling back to scan")
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "[cad] BLE scanner unavailable; closing flow")
            _state.value = State.ERROR
            close()
            return@callbackFlow
        }

        var hasFoundDevice = false
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (hasFoundDevice) return
                hasFoundDevice = true
                val device = result.device
                val deviceName = runCatching { device.name }.getOrNull() ?: "(unnamed)"
                Log.d(TAG, "[cad] scan found $deviceName ${device.address} RSSI=${result.rssi}")
                runCatching { scanner.stopScan(this) }
                _state.value = State.CONNECTING
                activeGatt = connectAndSubscribe(
                    device = device,
                    onDisconnect = { close() },
                    onSample = { sample -> trySend(sample) },
                )
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "[cad] scan failed errorCode=$errorCode")
                _state.value = State.ERROR
                close()
            }
        }

        // Filter on the CSC service UUID so only cadence/speed sensors appear —
        // the rider's chest strap and every other BLE device stay out of the list.
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(CSC_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        _state.value = State.SCANNING
        Log.d(TAG, "[cad] starting BLE scan filtering on 0x1816")
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
            .onFailure {
                Log.w(TAG, "[cad] scanner.startScan threw", it)
                _state.value = State.ERROR
                close()
            }

        awaitClose {
            Log.d(TAG, "[cad] scan consumer cancelled — cleaning up")
            runCatching { scanner.stopScan(scanCallback) }
            activeGatt?.let {
                runCatching { it.disconnect() }
                runCatching { it.close() }
            }
            _state.value = State.IDLE
        }
    }

    /**
     * Connects, discovers services, then walks the GATT ops one at a time —
     * device info → CSC feature → sensor location → battery → subscribe battery
     * → subscribe CSC. Each op completes in a callback which advances [Stage]
     * and submits the next. Missing characteristics are skipped, never fatal:
     * plenty of cheap sensors omit the Device Information service entirely and
     * still stream perfectly good cadence.
     */
    @SuppressLint("MissingPermission")
    private fun connectAndSubscribe(
        device: BluetoothDevice,
        onDisconnect: () -> Unit,
        onSample: (CadenceSample) -> Unit,
    ): BluetoothGatt? {
        var stage: Stage = Stage.IDLE

        fun readOrSkip(gatt: BluetoothGatt, service: UUID, characteristic: UUID, next: Stage): Boolean {
            val c = gatt.getService(service)?.getCharacteristic(characteristic)
            if (c == null) {
                stage = next
                return false
            }
            val ok = runCatching { gatt.readCharacteristic(c) }.getOrDefault(false)
            if (!ok) stage = next
            return ok
        }

        fun requestNextStep(gatt: BluetoothGatt) {
            when (stage) {
                Stage.READING_MANUFACTURER ->
                    if (!readOrSkip(gatt, DEVICE_INFO_SERVICE, MANUFACTURER_NAME, Stage.READING_MODEL)) {
                        requestNextStep(gatt)
                    }
                Stage.READING_MODEL ->
                    if (!readOrSkip(gatt, DEVICE_INFO_SERVICE, MODEL_NUMBER, Stage.READING_FIRMWARE)) {
                        requestNextStep(gatt)
                    }
                Stage.READING_FIRMWARE ->
                    if (!readOrSkip(gatt, DEVICE_INFO_SERVICE, FIRMWARE_REV, Stage.READING_CSC_FEATURE)) {
                        requestNextStep(gatt)
                    }
                Stage.READING_CSC_FEATURE ->
                    if (!readOrSkip(gatt, CSC_SERVICE, CSC_FEATURE, Stage.READING_SENSOR_LOCATION)) {
                        requestNextStep(gatt)
                    }
                Stage.READING_SENSOR_LOCATION ->
                    if (!readOrSkip(gatt, CSC_SERVICE, SENSOR_LOCATION, Stage.READING_BATTERY_LEVEL)) {
                        requestNextStep(gatt)
                    }
                Stage.READING_BATTERY_LEVEL ->
                    if (!readOrSkip(gatt, BATTERY_SERVICE, BATTERY_LEVEL, Stage.SUBSCRIBING_CSC)) {
                        requestNextStep(gatt)
                    }
                Stage.SUBSCRIBING_BATTERY -> {
                    val c = gatt.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)
                    val cccd = c?.getDescriptor(CCCD_UUID)
                    if (c == null || cccd == null) {
                        // Read-only battery is normal on cheap sensors — fine.
                        stage = Stage.SUBSCRIBING_CSC
                        requestNextStep(gatt)
                        return
                    }
                    runCatching {
                        gatt.setCharacteristicNotification(c, true)
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(cccd)
                    }.onFailure {
                        Log.w(TAG, "[cad] battery NOTIFY subscribe failed", it)
                        stage = Stage.SUBSCRIBING_CSC
                        requestNextStep(gatt)
                    }
                }
                Stage.SUBSCRIBING_CSC -> {
                    val measurement = gatt.getService(CSC_SERVICE)?.getCharacteristic(CSC_MEASUREMENT)
                    if (measurement == null) {
                        Log.w(TAG, "[cad] CSC Measurement characteristic missing — bail")
                        stage = Stage.READY
                        return
                    }
                    val notifyOk = gatt.setCharacteristicNotification(measurement, true)
                    Log.d(TAG, "[cad] setCharacteristicNotification CSC ok=$notifyOk")
                    val cccd = measurement.getDescriptor(CCCD_UUID)
                    if (cccd == null) {
                        Log.w(TAG, "[cad] CCCD missing on CSC Measurement")
                        stage = Stage.READY
                        return
                    }
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val written = runCatching { gatt.writeDescriptor(cccd) }.getOrDefault(false)
                    Log.d(TAG, "[cad] CSC CCCD write submitted=$written")
                }
                Stage.READY, Stage.IDLE -> { /* nothing to do */ }
            }
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "[cad] connection state change status=$status newState=$newState")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        _state.value = State.CONNECTED
                        runCatching { gatt.discoverServices() }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.w(TAG, "[cad] disconnected")
                        _state.value = State.DISCONNECTED
                        runCatching { gatt.close() }
                        onDisconnect()
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.d(TAG, "[cad] services discovered status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "[cad] service discovery failed")
                    return
                }
                stage = Stage.READING_MANUFACTURER
                requestNextStep(gatt)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                when (characteristic.uuid) {
                    CSC_MEASUREMENT -> {
                        val value = characteristic.value ?: return
                        val measurement = CscParser.parseMeasurement(value) ?: run {
                            Log.w(TAG, "[cad] undecodable CSC packet (${value.size} bytes)")
                            return
                        }
                        val now = System.currentTimeMillis()
                        val rpm = tracker.onMeasurement(measurement, now)
                        if (rpm == null) {
                            // Wheel-only packet: the sensor is in SPEED mode.
                            // Surface it rather than showing a silent dead dash.
                            Log.d(TAG, "[cad] wheel-only packet — sensor is not in cadence mode")
                            onSample(
                                CadenceSample(
                                    receivedAtMs = now,
                                    cadenceRpm = 0f,
                                    cumulativeCrankRevs = tracker.totalCrankRevs,
                                    hasCrankData = false,
                                ),
                            )
                            return
                        }
                        onSample(
                            CadenceSample(
                                receivedAtMs = now,
                                cadenceRpm = rpm,
                                cumulativeCrankRevs = tracker.totalCrankRevs,
                                hasCrankData = true,
                            ),
                        )
                    }
                    BATTERY_LEVEL -> {
                        val level = characteristic.value?.firstOrNull()?.toInt()?.and(0xFF)
                        Log.d(TAG, "[cad] battery NOTIFY level=$level%")
                        _battery.value = level
                    }
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                val ok = status == BluetoothGatt.GATT_SUCCESS
                val v = characteristic.value
                fun info(): DeviceInfo =
                    _deviceInfo.value ?: DeviceInfo(device.nameSafe(), device.address)
                when (characteristic.uuid) {
                    MANUFACTURER_NAME -> {
                        if (ok && v != null) {
                            _deviceInfo.value = info().copy(manufacturer = v.asText())
                        }
                        stage = Stage.READING_MODEL
                        requestNextStep(gatt)
                    }
                    MODEL_NUMBER -> {
                        if (ok && v != null) {
                            _deviceInfo.value = info().copy(model = v.asText())
                        }
                        stage = Stage.READING_FIRMWARE
                        requestNextStep(gatt)
                    }
                    FIRMWARE_REV -> {
                        if (ok && v != null) {
                            _deviceInfo.value = info().copy(firmware = v.asText())
                        }
                        stage = Stage.READING_CSC_FEATURE
                        requestNextStep(gatt)
                    }
                    CSC_FEATURE -> {
                        if (ok && v != null) {
                            val feature = CscParser.parseFeature(v)
                            Log.d(TAG, "[cad] CSC feature crank=${feature?.supportsCrankRevolutions}")
                            _deviceInfo.value =
                                info().copy(supportsCrankData = feature?.supportsCrankRevolutions)
                        }
                        stage = Stage.READING_SENSOR_LOCATION
                        requestNextStep(gatt)
                    }
                    SENSOR_LOCATION -> {
                        if (ok && v != null) {
                            val label = CscSensorLocation.label(CscParser.parseSensorLocation(v))
                            Log.d(TAG, "[cad] sensor location=$label")
                            _deviceInfo.value = info().copy(sensorLocation = label)
                        }
                        stage = Stage.READING_BATTERY_LEVEL
                        requestNextStep(gatt)
                    }
                    BATTERY_LEVEL -> {
                        if (ok && v != null) {
                            _battery.value = v.firstOrNull()?.toInt()?.and(0xFF)
                            Log.d(TAG, "[cad] initial battery ${_battery.value}%")
                        }
                        stage = Stage.SUBSCRIBING_BATTERY
                        requestNextStep(gatt)
                    }
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                Log.d(TAG, "[cad] descriptor write ${descriptor.characteristic.uuid} status=$status")
                when (descriptor.characteristic.uuid) {
                    BATTERY_LEVEL -> {
                        stage = Stage.SUBSCRIBING_CSC
                        requestNextStep(gatt)
                    }
                    CSC_MEASUREMENT -> {
                        stage = Stage.READY
                        Log.d(TAG, "[cad] setup chain complete — cadence notifications active")
                        _deviceInfo.value?.let { info -> onPaired?.invoke(info) }
                    }
                }
            }
        }

        Log.d(TAG, "[cad] connectGatt to ${device.address} (autoConnect=true)")
        return runCatching {
            // autoConnect = true — see the class KDoc. The crank sensor sleeps;
            // a parked connection wakes with it instead of timing out at it.
            device.connectGatt(appContext, /* autoConnect = */ true, callback)
        }.onFailure {
            Log.w(TAG, "[cad] connectGatt failed", it)
            _state.value = State.ERROR
        }.getOrNull()
    }

    private fun hasPermissions(): Boolean {
        val ctx = appContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.nameSafe(): String? = runCatching { name }.getOrNull()

    /** Device Information strings are often space-padded to a fixed width. */
    private fun ByteArray.asText(): String = toString(Charsets.UTF_8).trim(' ')

    companion object {
        private const val TAG = "URUJ-BLE-CAD"

        /** Cycling Speed and Cadence service + its characteristics (Bluetooth SIG). */
        val CSC_SERVICE: UUID = UUID.fromString("00001816-0000-1000-8000-00805f9b34fb")
        val CSC_MEASUREMENT: UUID = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")
        val CSC_FEATURE: UUID = UUID.fromString("00002a5c-0000-1000-8000-00805f9b34fb")
        val SENSOR_LOCATION: UUID = UUID.fromString("00002a5d-0000-1000-8000-00805f9b34fb")

        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val MANUFACTURER_NAME: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
        val MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        val FIRMWARE_REV: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

        /** Standard CCCD — writing 0x0100 enables notifications. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
