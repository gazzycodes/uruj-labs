package com.uruj.sensor.android

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.uruj.sensor.HealthConnectHrSource
import com.uruj.sensor.HealthConnectStatus
import com.uruj.sensor.HrSample
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.time.Duration
import java.time.Instant

private val HR_LOOKBACK_WINDOW: Duration = Duration.ofMinutes(5)
private val HR_READ_PERMISSION: String = HealthPermission.getReadPermission(HeartRateRecord::class)
private val HRV_READ_PERMISSION: String = HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)
private val SLEEP_READ_PERMISSION: String = HealthPermission.getReadPermission(SleepSessionRecord::class)
private val RHR_READ_PERMISSION: String = HealthPermission.getReadPermission(RestingHeartRateRecord::class)

/**
 * Per-record permission strings the app needs from Health Connect. The pre-ride
 * checklist requests them all in one flow so the user grants once and unlocks
 * everything (live HR, post-ride HR enrichment, readiness scoring).
 */
object HealthConnectPermissions {
    val readHeartRate: Set<String> = setOf(HR_READ_PERMISSION)
    val readHrv: Set<String> = setOf(HRV_READ_PERMISSION)
    val readSleep: Set<String> = setOf(SLEEP_READ_PERMISSION)
    val readRestingHr: Set<String> = setOf(RHR_READ_PERMISSION)

    /** Combined set requested by the pre-ride checklist FIX flow — grants the full
     *  biohacker pipeline catalog (HR, HRV, sleep, RHR, SpO2, VO2max, steps, weight,
     *  body fat, calories, distance, floors, exercise, body temp, respiratory rate). */
    val allReadPermissions: Set<String>
        get() = com.uruj.data.HcDataTypes.allReadPermissions
}

class AndroidHealthConnectHrSource(context: Context) : HealthConnectHrSource {

    private val appContext = context.applicationContext

    override suspend fun status(): HealthConnectStatus {
        return when (HealthConnectClient.getSdkStatus(appContext)) {
            HealthConnectClient.SDK_AVAILABLE -> {
                runCatching {
                    val client = HealthConnectClient.getOrCreate(appContext)
                    val granted = client.permissionController.getGrantedPermissions()
                    if (HR_READ_PERMISSION in granted) HealthConnectStatus.Available
                    else HealthConnectStatus.PermissionDenied
                }.getOrElse {
                    HealthConnectStatus.Error(it.message ?: it::class.simpleName ?: "unknown")
                }
            }
            HealthConnectClient.SDK_UNAVAILABLE,
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectStatus.NotInstalled
            else -> HealthConnectStatus.Error("Unknown SDK status")
        }
    }

    override fun samples(pollIntervalMillis: Long): Flow<HrSample> = flow {
        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }.getOrNull()
            ?: return@flow

        var lastEmittedMeasuredMs: Long? = null

        while (currentCoroutineContext().isActive) {
            val now = Instant.now()
            val windowStart = now.minus(HR_LOOKBACK_WINDOW)

            val newest = runCatching {
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(windowStart, now),
                        ascendingOrder = false,
                        // Each record can contain multiple Samples (series). Pull a few records
                        // so we don't miss a late-arriving sample from a longer batch.
                        pageSize = 10,
                    )
                ).records
                    .flatMap { it.samples }
                    .maxByOrNull { it.time }
            }.getOrNull()

            if (newest != null) {
                val measuredMs = newest.time.toEpochMilli()
                if (measuredMs != lastEmittedMeasuredMs) {
                    emit(
                        HrSample(
                            receivedAtMs = System.currentTimeMillis(),
                            measuredAtMs = measuredMs,
                            bpm = newest.beatsPerMinute.toInt(),
                            // HC doesn't expose RR intervals from Samsung Fit Band 3.
                            source = HrSample.Source.HC_BATCHED,
                        )
                    )
                    lastEmittedMeasuredMs = measuredMs
                }
            }

            delay(pollIntervalMillis)
        }
    }
}
