package com.uruj.ui.routemap

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uruj.data.NdjsonRideReader
import com.uruj.data.RideHistoryRepository
import com.uruj.data.RiderProfileStore
import com.uruj.data.StoredRideSummary
import com.uruj.domain.RideSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Loads a ride's NDJSON, joins it with the rider's profile (for max-HR zone
 * coloring), and emits a renderable RouteMapState. Stride-downsamples to keep
 * polyline rendering smooth — 2hr ride at 1Hz = 7200 samples, more than the
 * map needs for visual clarity.
 *
 * HR zone classification uses %max-HR (not Karvonen). Why: %max is simpler,
 * doesn't depend on having an accurate resting-HR per-rider, and is the
 * industry-standard mapping for HR-zone-colored route visualization (Strava
 * uses similar). For pacing zones on the live HUD we use Karvonen — different
 * tools for different jobs.
 */
class RouteMapViewModel(application: Application) : AndroidViewModel(application) {

    private val reader = NdjsonRideReader(application)
    private val profileStore = RiderProfileStore(application)
    private val historyRepo = RideHistoryRepository(application)

    private val _state = MutableStateFlow<RouteMapState>(RouteMapState.Loading)
    val state: StateFlow<RouteMapState> = _state.asStateFlow()

    fun load(sessionId: String) {
        viewModelScope.launch {
            _state.value = RouteMapState.Loading
            val profile = profileStore.current()
            // Load summary AND samples — header needs ride context (date/dist/duration).
            val summary = historyRepo.load(sessionId)
            val samples = reader.readSamples(sessionId, stride = 3)
            val gpsPoints = samples.filter {
                it.horizontalAccuracyMeters in 0.1f..50f &&
                    (it.latitude != 0.0 || it.longitude != 0.0)
            }
            if (gpsPoints.isEmpty()) {
                _state.value = RouteMapState.Empty(
                    "No valid GPS samples in this ride. The recording may be too short or indoors."
                )
                return@launch
            }
            val zonedPoints = gpsPoints.map { sample ->
                ZonedPoint(
                    sample = sample,
                    zone = sample.hrBpm?.let { hr -> classifyZone(hr, profile.maxHrBpm) },
                )
            }
            // Flag whether the NDJSON had ANY HR data — older rides recorded
            // before our HC HR pipeline integration have hrBpm=null on every
            // sample. UI uses this to show "HR data: not captured for this ride"
            // so the rider knows the grey polyline isn't a bug.
            val anyHrData = zonedPoints.any { it.sample.hrBpm != null }
            _state.value = RouteMapState.Ready(
                sessionId = sessionId,
                summary = summary,
                points = zonedPoints,
                maxHrBpm = profile.maxHrBpm,
                rideStartMs = samples.firstOrNull()?.timestampMs ?: 0L,
                hasHrData = anyHrData,
            )
        }
    }

    /** %max-HR zone classification — universal mapping that doesn't require RHR. */
    private fun classifyZone(hrBpm: Int, maxHrBpm: Int): HrZone {
        if (maxHrBpm <= 0) return HrZone.Z1
        val pct = hrBpm.toFloat() / maxHrBpm
        return when {
            pct < 0.60f -> HrZone.Z1
            pct < 0.70f -> HrZone.Z2
            pct < 0.80f -> HrZone.Z3
            pct < 0.90f -> HrZone.Z4
            else -> HrZone.Z5
        }
    }
}

enum class HrZone(val label: String, val rangeLabel: String) {
    Z1("Recovery", "<60% max"),
    Z2("Endurance", "60-70% max"),
    Z3("Tempo", "70-80% max"),
    Z4("Threshold", "80-90% max"),
    Z5("VO2 / Sprint", "90%+ max"),
}

data class ZonedPoint(val sample: RideSample, val zone: HrZone?)

sealed class RouteMapState {
    data object Loading : RouteMapState()
    data class Empty(val reason: String) : RouteMapState()
    data class Ready(
        val sessionId: String,
        val summary: StoredRideSummary?,
        val points: List<ZonedPoint>,
        val maxHrBpm: Int,
        val rideStartMs: Long,
        /** False when NDJSON has no HR samples — UI surfaces this so the
         *  rider knows the grey polyline reflects missing data, not a bug. */
        val hasHrData: Boolean,
    ) : RouteMapState()
}
