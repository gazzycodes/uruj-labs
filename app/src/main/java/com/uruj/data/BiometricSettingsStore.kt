package com.uruj.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * v0.6.0 — toggle + state for the 24/7 continuous biometric service.
 *
 * `BiometricService` runs as a persistent foreground service holding a BLE
 * connection to the chest strap independent of any ride. This store persists:
 *   - User's opt-in toggle (default OFF — explicit consent for battery use)
 *   - Last-started timestamp (so we know how long it's been running)
 *   - Last-stopped timestamp + reason (for debugging crashes / OEM kills)
 *
 * MainActivity reads `enabled` on launch and starts the service if true.
 * The toggle UI in Diagnostics writes here directly.
 *
 * NOT stored here: per-sample RR data (that goes to NDJSON in
 * `ContinuousBiometricRecorder`) or aggregate metrics (those compute on
 * demand from the NDJSON files).
 */
data class BiometricSettings(
    val enabled: Boolean = false,
    val lastStartedAtMs: Long? = null,
    val lastStoppedAtMs: Long? = null,
)

private val Context.biometricSettingsDataStore: DataStore<Preferences>
    by preferencesDataStore("biometric_settings")

class BiometricSettingsStore(context: Context) {
    private val dataStore = context.applicationContext.biometricSettingsDataStore

    val settings: Flow<BiometricSettings> = dataStore.data.map { prefs ->
        BiometricSettings(
            enabled = prefs[KEY_ENABLED] ?: false,
            lastStartedAtMs = prefs[KEY_LAST_STARTED],
            lastStoppedAtMs = prefs[KEY_LAST_STOPPED],
        )
    }

    suspend fun current(): BiometricSettings = settings.first()

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_ENABLED] = enabled
            if (enabled) prefs[KEY_LAST_STARTED] = System.currentTimeMillis()
            else prefs[KEY_LAST_STOPPED] = System.currentTimeMillis()
        }
    }

    /** Touch last-started on service successful start (e.g. after auto-restart). */
    suspend fun touchStarted() {
        dataStore.edit { it[KEY_LAST_STARTED] = System.currentTimeMillis() }
    }

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("continuous_enabled")
        private val KEY_LAST_STARTED = longPreferencesKey("last_started_at_ms")
        private val KEY_LAST_STOPPED = longPreferencesKey("last_stopped_at_ms")
    }
}
