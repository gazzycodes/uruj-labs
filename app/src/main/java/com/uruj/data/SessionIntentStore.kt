package com.uruj.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.uruj.domain.SessionIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * v0.8.0 — persists the rider's "today's session" choice. Pre-ride checklist
 * writes here; RideRecorderService + HUD read from here.
 *
 * Last-picked value sticks across app restarts so the rider doesn't have to
 * pick again if they ride twice in a day with the same intent. They can
 * always override via the HUD mid-ride.
 *
 * Default: EXPLORATORY (no coaching) so a first-time user / unconfigured
 * setup never gets surprise TTS chatter.
 */
private val Context.sessionIntentDataStore: DataStore<Preferences>
    by preferencesDataStore("session_intent")

class SessionIntentStore(context: Context) {
    private val dataStore = context.applicationContext.sessionIntentDataStore

    val intent: Flow<SessionIntent> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_INTENT] ?: SessionIntent.EXPLORATORY.name
        runCatching { SessionIntent.valueOf(raw) }.getOrDefault(SessionIntent.EXPLORATORY)
    }

    suspend fun current(): SessionIntent = intent.first()

    suspend fun set(intent: SessionIntent) {
        dataStore.edit { it[KEY_INTENT] = intent.name }
    }

    companion object {
        private val KEY_INTENT = stringPreferencesKey("session_intent_v1")
    }
}
