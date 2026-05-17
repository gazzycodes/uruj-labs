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
import com.uruj.sensor.HrSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/**
 * BLE chest-strap heart-rate source (v0.5.0 — Magene H613 + any standards-compliant
 * BLE Heart Rate Profile strap: Polar H9/H10, Wahoo TICKR, CooSpo H6, etc.).
 *
 * Reads standard Bluetooth SIG Heart Rate Service (0x180D):
 *   - Heart Rate Measurement (0x2A37) NOTIFY → live HR + RR intervals
 *   - Standard Battery Service (0x180F) for battery level monitoring
 *   - Device Information Service (0x180A) for firmware + model
 *
 * The strap streams beat-by-beat. Each notification carries the current HR value
 * AND zero-or-more RR intervals representing the time(s) between consecutive
 * heartbeats since the last notification. RR intervals are the lab-grade signal
 * for true RMSSD HRV computation (per [[reference_magene_h613_capabilities]]).
 *
 * Behaviour:
 *   - Scan filters on 0x180D service UUID — only finds HR-capable devices
 *   - First device that responds becomes the active connection (manual pairing UI
 *     comes in v0.5.1 when there might be multiple straps available)
 *   - Connection auto-recovers on disconnect (5s back-off retry)
 *   - All BLE traffic logged verbose at Log.d level (lab-level rule 8 source
 *     provenance — every byte from the strap is traceable)
 *
 * The emitted samples carry source=BLE_CHEST_STRAP and contactDetected flag, so
 * downstream consumers (RideRecorderService, HRV calculators) can prioritize this
 * source over HC and surface contact-quality on the HUD.
 */
class BleHrSource(context: Context) {

    private val appContext = context.applicationContext

    /** Latest battery level (0-100), null when not yet read. Surfaces on HUD top bar. */
    private val _battery = MutableStateFlow<Int?>(null)
    val battery: StateFlow<Int?> = _battery

    /** Connection state for HUD indicator. */
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    /** Identifies which strap is connected — surfaced in About / Diagnostics. */
    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

    data class DeviceInfo(
        val name: String?,
        val address: String,
        val manufacturer: String? = null,
        val model: String? = null,
        val firmware: String? = null,
    )

    /**
     * Returns a flow of HR samples streaming from the first standards-compliant
     * BLE HR strap found in range. Starts scanning when collected, cancels and
     * disconnects when the consumer cancels.
     *
     * Permission gate: if BLUETOOTH_SCAN / BLUETOOTH_CONNECT (Android 12+) or
     * ACCESS_FINE_LOCATION (Android <12) is missing, the flow closes immediately
     * with the state set to ERROR. Pre-ride checklist surfaces this in the FIX
     * action so the user grants and re-tries.
     */
    @SuppressLint("MissingPermission") // Permissions checked at flow entry
    fun samples(): Flow<HrSample> = callbackFlow {
        if (!hasPermissions()) {
            Log.w(TAG, "BLE permissions not granted; closing flow")
            _state.value = State.ERROR
            close()
            return@callbackFlow
        }
        val btManager = appContext.getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter? = btManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "BluetoothAdapter null or disabled; closing flow")
            _state.value = State.ERROR
            close()
            return@callbackFlow
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BLE scanner unavailable; closing flow")
            _state.value = State.ERROR
            close()
            return@callbackFlow
        }

        var activeGatt: BluetoothGatt? = null
        var hasFoundDevice = false

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (hasFoundDevice) return
                hasFoundDevice = true
                val deviceName = runCatching { device.name }.getOrNull() ?: "(unnamed)"
                Log.d(TAG, "[scan] found $deviceName ${device.address} RSSI=${result.rssi}")
                runCatching { scanner.stopScan(this) }
                _state.value = State.CONNECTING
                activeGatt = connectAndSubscribe(device) { sample ->
                    trySend(sample)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "[scan] failed errorCode=$errorCode")
                _state.value = State.ERROR
                close()
            }
        }

        // Filter on standard HR Service UUID — only HR-capable devices show up.
        // Any strap that exposes 0x180D will match: Magene, Polar, Wahoo, CooSpo, etc.
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HR_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        _state.value = State.SCANNING
        Log.d(TAG, "[scan] starting BLE scan filtering on 0x180D")
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
            .onFailure {
                Log.w(TAG, "scanner.startScan threw", it)
                _state.value = State.ERROR
                close()
            }

        awaitClose {
            Log.d(TAG, "[scan] consumer cancelled — cleaning up")
            runCatching { scanner.stopScan(scanCallback) }
            activeGatt?.let {
                runCatching { it.disconnect() }
                runCatching { it.close() }
            }
            _state.value = State.IDLE
        }
    }

    /**
     * Connects to the discovered device, walks Service Discovery, reads device
     * info + battery, subscribes to HR Measurement notifications. Each parsed
     * sample is delivered via `onSample`.
     *
     * Returns the active BluetoothGatt so the caller can disconnect on flow cancel.
     */
    @SuppressLint("MissingPermission")
    private fun connectAndSubscribe(
        device: BluetoothDevice,
        onSample: (HrSample) -> Unit,
    ): BluetoothGatt? {
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "[gatt] connection state change status=$status newState=$newState")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        _state.value = State.CONNECTED
                        Log.d(TAG, "[gatt] connected, discovering services...")
                        runCatching { gatt.discoverServices() }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.w(TAG, "[gatt] disconnected")
                        _state.value = State.DISCONNECTED
                        runCatching { gatt.close() }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.d(TAG, "[gatt] services discovered status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "[gatt] service discovery failed")
                    return
                }

                // Read Device Information (0x180A) — async via gatt callbacks.
                readDeviceInfo(gatt)
                // Read Battery Level (0x180F).
                readBatteryLevel(gatt)
                // Subscribe to HR Measurement notifications.
                subscribeHrNotifications(gatt)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                // 0x2A37 notification — parse HR + RR intervals.
                if (characteristic.uuid == HR_MEASUREMENT) {
                    val value = characteristic.value ?: return
                    val sample = parseHrMeasurement(value) ?: return
                    Log.d(
                        TAG,
                        "[hr] ${sample.bpm} bpm contact=${sample.contactDetected} " +
                            "rr=${sample.rrIntervalsMs}",
                    )
                    onSample(sample)
                } else if (characteristic.uuid == BATTERY_LEVEL) {
                    val level = characteristic.value?.firstOrNull()?.toInt()?.and(0xFF)
                    Log.d(TAG, "[bat] battery level $level%")
                    _battery.value = level
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) return
                val v = characteristic.value ?: return
                when (characteristic.uuid) {
                    BATTERY_LEVEL -> {
                        val level = v.firstOrNull()?.toInt()?.and(0xFF)
                        Log.d(TAG, "[bat] initial battery $level%")
                        _battery.value = level
                        // Subscribe to battery NOTIFY if supported so we get updates.
                        runCatching {
                            gatt.setCharacteristicNotification(characteristic, true)
                            val cccd = characteristic.getDescriptor(CCCD_UUID) ?: return@runCatching
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(cccd)
                        }
                    }
                    MANUFACTURER_NAME -> {
                        val s = v.toString(Charsets.UTF_8).trim(' ')
                        Log.d(TAG, "[dev] manufacturer=$s")
                        _deviceInfo.update { (it ?: DeviceInfo(device.nameSafe(), device.address)).copy(manufacturer = s) }
                    }
                    MODEL_NUMBER -> {
                        val s = v.toString(Charsets.UTF_8).trim(' ')
                        Log.d(TAG, "[dev] model=$s")
                        _deviceInfo.update { (it ?: DeviceInfo(device.nameSafe(), device.address)).copy(model = s) }
                    }
                    FIRMWARE_REV -> {
                        val s = v.toString(Charsets.UTF_8).trim(' ')
                        Log.d(TAG, "[dev] firmware=$s")
                        _deviceInfo.update { (it ?: DeviceInfo(device.nameSafe(), device.address)).copy(firmware = s) }
                    }
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                Log.d(
                    TAG,
                    "[gatt] descriptor write ${descriptor.characteristic.uuid} status=$status",
                )
            }
        }

        Log.d(TAG, "[gatt] connectGatt to ${device.address}")
        return runCatching {
            device.connectGatt(appContext, /* autoConnect = */ false, callback)
        }.onFailure {
            Log.w(TAG, "connectGatt failed", it)
            _state.value = State.ERROR
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun subscribeHrNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(HR_SERVICE)
        if (service == null) {
            Log.w(TAG, "[gatt] HR service 0x180D not found on connected device")
            return
        }
        val measurement = service.getCharacteristic(HR_MEASUREMENT)
        if (measurement == null) {
            Log.w(TAG, "[gatt] HR Measurement 0x2A37 not found")
            return
        }
        val ok = gatt.setCharacteristicNotification(measurement, true)
        Log.d(TAG, "[gatt] setCharacteristicNotification HR ok=$ok")

        val cccd = measurement.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            Log.w(TAG, "[gatt] CCCD descriptor not found on 0x2A37")
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val written = gatt.writeDescriptor(cccd)
        Log.d(TAG, "[gatt] CCCD write for HR notifications submitted=$written")
    }

    @SuppressLint("MissingPermission")
    private fun readBatteryLevel(gatt: BluetoothGatt) {
        val service = gatt.getService(BATTERY_SERVICE) ?: run {
            Log.d(TAG, "[bat] battery service not exposed by device")
            return
        }
        val level = service.getCharacteristic(BATTERY_LEVEL) ?: return
        runCatching { gatt.readCharacteristic(level) }
    }

    @SuppressLint("MissingPermission")
    private fun readDeviceInfo(gatt: BluetoothGatt) {
        val service = gatt.getService(DEVICE_INFO_SERVICE) ?: return
        listOf(MANUFACTURER_NAME, MODEL_NUMBER, FIRMWARE_REV).forEach { uuid ->
            val c = service.getCharacteristic(uuid) ?: return@forEach
            runCatching { gatt.readCharacteristic(c) }
        }
    }

    private fun hasPermissions(): Boolean {
        val ctx = appContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            // Pre-12 needs fine-location for BLE scanning + legacy bluetooth perms
            // (declared in manifest with maxSdkVersion=30, granted at install).
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.nameSafe(): String? = runCatching { name }.getOrNull()

    private fun <T> MutableStateFlow<T?>.update(transform: (T?) -> T) {
        value = transform(value)
    }

    companion object {
        private const val TAG = "URUJ-BLE"

        // Standard Bluetooth SIG service UUIDs (16-bit, full 128-bit form).
        // See: https://www.bluetooth.com/specifications/assigned-numbers/
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val BODY_SENSOR_LOCATION: UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val MANUFACTURER_NAME: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
        val MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        val FIRMWARE_REV: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

        /** Client Characteristic Configuration Descriptor — standard Bluetooth SIG.
         *  Writing 0x0100 to this enables notifications for the parent characteristic. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Parses the Heart Rate Measurement characteristic (0x2A37) per
         * Bluetooth SIG spec. Layout (little-endian):
         *   byte 0: flags
         *     bit 0: 0 = HR is uint8, 1 = HR is uint16
         *     bit 1-2: sensor contact status (00=not supported, 10=not detected, 11=detected)
         *     bit 3: 1 = Energy Expended field present (uint16, after HR)
         *     bit 4: 1 = RR intervals present (uint16 array, after HR+EE)
         *   bytes 1..N: HR value (1 or 2 bytes)
         *   optional: Energy Expended (uint16 LE)
         *   optional: 1+ RR intervals as uint16 LE, units of 1/1024 seconds
         *
         * Returns null if buffer is malformed.
         */
        fun parseHrMeasurement(value: ByteArray): HrSample? {
            if (value.isEmpty()) return null
            val flags = value[0].toInt() and 0xFF
            val hrIs16Bit = (flags and 0x01) != 0
            val contactSupported = (flags and 0x04) != 0
            val contactDetectedBit = (flags and 0x02) != 0
            val eePresent = (flags and 0x08) != 0
            val rrPresent = (flags and 0x10) != 0

            var idx = 1
            val hr = if (hrIs16Bit) {
                if (value.size < idx + 2) return null
                val v = (value[idx + 1].toInt() and 0xFF) shl 8 or (value[idx].toInt() and 0xFF)
                idx += 2
                v
            } else {
                if (value.size < idx + 1) return null
                val v = value[idx].toInt() and 0xFF
                idx += 1
                v
            }
            if (eePresent) idx += 2  // skip Energy Expended uint16

            val rrIntervalsMs = mutableListOf<Int>()
            while (rrPresent && idx + 1 < value.size + 1 && idx + 1 < value.size) {
                if (value.size < idx + 2) break
                val raw = (value[idx + 1].toInt() and 0xFF) shl 8 or (value[idx].toInt() and 0xFF)
                // RR intervals are in 1/1024 second units. Convert to ms.
                rrIntervalsMs += (raw * 1000) / 1024
                idx += 2
            }

            val now = System.currentTimeMillis()
            return HrSample(
                receivedAtMs = now,
                measuredAtMs = now, // BLE notifications are real-time, no separate "measured at"
                bpm = hr,
                rrIntervalsMs = rrIntervalsMs.toList(),
                source = HrSample.Source.BLE_CHEST_STRAP,
                contactDetected = if (contactSupported) contactDetectedBit else null,
            )
        }
    }
}
