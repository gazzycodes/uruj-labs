package com.uruj.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore("theme_settings")

/**
 * v0.9.79 — the rider's daylight/night display choice.
 *
 * Its own DataStore file rather than a field on an existing store: the theme is
 * read once at app start on the UI critical path, and must not be blocked behind
 * (or invalidated by) BLE pairing or profile writes.
 *
 * Defaults to dark — a fresh install on a night ride should not flash white.
 */
class ThemeSettingsStore(private val context: Context) {

    val lightMode: Flow<Boolean> =
        context.themeDataStore.data.map { it[LIGHT_MODE] ?: false }

    suspend fun currentLightMode(): Boolean = lightMode.first()

    suspend fun setLightMode(enabled: Boolean) {
        context.themeDataStore.edit { it[LIGHT_MODE] = enabled }
    }

    private companion object {
        val LIGHT_MODE = booleanPreferencesKey("light_mode")
    }
}
