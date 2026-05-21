package com.uruj.ui.history

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uruj.data.NdjsonRideReader
import com.uruj.data.RideHistoryRepository
import com.uruj.data.RiderProfileStore
import com.uruj.data.StoredRideSummary
import com.uruj.power.WeeklyPolarizedAnalyzer
import com.uruj.util.rethrowCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields

class RideHistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = RideHistoryRepository(application)
    private val ndjsonReader = NdjsonRideReader(application)
    private val profileStore = RiderProfileStore(application)
    private val weeklyAnalyzer = WeeklyPolarizedAnalyzer()

    private val _rides = MutableStateFlow<List<StoredRideSummary>>(emptyList())
    val rides: StateFlow<List<StoredRideSummary>> = _rides.asStateFlow()

    /**
     * v0.9.22 — weekly polarized compliance summary for the current ISO week
     * (Mon-Sun). Null while loading or when the week has no rides with HR
     * data. Recomputed on every [refresh] call (which fires on screen open),
     * so freshness is gated on user navigation — same v0.9.17 design that
     * has per-ride TIZ recompute on summary open.
     */
    private val _weeklyPolarized = MutableStateFlow<WeeklyPolarizedAnalyzer.WeekResult?>(null)
    val weeklyPolarized: StateFlow<WeeklyPolarizedAnalyzer.WeekResult?> = _weeklyPolarized.asStateFlow()

    /**
     * v0.9.24 — error-state surface for the weekly polarized compute. Set
     * when a non-cancellation exception fires so the v0.9.23 skeleton can
     * transition to an actionable error card with a retry button instead
     * of pulsing forever. Cleared on next successful compute. Cancellation
     * preserved per v0.9.20.
     */
    private val _weeklyPolarizedError = MutableStateFlow<String?>(null)
    val weeklyPolarizedError: StateFlow<String?> = _weeklyPolarizedError.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                // Sweep up orphan NDJSON files — typically rides whose service was killed
                // before stopRecording could write a summary. Idempotent and cheap.
                repo.recoverOrphanRides()
                repo.listAll()
            }
            _rides.value = list
            // v0.9.22 — kick off the weekly polarized compute in the same
            // launch but after the list is published. Heavier (7 NDJSON
            // reads + TIZ recomputes), runs on Dispatchers.IO and doesn't
            // block the list rendering.
            computeWeeklyPolarized(list)
        }
    }

    fun delete(sessionId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(sessionId) }
            refresh()
        }
    }

    /**
     * v0.9.22 — compute the weekly polarized result from the rides already
     * loaded. Filters to the current ISO week (Mon-Sun), loads NDJSON HR
     * samples for each ride in-window, and aggregates via
     * [WeeklyPolarizedAnalyzer.compute]. Rides without usable strap HR
     * (< [NdjsonRideReader.MIN_USEFUL_HR_SAMPLES]) are still counted as
     * logged rides but contribute zero TIZ — daily bar shows as "no HR
     * data" greyed out.
     *
     * HC fallback for legacy band-only rides deferred to a future PR. For
     * v0.9.22 MVP, NDJSON-strap rides are the primary path (the rider's
     * post-Magene-H613-purchase rides all have strap data).
     */
    private suspend fun computeWeeklyPolarized(rides: List<StoredRideSummary>) {
        runCatching {
            withContext(Dispatchers.IO) {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val weekStart = today.with(WeekFields.ISO.dayOfWeek(), 1L)
                val weekEnd = weekStart.plusDays(6)

                // Pre-filter to rides in the current week (cheap — date math
                // on summary metadata; no disk I/O yet).
                val weekRides = rides.filter { summary ->
                    val rideDate = Instant.ofEpochMilli(summary.startedAtMs).atZone(zone).toLocalDate()
                    rideDate in weekStart..weekEnd
                }

                val profile = profileStore.current()

                // Load HR samples per ride (one NDJSON read each). Sequential
                // (not parallel) to keep disk I/O orderly and to respect any
                // future HC fallback's rate-limit posture.
                val ridesWithHr = weekRides.map { summary ->
                    val samples = runCatching { ndjsonReader.readHrSamples(summary.sessionId) }
                        .rethrowCancellation()
                        .onFailure {
                            Log.w(TAG, "weekly polarized: HR samples read failed for ${summary.sessionId}", it)
                        }
                        .getOrDefault(emptyList())
                    // v0.8.2 MIN_USEFUL_HR_SAMPLES gate — fewer than this and
                    // we don't trust the NDJSON-only stream. Day's bar will
                    // show "no HR data."
                    val usable = if (samples.size >= NdjsonRideReader.MIN_USEFUL_HR_SAMPLES) samples
                    else emptyList()
                    summary to usable
                }

                weeklyAnalyzer.compute(
                    today = today,
                    zone = zone,
                    ridesWithHr = ridesWithHr,
                    maxHrBpm = profile.maxHrBpm,
                    restingHrBpm = profile.restingHrBpm,
                )
            }
        }.rethrowCancellation()
            .onSuccess { result ->
                _weeklyPolarized.value = result
                _weeklyPolarizedError.value = null  // v0.9.24 — clear error on success
                Log.d(
                    TAG,
                    "weekly polarized: ${result.rideCount} rides, ${result.ridesWithHrCount} with HR · " +
                        "easy=${(result.weeklyEasyPct * 100).toInt()}% gray=${(result.weeklyGrayPct * 100).toInt()}% " +
                        "hard=${(result.weeklyHardPct * 100).toInt()}%",
                )
            }
            .onFailure {
                Log.w(TAG, "weekly polarized compute failed", it)
                // v0.9.24 — surface error so the UI can transition the skeleton
                // (v0.9.23) to an actionable error card with retry button.
                // Only set if there's no cached prior result (sticky cache wins
                // over visible error when we have stale-but-real data).
                if (_weeklyPolarized.value == null) {
                    _weeklyPolarizedError.value = it.message ?: it::class.simpleName ?: "unknown failure"
                }
            }
    }

    companion object {
        private const val TAG = "URUJ-WeeklyPolar"
    }
}
