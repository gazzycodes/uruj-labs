package com.uruj.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists paired BLE chest-strap details so URUJ can auto-reconnect on ride
 * start without re-scanning every time. v0.5.1 introduction — once the rider
 * pairs their Magene H613 (or Polar / Wahoo / CooSpo) via Diagnostics
 * SCAN & PAIR, the MAC address is saved here. RideRecorderService reads it
 * at startRecording and skips scan, going direct to `connectGatt`.
 *
 * "Forget Strap" from Profile clears the saved address. Next ride falls back
 * to scan-and-pair.
 *
 * Stored: device MAC, last-known name, manufacturer/model/firmware (for
 * About-section transparency), last-connected-at timestamp (for "paired 3
 * days ago" UI strings).
 */
data class PairedStrap(
    val address: String,
    val name: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val firmware: String? = null,
    val lastConnectedAtMs: Long? = null,
)

private val Context.bleSettingsDataStore: DataStore<Preferences> by preferencesDataStore("ble_settings")

class BleSettingsStore(context: Context) {
    private val dataStore = context.applicationContext.bleSettingsDataStore

    val paired: Flow<PairedStrap?> = dataStore.data.map { prefs ->
        val address = prefs[KEY_ADDRESS] ?: return@map null
        PairedStrap(
            address = address,
            name = prefs[KEY_NAME],
            manufacturer = prefs[KEY_MANUFACTURER],
            model = prefs[KEY_MODEL],
            firmware = prefs[KEY_FIRMWARE],
            lastConnectedAtMs = prefs[KEY_LAST_CONNECTED_AT_MS],
        )
    }

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
        }
    }

    /** Update only the last-connected-at timestamp on each successful reconnect. */
    suspend fun touchLastConnected() {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_CONNECTED_AT_MS] = System.currentTimeMillis()
        }
    }

    /** "Forget Strap" — clear all paired-device state. Next ride re-scans. */
    suspend fun forget() {
        dataStore.edit { it.clear() }
    }

    companion object {
        private val KEY_ADDRESS = stringPreferencesKey("paired_address")
        private val KEY_NAME = stringPreferencesKey("paired_name")
        private val KEY_MANUFACTURER = stringPreferencesKey("paired_manufacturer")
        private val KEY_MODEL = stringPreferencesKey("paired_model")
        private val KEY_FIRMWARE = stringPreferencesKey("paired_firmware")
        private val KEY_LAST_CONNECTED_AT_MS = longPreferencesKey("paired_last_connected_at_ms")
    }
}
