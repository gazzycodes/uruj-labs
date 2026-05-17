package com.uruj.sensor

import kotlinx.coroutines.flow.Flow

data class HrSample(
    /** When this sample was received by our app. For HC: when we polled it out.
     *  For BLE: when the GATT notification arrived. */
    val receivedAtMs: Long,
    /** When the band actually measured the HR. Use this to compute freshness.
     *  For BLE chest straps this equals receivedAtMs (notifications are real-time). */
    val measuredAtMs: Long,
    val bpm: Int,
    /** Beat-to-beat RR intervals in ms, present when source provides them.
     *  Empty for Health Connect (Fit Band 3 doesn't expose RR), populated for
     *  BLE chest straps with flag-bit-4 in the 0x2A37 characteristic.
     *  Real RMSSD HRV requires this — it's the lab-grade signal. */
    val rrIntervalsMs: List<Int> = emptyList(),
    /** Source provenance (lab-level rule 1). Tells the rest of the pipeline
     *  which sensor produced this sample for cross-validation logging + UI. */
    val source: Source = Source.UNKNOWN,
    /** True when the sensor reports good electrode contact. BLE HR Service
     *  flag bit 1 (Sensor Contact Status). Null when sensor doesn't report. */
    val contactDetected: Boolean? = null,
) {
    enum class Source {
        BLE_CHEST_STRAP,   // Magene H613 etc. — ECG-precision, RR intervals available
        HC_BATCHED,        // Samsung Fit Band 3 via Health Connect — batched, no RR
        UNKNOWN,           // Default — should be set by every source going forward
    }
}

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
