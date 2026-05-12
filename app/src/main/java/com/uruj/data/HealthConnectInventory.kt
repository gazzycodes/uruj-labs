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
import kotlinx.coroutines.Dispatchers
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

    suspend fun inventory(): List<HcDataTypeStatus> = withContext(Dispatchers.IO) {
        val sdkOk = HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE
        if (!sdkOk) return@withContext HcDataTypes.all.map {
            HcDataTypeStatus(it, false, 0, null, "Health Connect not available")
        }
        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }.getOrNull()
            ?: return@withContext HcDataTypes.all.map {
                HcDataTypeStatus(it, false, 0, null, "Cannot create HC client")
            }
        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())

        val weekAgo = Instant.now().minus(Duration.ofDays(7))
        val now = Instant.now()
        val range = TimeRangeFilter.between(weekAgo, now)

        HcDataTypes.all.map { type ->
            if (type.readPermission !in granted) {
                HcDataTypeStatus(type, false, 0, null)
            } else {
                queryType(client, type, range)
            }
        }
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
        }.onFailure {
            Log.w("URUJ-Inventory", "Query failed for ${type.key}", it)
        }.getOrElse {
            HcDataTypeStatus(type, true, 0, null, it.message ?: "query failed")
        }
    }
}
