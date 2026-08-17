package com.uruj.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists paired BLE devices so URUJ can auto-reconnect on ride start without
 * re-scanning every time. v0.5.1 introduction (chest strap) — once the rider
 * pairs their Magene H613 (or Polar / Wahoo / CooSpo) via Diagnostics
 * SCAN & PAIR, the MAC address is saved here. RideRecorderService reads it
 * at startRecording and skips scan, going direct to `connectGatt`.
 *
 * "Forget" from Diagnostics clears the saved address for that device only.
 * The next ride falls back to scan-and-pair.
 *
 * v0.9.78 — a second device slot for the **cadence sensor** (Magene S314 on the
 * crank, CSC service 0x1816). Same shape, separate key namespace, so pairing or
 * forgetting one device can never disturb the other. That independence is why
 * `forget()` now clears its own keys explicitly instead of wiping the whole
 * preferences file the way it did when a strap was the only thing stored.
 *
 * Stored per device: MAC, last-known name, manufacturer/model/firmware (for
 * About-section transparency), last-connected-at timestamp, and last-seen
 * battery level (so the pre-ride view can warn about a sensor that finished the
 * last ride at 6% instead of dying halfway through the next one).
 */
data class PairedBleDevice(
    val address: String,
    val name: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val firmware: String? = null,
    val lastConnectedAtMs: Long? = null,
    /** Battery level read at the last successful connection, 0-100. */
    val lastBatteryPct: Int? = null,
    /** When [lastBatteryPct] was read — a stale reading should read as stale. */
    val lastBatteryAtMs: Long? = null,
)

/**
 * The chest strap keeps its original name at every existing call site; it is the
 * same record shape as any other paired BLE device.
 */
typealias PairedStrap = PairedBleDevice

private val Context.bleSettingsDataStore: DataStore<Preferences> by preferencesDataStore("ble_settings")

class BleSettingsStore(context: Context) {
    private val dataStore = context.applicationContext.bleSettingsDataStore

    // ───────────────────────── chest strap (HR, 0x180D) ─────────────────────────

    val paired: Flow<PairedStrap?> = dataStore.data.map { prefs -> prefs.readStrap() }

    suspend fun current(): PairedStrap? = paired.first()

    /** Called when scan-and-pair finds the strap and successfully reads device info. */
    suspend fun save(strap: PairedStrap) {
        dataStore.edit { prefs ->
            prefs[KEY_ADDRESS] = strap.address
            strap.name?.let { prefs[KEY_NAME] = it }
            strap.manufacturer?.let { prefs[KEY_MANUFACTURER] = it }
            strap.model?.let { prefs[KEY_MODEL] = it }
            strap.firmware?.let { prefs[KEY_FIRMWARE] = it }
            strap.lastConnectedAtMs?.let { prefs[KEY_LAST_CONNECTED_AT_MS] = it }
            strap.lastBatteryPct?.let { prefs[KEY_BATTERY_PCT] = it }
            strap.lastBatteryAtMs?.let { prefs[KEY_BATTERY_AT_MS] = it }
        }
    }

    /** Update only the last-connected-at timestamp on each successful reconnect. */
    suspend fun touchLastConnected() {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_CONNECTED_AT_MS] = System.currentTimeMillis()
        }
    }

    /** Record the strap's battery level so the rider can see it before a ride. */
    suspend fun saveStrapBattery(pct: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_BATTERY_PCT] = pct
            prefs[KEY_BATTERY_AT_MS] = System.currentTimeMillis()
        }
    }

    /** "Forget Strap" — clears the strap slot only; the cadence pairing survives. */
    suspend fun forget() {
        dataStore.edit { prefs ->
            STRAP_KEYS.forEach { prefs.remove(it) }
        }
    }

    // ─────────────────────── cadence sensor (CSC, 0x1816) ───────────────────────

    val pairedCadence: Flow<PairedBleDevice?> = dataStore.data.map { prefs -> prefs.readCadence() }

    suspend fun currentCadence(): PairedBleDevice? = pairedCadence.first()

    suspend fun saveCadence(sensor: PairedBleDevice) {
        dataStore.edit { prefs ->
            prefs[KEY_CAD_ADDRESS] = sensor.address
            sensor.name?.let { prefs[KEY_CAD_NAME] = it }
            sensor.manufacturer?.let { prefs[KEY_CAD_MANUFACTURER] = it }
            sensor.model?.let { prefs[KEY_CAD_MODEL] = it }
            sensor.firmware?.let { prefs[KEY_CAD_FIRMWARE] = it }
            sensor.lastConnectedAtMs?.let { prefs[KEY_CAD_LAST_CONNECTED_AT_MS] = it }
            sensor.lastBatteryPct?.let { prefs[KEY_CAD_BATTERY_PCT] = it }
            sensor.lastBatteryAtMs?.let { prefs[KEY_CAD_BATTERY_AT_MS] = it }
        }
    }

    suspend fun touchCadenceLastConnected() {
        dataStore.edit { prefs ->
            prefs[KEY_CAD_LAST_CONNECTED_AT_MS] = System.currentTimeMillis()
        }
    }

    /**
     * Record the cadence sensor's battery. Called once per connection (not per
     * notification) — a DataStore write per BLE packet would be absurd I/O for a
     * number that moves a few percent a month.
     */
    suspend fun saveCadenceBattery(pct: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_CAD_BATTERY_PCT] = pct
            prefs[KEY_CAD_BATTERY_AT_MS] = System.currentTimeMillis()
        }
    }

    /** "Forget cadence sensor" — clears the cadence slot only. */
    suspend fun forgetCadence() {
        dataStore.edit { prefs ->
            CADENCE_KEYS.forEach { prefs.remove(it) }
        }
    }

    // ───────────────────────────────── mapping ─────────────────────────────────

    private fun Preferences.readStrap(): PairedStrap? {
        val address = this[KEY_ADDRESS] ?: return null
        return PairedBleDevice(
            address = address,
            name = this[KEY_NAME],
            manufacturer = this[KEY_MANUFACTURER],
            model = this[KEY_MODEL],
            firmware = this[KEY_FIRMWARE],
            lastConnectedAtMs = this[KEY_LAST_CONNECTED_AT_MS],
            lastBatteryPct = this[KEY_BATTERY_PCT],
            lastBatteryAtMs = this[KEY_BATTERY_AT_MS],
        )
    }

    private fun Preferences.readCadence(): PairedBleDevice? {
        val address = this[KEY_CAD_ADDRESS] ?: return null
        return PairedBleDevice(
            address = address,
            name = this[KEY_CAD_NAME],
            manufacturer = this[KEY_CAD_MANUFACTURER],
            model = this[KEY_CAD_MODEL],
            firmware = this[KEY_CAD_FIRMWARE],
            lastConnectedAtMs = this[KEY_CAD_LAST_CONNECTED_AT_MS],
            lastBatteryPct = this[KEY_CAD_BATTERY_PCT],
            lastBatteryAtMs = this[KEY_CAD_BATTERY_AT_MS],
        )
    }

    companion object {
        private val KEY_ADDRESS = stringPreferencesKey("paired_address")
        private val KEY_NAME = stringPreferencesKey("paired_name")
        private val KEY_MANUFACTURER = stringPreferencesKey("paired_manufacturer")
        private val KEY_MODEL = stringPreferencesKey("paired_model")
        private val KEY_FIRMWARE = stringPreferencesKey("paired_firmware")
        private val KEY_LAST_CONNECTED_AT_MS = longPreferencesKey("paired_last_connected_at_ms")
        private val KEY_BATTERY_PCT = intPreferencesKey("paired_battery_pct")
        private val KEY_BATTERY_AT_MS = longPreferencesKey("paired_battery_at_ms")

        private val KEY_CAD_ADDRESS = stringPreferencesKey("cadence_address")
        private val KEY_CAD_NAME = stringPreferencesKey("cadence_name")
        private val KEY_CAD_MANUFACTURER = stringPreferencesKey("cadence_manufacturer")
        private val KEY_CAD_MODEL = stringPreferencesKey("cadence_model")
        private val KEY_CAD_FIRMWARE = stringPreferencesKey("cadence_firmware")
        private val KEY_CAD_LAST_CONNECTED_AT_MS = longPreferencesKey("cadence_last_connected_at_ms")
        private val KEY_CAD_BATTERY_PCT = intPreferencesKey("cadence_battery_pct")
        private val KEY_CAD_BATTERY_AT_MS = longPreferencesKey("cadence_battery_at_ms")

        private val STRAP_KEYS: List<Preferences.Key<*>> = listOf(
            KEY_ADDRESS, KEY_NAME, KEY_MANUFACTURER, KEY_MODEL, KEY_FIRMWARE,
            KEY_LAST_CONNECTED_AT_MS, KEY_BATTERY_PCT, KEY_BATTERY_AT_MS,
        )
        private val CADENCE_KEYS: List<Preferences.Key<*>> = listOf(
            KEY_CAD_ADDRESS, KEY_CAD_NAME, KEY_CAD_MANUFACTURER, KEY_CAD_MODEL,
            KEY_CAD_FIRMWARE, KEY_CAD_LAST_CONNECTED_AT_MS, KEY_CAD_BATTERY_PCT,
            KEY_CAD_BATTERY_AT_MS,
        )
    }
}
