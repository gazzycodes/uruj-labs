package com.uruj.power

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.prsDataStore: DataStore<Preferences> by preferencesDataStore("personal_records")

/**
 * Tracks all-time best rolling-average power across common interval lengths. Each new
 * ride sample feeds the rolling buffers; whenever a window's current average exceeds
 * the stored all-time best, we fire a callback (HUD flash + TTS announcement).
 *
 * Stored in DataStore so PRs persist across rides forever.
 */
class PrTracker(context: Context) {

    private val dataStore = context.applicationContext.prsDataStore

    private val windowLengthsSec = listOf(60, 300, 1200)
    private val rolling = windowLengthsSec.associateWith { com.uruj.util.RollingAverage(it) }
    private val bests = mutableMapOf<Int, Float>()
    /** Ms since-epoch when each window most recently announced a PR. Prevents spam. */
    private val lastAnnouncedAt = mutableMapOf<Int, Long>()

    suspend fun load() {
        val prefs = dataStore.data.first()
        windowLengthsSec.forEach { sec ->
            bests[sec] = prefs[bestKey(sec)] ?: 0f
        }
    }

    /**
     * Feed a new instant power sample. Returns the list of windows that just hit a new PR
     * (empty if none). Throttled — each window can fire at most once per [COOLDOWN_MS]
     * so a steadily-rising warmup average doesn't trigger 15 spammy announcements.
     */
    fun observe(timestampMs: Long, instantPowerWatts: Float): List<NewPr> {
        if (instantPowerWatts <= 0f) return emptyList()
        val results = mutableListOf<NewPr>()
        windowLengthsSec.forEach { sec ->
            val buffer = rolling.getValue(sec)
            val avg = buffer.add(timestampMs, instantPowerWatts)
            // Window must be genuinely full — a 1-second spike shouldn't trigger a
            // "20-minute power PR" announcement.
            if (!buffer.isFull()) return@forEach
            // Per-window cooldown: don't announce another PR for this window until
            // COOLDOWN_MS has elapsed since the last announcement (or it's the first).
            val lastAnnounced = lastAnnouncedAt[sec]
            if (lastAnnounced != null && (timestampMs - lastAnnounced) < COOLDOWN_MS) {
                // Update the best silently — we still want to track the high mark for
                // persistence, just don't announce/flash again so soon.
                val previous = bests[sec] ?: 0f
                if (avg > previous) bests[sec] = avg
                return@forEach
            }
            val previous = bests[sec] ?: 0f
            // First-ever PR for this window: any sustained effort > 50 W counts.
            // Subsequent PRs require a meaningful improvement (≥10% — was 5%, too sensitive).
            val isNewPr = (previous == 0f && avg > 50f) || avg > previous * 1.10f
            if (isNewPr) {
                bests[sec] = avg
                lastAnnouncedAt[sec] = timestampMs
                results += NewPr(windowSeconds = sec, watts = avg, previousWatts = previous)
            }
        }
        return results
    }

    companion object {
        /** Minimum delay between PR announcements for the same window (5 minutes). */
        private const val COOLDOWN_MS = 5 * 60 * 1_000L
    }

    suspend fun persist() {
        dataStore.edit { prefs ->
            windowLengthsSec.forEach { sec ->
                bests[sec]?.let { prefs[bestKey(sec)] = it }
            }
        }
    }

    /**
     * Wipes all stored PR ceilings — in-memory state, persisted values, and
     * announcement-cooldown timestamps. Next sustained efforts that complete
     * a window become the new fresh PRs. Used to clear pollution from early
     * test rides that registered inflated power (pre-v0.1 GPS-quality fix)
     * and left ceilings too high for real efforts to beat.
     */
    suspend fun reset() {
        bests.clear()
        lastAnnouncedAt.clear()
        rolling.values.forEach { it.reset() }
        dataStore.edit { prefs ->
            windowLengthsSec.forEach { sec ->
                prefs.remove(bestKey(sec))
            }
        }
    }

    private fun bestKey(seconds: Int) = floatPreferencesKey("pr_best_${seconds}s_watts")
}

data class NewPr(val windowSeconds: Int, val watts: Float, val previousWatts: Float) {
    val label: String get() = when (windowSeconds) {
        60 -> "1-minute"
        300 -> "5-minute"
        1200 -> "20-minute"
        else -> "${windowSeconds}s"
    }
}
