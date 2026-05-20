package com.uruj.ui.routemap

import android.app.Application
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uruj.data.NdjsonRideReader
import com.uruj.data.RideHistoryRepository
import com.uruj.data.RiderProfileStore
import com.uruj.data.StoredRideSummary
import com.uruj.domain.RideSample
import com.uruj.util.rethrowCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

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

            // v0.3.5: pull HC HR samples for the ride's time range and time-align
            // them to GPS points. Fixes the fundamental Fit Band 3 batching limit
            // — during the ride, NDJSON gets hrBpm=null because Samsung doesn't
            // push HR live. AFTER the ride, Samsung's batch sync populates HC.
            // So at render time we can read HC + join to GPS by nearest timestamp.
            // Works retroactively for all rides whose HR data still lives in HC
            // (Samsung default retention 30+ days).
            val rideStartMs = samples.firstOrNull()?.timestampMs ?: 0L
            val rideEndMs = samples.lastOrNull()?.timestampMs ?: rideStartMs
            val hcHrSamples = if (rideStartMs > 0 && rideEndMs > rideStartMs) {
                readHcHrForRideWindow(rideStartMs, rideEndMs)
            } else emptyList()
            val augmentedPoints = if (hcHrSamples.isNotEmpty()) {
                timeAlignHrToGps(gpsPoints, hcHrSamples)
            } else gpsPoints

            val zonedPoints = augmentedPoints.map { sample ->
                ZonedPoint(
                    sample = sample,
                    // v0.9.14 — Karvonen classification using profile RHR.
                    // Matches TIZ card + HUD + audio coach. Pre-v0.9.14 used
                    // %max-HR which disagreed with the other surfaces.
                    zone = sample.hrBpm?.let { hr ->
                        classifyZone(hr, profile.maxHrBpm, profile.restingHrBpm)
                    },
                )
            }
            // Flag now reflects HC-augmented data — when sleep/post-ride sync
            // populated HC, hasHrData becomes true even for rides whose NDJSON
            // recorded all-null HR. UI shows zone legend instead of "NO HR DATA."
            val anyHrData = zonedPoints.any { it.sample.hrBpm != null }
            Log.d(TAG, "Ride $sessionId: ${gpsPoints.size} GPS · ${hcHrSamples.size} HC HR · zone-tagged: $anyHrData")
            _state.value = RouteMapState.Ready(
                sessionId = sessionId,
                summary = summary,
                points = zonedPoints,
                maxHrBpm = profile.maxHrBpm,
                rideStartMs = rideStartMs,
                hasHrData = anyHrData,
            )
        }
    }

    /**
     * Read all HC HeartRateRecord samples whose timestamps fall within the ride
     * window (±120s buffer to catch edge samples). Returns (epoch-ms, bpm) pairs
     * sorted ascending by time. Silent empty-list on any failure — caller
     * gracefully degrades to NDJSON-only rendering.
     */
    private suspend fun readHcHrForRideWindow(
        rideStartMs: Long,
        rideEndMs: Long,
    ): List<Pair<Long, Int>> = withContext(Dispatchers.IO) {
        runCatching {
            val app = getApplication<Application>()
            val sdkOk = HealthConnectClient.getSdkStatus(app) == HealthConnectClient.SDK_AVAILABLE
            if (!sdkOk) return@runCatching emptyList()
            val client = HealthConnectClient.getOrCreate(app)
            val granted = client.permissionController.getGrantedPermissions()
            if (HealthPermission.getReadPermission(HeartRateRecord::class) !in granted) {
                return@runCatching emptyList()
            }
            // v0.8.5 — observability so HC pressure is visible in logcat.
            // Route map opens once per ride-summary visit, not a hot path.
            com.uruj.data.HcReadGuard.recordRead("routemap.hr-samples")
            val rangeStart = Instant.ofEpochMilli(rideStartMs).minusSeconds(120)
            val rangeEnd = Instant.ofEpochMilli(rideEndMs).plusSeconds(120)
            client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(rangeStart, rangeEnd),
                    ascendingOrder = true,
                    pageSize = 5_000,
                ),
            ).records
                .flatMap { it.samples }
                .map { it.time.toEpochMilli() to it.beatsPerMinute.toInt() }
                .sortedBy { it.first }
        }.rethrowCancellation()
            .onFailure { Log.w(TAG, "HC HR read failed for ride window", it) }
            .getOrDefault(emptyList())
    }

    /**
     * Merge-style O(n+m) time alignment: for each GPS sample with null hrBpm,
     * find the closest HC HR sample by timestamp (within ±60s tolerance) and
     * inject it into the sample. Preserves any existing NDJSON HR data on the
     * sample (live data wins over post-batch). hcHrSamples must be ascending.
     */
    private fun timeAlignHrToGps(
        gpsPoints: List<RideSample>,
        hcHrSamples: List<Pair<Long, Int>>,
    ): List<RideSample> {
        if (hcHrSamples.isEmpty()) return gpsPoints
        val toleranceMs = 60_000L
        var hcIdx = 0
        return gpsPoints.map { gps ->
            if (gps.hrBpm != null) return@map gps
            val gpsTime = gps.timestampMs
            // Advance hcIdx to the first HC sample at or after gpsTime
            while (hcIdx < hcHrSamples.size && hcHrSamples[hcIdx].first < gpsTime) {
                hcIdx++
            }
            // Closest candidate is hcIdx (≥gpsTime) or hcIdx-1 (<gpsTime)
            val candidates = listOfNotNull(
                hcHrSamples.getOrNull(hcIdx),
                hcHrSamples.getOrNull(hcIdx - 1),
            )
            val closest = candidates.minByOrNull { kotlin.math.abs(it.first - gpsTime) }
                ?: return@map gps
            val timeDiffMs = kotlin.math.abs(closest.first - gpsTime)
            if (timeDiffMs > toleranceMs) return@map gps
            gps.copy(hrBpm = closest.second, hrAgeMs = timeDiffMs)
        }
    }

    companion object {
        private const val TAG = "URUJ-RouteMap"
    }

    /**
     * v0.9.14 — Karvonen classification delegating to the single source of
     * truth in [com.uruj.power.KarvonenZonesCalculator.classifyKarvonenZone].
     * Pre-fix used %max-HR (didn't account for RHR — wrong for trained
     * athletes). Now agrees with TIZ card + HUD + audio coach + Bio Lab.
     */
    private fun classifyZone(hrBpm: Int, maxHrBpm: Int, restingHrBpm: Int): HrZone {
        val zone = com.uruj.power.KarvonenZonesCalculator.classifyKarvonenZone(
            hrBpm = hrBpm,
            hrMax = maxHrBpm,
            hrRest = restingHrBpm,
        )
        // Map shared classifier (0=below Z1, 1-5=Z1-Z5) → polyline HrZone.
        // v0.9.17 — Sub-Z1 deliberately stays on the route polyline as Z1
        // color. The TIZ card now exposes sub-Z1 as its own 6th bucket so
        // depth-of-recovery is visible numerically, but the route polyline
        // stays at 5 colors for map readability — a 6th color on a busy
        // OSM tile background would crowd the visual. Riders see "where
        // sub-recovery time happened" in TIZ; they see "where they rode and
        // roughly how hard" on the map. Different lenses, same source data.
        return when (zone) {
            0, 1 -> HrZone.Z1
            2 -> HrZone.Z2
            3 -> HrZone.Z3
            4 -> HrZone.Z4
            else -> HrZone.Z5
        }
    }
}

/**
 * v0.9.14 — labels updated to reflect Karvonen semantics. The %-of-HRR
 * range labels (matching [com.uruj.power.KarvonenZonesCalculator] zone
 * ranges) replace the prior %-of-max labels. Zone NAMES kept identical
 * (Recovery / Endurance / Tempo / Threshold / VO2 / Sprint) so existing
 * trend chart + tier-legend semantics carry over.
 */
enum class HrZone(val label: String, val rangeLabel: String) {
    Z1("Recovery", "50-60% HRR"),
    Z2("Endurance", "60-70% HRR"),
    Z3("Tempo", "70-80% HRR"),
    Z4("Threshold", "80-90% HRR"),
    Z5("VO2 / Sprint", "90%+ HRR"),
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
