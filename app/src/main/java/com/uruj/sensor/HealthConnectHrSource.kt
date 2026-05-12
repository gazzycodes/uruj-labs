package com.uruj.sensor

import kotlinx.coroutines.flow.Flow

data class HrSample(
    /** When this sample was received from Health Connect, not when the band measured it. */
    val receivedAtMs: Long,
    /** When the band actually measured the HR. Use this to compute freshness. */
    val measuredAtMs: Long,
    val bpm: Int,
)

sealed interface HealthConnectStatus {
    data object Available : HealthConnectStatus
    data object NotInstalled : HealthConnectStatus
    data object PermissionDenied : HealthConnectStatus
    data class Error(val message: String) : HealthConnectStatus
}

interface HealthConnectHrSource {
    suspend fun status(): HealthConnectStatus

    /**
     * Polls Health Connect for recent HR readings. Emits the most-recent sample
     * whenever one newer than the last emission appears. Samsung Health pushes
     * HR in batches (~once per minute during a workout), so subscribers should
     * track sample age and render freshness rather than treating each emission
     * as "now".
     */
    fun samples(pollIntervalMillis: Long = 5_000L): Flow<HrSample>
}
