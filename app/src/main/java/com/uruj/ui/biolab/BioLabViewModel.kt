package com.uruj.ui.biolab

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uruj.data.BioLabRepository
import com.uruj.data.BioLabSnapshot
import com.uruj.data.HcReadGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BioLabViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = BioLabRepository(application)

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
