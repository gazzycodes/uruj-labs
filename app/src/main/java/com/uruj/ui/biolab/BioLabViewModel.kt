package com.uruj.ui.biolab

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uruj.data.BioLabRepository
import com.uruj.data.BioLabSnapshot
import com.uruj.data.HcReadGuard
import com.uruj.data.MealMarkRepository
import com.uruj.data.PostprandialSnapshotRepository
import com.uruj.data.ReadinessRepository
import com.uruj.data.TrackerRepository
import com.uruj.domain.MealMark
import com.uruj.domain.TrackerEntry
import com.uruj.domain.TrackerType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BioLabViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = BioLabRepository(application)
    // v0.9.7 — ReadinessRepository ownership so Bio Lab can refresh today's
    // TSB snapshot before the Training State card reads disk. Fixes the
    // drift bug where Readiness card showed live TSB (e.g. -30) while Bio
    // Lab Training State card showed stale disk value (-29) from earlier
    // in the day. See [[reference_snapshot_persistence_architecture]] —
    // today's mutable / past immutable.
    private val readinessRepo = ReadinessRepository(application)
    // v0.9.31 — meal-mark events for Tier B postprandial test
    private val mealMarkRepo = MealMarkRepository(application)
    // v0.9.32 — delete-flow for the postprandial card long-press
    private val postprandialSnapshotRepo = PostprandialSnapshotRepository(application)
    // v0.9.39 — in-app subjective + behavioral tracker layer (#111)
    private val trackerRepo = TrackerRepository(application)

    // v0.9.31 — one-shot toast/snackbar message for meal-mark confirmation
    private val _markMealMessage = MutableStateFlow<String?>(null)
    val markMealMessage: StateFlow<String?> = _markMealMessage.asStateFlow()

    private val _snapshot = MutableStateFlow<BioLabSnapshot?>(null)
    val snapshot: StateFlow<BioLabSnapshot?> = _snapshot.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * v0.8.5 — refresh with sticky-cache fallback (same pattern as
     * ChecklistViewModel.refreshReadiness introduced in v0.8.4).
     *
     * Health Connect's foreground rate limiter can throttle reads silently —
     * URUJ's first transient stage of HC's cooldown returns empty results
     * with no exception thrown. Pre-v0.8.5 a refresh during cooldown would
     * overwrite a complete cached snapshot with a degraded one, blanking
     * Bio Lab cards even though the underlying data was unchanged.
     *
     * Sticky rule: only overwrite the displayed snapshot if the new compute
     * is AT LEAST AS COMPLETE as the cached one (measured by `dataConfidence`,
     * the fraction of the 7 key signals that produced non-null data), OR if
     * the cached snapshot is more than 10 minutes old (so a genuine
     * degradation eventually surfaces and doesn't get stuck behind a
     * forever-stale "complete" snapshot from earlier in the day).
     *
     * Manual refresh (force = true) always overwrites — the user explicitly
     * asked for fresh data; HC will be queried + the result shown verbatim,
     * even if degraded. The user is the source of truth at that point.
     */
    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            // v0.9.54 — tab-open debounce. If the cached snapshot is fresh
            // (<60s old) AND this is not a manual SYNC (force=false), skip
            // the whole compute path. The user JUST loaded Bio Lab seconds
            // ago — re-running the full snapshot() (HC reads + NDJSON walk
            // + HRR1 evaluation + HRV today + FreqDomain + CAR backfill =
            // ~18s cold) on a tab re-open is wasted work the user can't
            // distinguish from cached data anyway.
            //
            // Mirror of ChecklistViewModel.READINESS_REFRESH_DEBOUNCE_MS
            // pattern from v0.8.4 — same architectural rule applied to
            // Bio Lab. Manual SYNC button (force=true) always bypasses;
            // the rider explicitly asked for fresh data at that point.
            //
            // Per [[reference_perf_architecture_findings_2026_05_27]] this
            // is the missing piece — pre-v0.9.54 BioLab fired snapshot()
            // on EVERY observer tick, including rapid tab-switches. The
            // v0.8.5 sticky-cache only protected against degraded
            // overwrite; it did not skip the expensive work itself.
            val current = _snapshot.value
            val ageMs = current?.let { System.currentTimeMillis() - it.computedAtMs } ?: Long.MAX_VALUE
            if (!force && current != null && ageMs < BIOLAB_REFRESH_DEBOUNCE_MS) {
                Log.d(
                    TAG,
                    "[v0.9.54] debounced — cached snapshot is ${ageMs / 1000}s old (<${BIOLAB_REFRESH_DEBOUNCE_MS / 1000}s threshold)",
                )
                return@launch
            }
            _isLoading.value = true
            try {
                // v0.8.5 — during the post-ride quiet window, serve cache
                // and skip the HC-heavy snapshot compute entirely. User is
                // in the post-ride tab cascade and BioLab's reads (HC HR
                // samples 30d, sleep windows 30d, exercise sessions 30d,
                // VO2 record, weight record) total ~7 HC reads per snapshot
                // — enough to push burst sub-limit if combined with
                // Inventory + Readiness reads. Cache holds until normal
                // sticky-cache TTL takes over.
                val cached = _snapshot.value
                if (!force && cached != null && HcReadGuard.isPostRideQuietWindow()) {
                    Log.d(TAG, "[v0.8.5] post-ride quiet window — serving cache")
                    return@launch
                }
                HcReadGuard.recordRead("biolab.snapshot")
                // v0.9.7 — refresh today's TSB snapshot BEFORE building the
                // Bio Lab snapshot. Training Load card reads disk; without
                // this refresh the card would show the stale morning value
                // while the live Readiness recompute (on Checklist) shows
                // a slightly different value (Samsung HC sync drift).
                // Wrapped in runCatching — Bio Lab open shouldn't fail if
                // TSB refresh blips (HC throttle, missing rhr baseline, etc.)
                runCatching {
                    readinessRepo.refreshTsbSnapshotForToday()
                }.onFailure { Log.w(TAG, "[v0.9.7] TSB pre-refresh failed", it) }

                // v0.9.53 — pass `force` to the repo so manual SYNC taps
                // bypass the 30d aggregations cache. Background / tab-open
                // refreshes (force=false) use the cache when fresh.
                val fresh = repo.snapshot(forceRefresh = force)
                val shouldUpdate = force ||
                    cached == null ||
                    fresh.dataConfidence >= cached.dataConfidence ||
                    (System.currentTimeMillis() - cached.computedAtMs) > STALENESS_FLOOR_MS
                if (shouldUpdate) {
                    _snapshot.value = fresh
                } else {
                    Log.d(
                        TAG,
                        "[v0.8.5] sticky-cache: keeping cached snapshot " +
                            "(confidence ${cached?.dataConfidence}, age ${System.currentTimeMillis() - (cached?.computedAtMs ?: 0L)}ms) " +
                            "over fresh (confidence ${fresh.dataConfidence}). HC likely throttled.",
                    )
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * v0.9.31 → v0.9.32 — Mark a meal. Now accepts an [offsetMinutesAgo]
     * parameter so the rider can backdate the mark if they tapped late
     * (e.g. 15 min after finishing eating). Pre-window is -30..-5 min
     * before the (adjusted) timestamp, so for diagnostic accuracy the
     * mark should approximate the meal START.
     *
     * Idempotent in the sense that each tap saves a NEW mark; future
     * iteration may add 60-sec debounce.
     */
    fun markMeal(
        offsetMinutesAgo: Int = 0,
        eventType: String = "meal",
        note: String? = null,
    ) {
        viewModelScope.launch {
            val offsetMs = offsetMinutesAgo.toLong() * 60_000L
            val timestampMs = System.currentTimeMillis() - offsetMs
            val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
            val mark = MealMark(
                timestampMs = timestampMs,
                eventType = eventType,
                note = cleanNote,
            )
            val ok = mealMarkRepo.save(mark)
            _markMealMessage.value = if (ok) {
                val hhmm = java.time.LocalTime.ofInstant(
                    java.time.Instant.ofEpochMilli(timestampMs),
                    java.time.ZoneId.systemDefault(),
                ).withSecond(0).withNano(0).toString()
                val ago = if (offsetMinutesAgo > 0) " (backdated $offsetMinutesAgo min)" else ""
                val typeLabel = when (eventType) {
                    "coffee" -> "Coffee"
                    "drink" -> "Drink"
                    "alcohol" -> "Alcohol"
                    "snack" -> "Snack"
                    "custom" -> "Event"
                    else -> "Meal"
                }
                "$typeLabel marked at $hhmm$ago. Postprandial analysis in 75 min from event time."
            } else {
                "Failed to save event mark. Try again."
            }
        }
    }

    fun clearMarkMealMessage() {
        _markMealMessage.value = null
    }

    /**
     * v0.9.39 — Save a generic tracker entry (#111). Used for mood / energy /
     * hydration / caffeine in Phase 1; extensible to all 13 tracker types.
     * Triggers a Bio Lab refresh so the cards update with the new entry.
     */
    fun saveTrackerEntry(
        type: TrackerType,
        numericValue: Float? = null,
        textValue: String? = null,
        note: String? = null,
    ) {
        viewModelScope.launch {
            val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
            val cleanText = textValue?.trim()?.takeIf { it.isNotEmpty() }
            val entry = TrackerEntry(
                type = type.key,
                numericValue = numericValue,
                textValue = cleanText,
                timestampMs = System.currentTimeMillis(),
                note = cleanNote,
            )
            val ok = trackerRepo.save(entry)
            _markMealMessage.value = if (ok) {
                val display = when (type) {
                    TrackerType.MOOD -> "Mood ${numericValue?.toInt() ?: "—"} / 10 logged"
                    TrackerType.ENERGY -> "Energy ${numericValue?.toInt() ?: "—"} / 10 logged"
                    TrackerType.HYDRATION_ML -> "+${numericValue?.toInt() ?: "—"} ml hydration logged"
                    TrackerType.CAFFEINE_MG -> "+${numericValue?.toInt() ?: "—"} mg caffeine logged"
                    TrackerType.SUPPLEMENTS -> {
                        val dose = numericValue?.toInt()?.let { " $it mg" } ?: ""
                        "${cleanText ?: "Supplement"}$dose logged"
                    }
                    TrackerType.BRISTOL -> "Bristol type ${numericValue?.toInt() ?: "—"} logged"
                    TrackerType.SLEEP_QUALITY -> "Sleep quality ${numericValue?.toInt() ?: "—"} / 10 logged"
                    TrackerType.SORENESS -> {
                        val loc = cleanText?.let { " ($it)" } ?: ""
                        "Soreness ${numericValue?.toInt() ?: "—"} / 10$loc logged"
                    }
                }
                display
            } else {
                "Failed to save ${type.displayName} entry. Try again."
            }
            refresh(force = true)
        }
    }

    /**
     * v0.9.39 — Delete a specific tracker entry by type + id. Used by
     * long-press affordance on tracker cards + trend READINGS list rows.
     */
    fun deleteTrackerEntry(type: TrackerType, id: String) {
        viewModelScope.launch {
            val ok = trackerRepo.delete(type.key, id)
            _markMealMessage.value = if (ok) {
                "${type.displayName} entry deleted."
            } else {
                "Nothing to delete."
            }
            refresh(force = true)
        }
    }

    /**
     * v0.9.32 — Delete a meal mark + its computed postprandial snapshot.
     * Used by long-press affordance on the PostprandialResponseCard. Lets
     * the rider remove a bad meal mark (e.g. backdated wrong, contaminated
     * by ride/activity) and re-mark properly. Refresh after to update card.
     */
    fun deleteMealMark(mealMarkId: String) {
        viewModelScope.launch {
            val markDeleted = mealMarkRepo.delete(mealMarkId)
            val snapDeleted = postprandialSnapshotRepo.delete(mealMarkId)
            _markMealMessage.value = if (markDeleted || snapDeleted) {
                "Meal reading deleted. Refresh to update."
            } else {
                "Nothing to delete."
            }
            // Trigger Bio Lab refresh to pull the new state from disk
            refresh(force = true)
        }
    }

    companion object {
        private const val TAG = "URUJ-BioLab"

        /**
         * v0.8.5 — after this much time, even a degraded snapshot replaces
         * the cached one. Prevents indefinitely-stuck cards when the rider's
         * data legitimately degrades (rare, but possible if Samsung Health
         * stops syncing for hours).
         */
        private const val STALENESS_FLOOR_MS = 10L * 60L * 1000L

        /**
         * v0.9.54 — tab-open debounce window. When refresh() is called with
         * force=false (programmatic / observer / tab re-open) AND the cached
         * snapshot is younger than this, skip the entire compute path.
         *
         * 60s is the same threshold used by
         * [com.uruj.ui.checklist.ChecklistViewModel.READINESS_REFRESH_DEBOUNCE_MS]
         * — rationale documented there: anything fresher than 60s doesn't
         * meaningfully change between rapid tab switches. Samsung's HC sync
         * is ~15-min cadence anyway, so 60s of staleness is invisible.
         *
         * Manual SYNC (force=true) always bypasses this debounce — the
         * rider explicitly wants fresh data at that moment, even if HC is
         * burst-hot.
         */
        private const val BIOLAB_REFRESH_DEBOUNCE_MS = 60L * 1000L
    }
}
