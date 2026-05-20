package com.uruj.data

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.uruj.util.rethrowCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Full catalog of Health Connect data types URUJ inventories. Used by the diagnostics
 * screen so the user can verify pre-sleep that every pipeline (band → Samsung Health →
 * Health Connect → URUJ) is alive for the data types they care about.
 *
 * Each entry knows its category (for UI grouping), expected source (band/scale/manual),
 * and Health Connect record class. The repo queries every type in parallel and reports
 * per-type counts + latest record timestamps.
 */
enum class HcCategory(val displayName: String) {
    Cardiovascular("Cardiovascular"),
    Recovery("Recovery"),
    Activity("Activity"),
    Fitness("Fitness"),
    BodyComposition("Body composition"),
    Other("Other"),
}

enum class HcSourceExpectation(val displayName: String) {
    Wearable("Fit Band 3"),
    SmartScale("Smart scale"),
    Manual("Manual entry"),
    PhoneSensor("Phone sensor"),
}

data class HcDataType(
    val key: String,
    val displayName: String,
    val category: HcCategory,
    val expectedSource: HcSourceExpectation,
    val recordClass: KClass<out Record>,
    val unit: String,
) {
    val readPermission: String get() = HealthPermission.getReadPermission(recordClass)
}

object HcDataTypes {
    val all: List<HcDataType> = listOf(
        // Cardiovascular
        HcDataType("hr", "Heart Rate", HcCategory.Cardiovascular, HcSourceExpectation.Wearable, HeartRateRecord::class, "bpm"),
        HcDataType("hrv", "HR Variability", HcCategory.Cardiovascular, HcSourceExpectation.Wearable, HeartRateVariabilityRmssdRecord::class, "ms"),
        HcDataType("rhr", "Resting Heart Rate", HcCategory.Cardiovascular, HcSourceExpectation.Wearable, RestingHeartRateRecord::class, "bpm"),
        // Recovery
        HcDataType("sleep", "Sleep Sessions", HcCategory.Recovery, HcSourceExpectation.Wearable, SleepSessionRecord::class, "sessions"),
        HcDataType("spo2", "SpO2", HcCategory.Recovery, HcSourceExpectation.Wearable, OxygenSaturationRecord::class, "%"),
        HcDataType("bodytemp", "Body Temperature", HcCategory.Recovery, HcSourceExpectation.Wearable, BodyTemperatureRecord::class, "°C"),
        HcDataType("respiratory", "Respiratory Rate", HcCategory.Recovery, HcSourceExpectation.Wearable, RespiratoryRateRecord::class, "rpm"),
        // Activity
        HcDataType("steps", "Steps", HcCategory.Activity, HcSourceExpectation.Wearable, StepsRecord::class, "steps"),
        HcDataType("distance", "Distance", HcCategory.Activity, HcSourceExpectation.Wearable, DistanceRecord::class, "m"),
        HcDataType("activeCal", "Active Calories", HcCategory.Activity, HcSourceExpectation.Wearable, ActiveCaloriesBurnedRecord::class, "kcal"),
        HcDataType("totalCal", "Total Calories", HcCategory.Activity, HcSourceExpectation.Wearable, TotalCaloriesBurnedRecord::class, "kcal"),
        HcDataType("floors", "Floors Climbed", HcCategory.Activity, HcSourceExpectation.Wearable, FloorsClimbedRecord::class, "floors"),
        HcDataType("exercise", "Exercise Sessions", HcCategory.Activity, HcSourceExpectation.Wearable, ExerciseSessionRecord::class, "sessions"),
        // Fitness
        HcDataType("vo2max", "VO2 Max", HcCategory.Fitness, HcSourceExpectation.Wearable, Vo2MaxRecord::class, "ml/kg/min"),
        // Body composition
        HcDataType("weight", "Body Weight", HcCategory.BodyComposition, HcSourceExpectation.SmartScale, WeightRecord::class, "kg"),
        HcDataType("bodyfat", "Body Fat %", HcCategory.BodyComposition, HcSourceExpectation.SmartScale, BodyFatRecord::class, "%"),
    )

    val allReadPermissions: Set<String> = all.map { it.readPermission }.toSet()
}

data class HcDataTypeStatus(
    val type: HcDataType,
    val permissionGranted: Boolean,
    val recordCount7d: Int,
    val latestRecordAtMs: Long?,
    val errorMessage: String? = null,
) {
    enum class Health { Working, NoPermission, NoData, Error }

    val health: Health get() = when {
        !permissionGranted -> Health.NoPermission
        errorMessage != null -> Health.Error
        recordCount7d > 0 -> Health.Working
        else -> Health.NoData
    }
}

class HealthConnectInventoryRepository(context: Context) {

    private val appContext = context.applicationContext

    // v0.8.4 — in-process cache to prevent HC rate-limit storms.
    // Previously every Pipeline tab open + every refresh tap fired 16 HC reads
    // back-to-back with zero delay. HC's foreground rate-limiter then rejected
    // half the calls with "request quota exceeded" → most cards showed ERR.
    //
    // Cache TTL 30 sec: long enough to absorb tab-switching + double-taps;
    // short enough that refresh button still produces fresh data within a
    // few seconds. Forced refresh (rate=true) bypasses the cache.
    @Volatile
    private var cachedInventory: List<HcDataTypeStatus>? = null
    @Volatile
    private var cachedAtMs: Long = 0L

    suspend fun inventory(force: Boolean = false): List<HcDataTypeStatus> = withContext(Dispatchers.IO) {
        // Serve cache when fresh (and not forced)
        val now = System.currentTimeMillis()
        if (!force && cachedInventory != null && (now - cachedAtMs) < CACHE_TTL_MS) {
            return@withContext cachedInventory!!
        }
        // v0.8.5 — during the post-ride quiet window, serve cache even if
        // beyond normal TTL. The user is in the post-ride tab cascade and
        // fresh inventory would add 16 HC reads to a system that's already
        // burst-hot. Only the explicit REFRESH tap (force=true) breaks
        // through this guard.
        if (!force && cachedInventory != null && HcReadGuard.isPostRideQuietWindow()) {
            return@withContext cachedInventory!!
        }
        HcReadGuard.recordRead("inventory.fresh")

        val sdkOk = HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE
        if (!sdkOk) return@withContext HcDataTypes.all.map {
            HcDataTypeStatus(it, false, 0, null, "Health Connect not available")
        }
        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }.rethrowCancellation().getOrNull()
            ?: return@withContext HcDataTypes.all.map {
                HcDataTypeStatus(it, false, 0, null, "Cannot create HC client")
            }
        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .rethrowCancellation()
            .getOrDefault(emptySet())

        val weekAgo = Instant.now().minus(Duration.ofDays(7))
        val nowInstant = Instant.now()
        val range = TimeRangeFilter.between(weekAgo, nowInstant)

        // v0.8.4 — throttle HC reads. 150ms between consecutive reads keeps
        // us well below HC's per-second rate ceiling. 16 types × 150ms =
        // ~2.4 sec total (vs <100ms before, which blew the rate limit).
        val results = mutableListOf<HcDataTypeStatus>()
        for ((index, type) in HcDataTypes.all.withIndex()) {
            val status = if (type.readPermission !in granted) {
                HcDataTypeStatus(type, false, 0, null)
            } else {
                queryType(client, type, range)
            }
            results += status
            if (index < HcDataTypes.all.lastIndex) {
                delay(THROTTLE_DELAY_MS)
            }
        }
        cachedInventory = results
        cachedAtMs = System.currentTimeMillis()
        results
    }

    /** v0.8.4 — invalidate cache (e.g. after permission grant flow completes). */
    fun invalidateCache() {
        cachedInventory = null
        cachedAtMs = 0L
    }

    private suspend fun queryType(
        client: HealthConnectClient,
        type: HcDataType,
        range: TimeRangeFilter,
    ): HcDataTypeStatus {
        return runCatching {
            // KClass<out Record> can't be unified with the generic T in ReadRecordsRequest<T>
            // — we erase the variance with an unchecked cast. Safe because KClass is
            // metadata-only at runtime and ReadRecordsRequest doesn't expose T as input.
            @Suppress("UNCHECKED_CAST")
            val erasedType = type.recordClass as KClass<Record>
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = erasedType,
                    timeRangeFilter = range,
                    ascendingOrder = false,
                    pageSize = 100,
                ),
            )
            val latest = response.records.firstOrNull()
            val latestMs = latest?.metadata?.lastModifiedTime?.toEpochMilli()
            HcDataTypeStatus(type, true, response.records.size, latestMs)
        }.rethrowCancellation()
            .onFailure {
                Log.w("URUJ-Inventory", "Query failed for ${type.key}", it)
            }.getOrElse {
                HcDataTypeStatus(type, true, 0, null, it.message ?: "query failed")
            }
    }

    companion object {
        /** v0.8.4 — minimum delay between consecutive HC reads to stay under
         *  HC's foreground rate limiter. 150ms × 16 types ≈ 2.4 sec total. */
        private const val THROTTLE_DELAY_MS = 150L
        /** v0.8.4 — in-process cache TTL. Long enough to absorb tab-switching
         *  + double-taps; short enough that a refresh button tap forces fresh
         *  data within seconds. */
        private const val CACHE_TTL_MS = 30_000L
    }
}