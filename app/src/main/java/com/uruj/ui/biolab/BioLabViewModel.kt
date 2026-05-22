package com.uruj.ui.biolab

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uruj.data.BioLabRepository
import com.uruj.data.BioLabSnapshot
import com.uruj.data.HcReadGuard
import com.uruj.data.MealMarkRepository
import com.uruj.data.ReadinessRepository
import com.uruj.domain.MealMark
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

                val fresh = repo.snapshot()
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
     * v0.9.31 — Mark a meal at the current wall-clock time. Triggers the
     * postprandial HRV response test pipeline: 75 min from now, the
     * pre-meal (-30..-5 min) and post-meal (+45..+75 min) windows can be
     * sliced from strap NDJSON and compared via [PostprandialCalculator].
     *
     * Idempotent in the sense that each tap saves a NEW mark — if the
     * rider accidentally double-taps within seconds, both marks save
     * (both compute identical postprandial responses, which is harmless
     * but does cost extra disk). Future UX iteration: 60-sec debounce.
     */
    fun markMeal() {
        viewModelScope.launch {
            val mark = MealMark(timestampMs = System.currentTimeMillis())
            val ok = mealMarkRepo.save(mark)
            _markMealMessage.value = if (ok) {
                val hhmm = java.time.LocalTime.now().withSecond(0).withNano(0).toString()
                "Meal marked at $hhmm. Postprandial analysis in 75 min."
            } else {
                "Failed to save meal mark. Try again."
            }
        }
    }

    fun clearMarkMealMessage() {
        _markMealMessage.value = null
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
    }
}
