package com.uruj.ui.profile

import android.app.Application
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uruj.data.RiderProfileStore
import com.uruj.domain.RiderProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

class RiderProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val store = RiderProfileStore(application)
    private val appContext = application.applicationContext

    private val _profile = MutableStateFlow(RiderProfile())
    val profile: StateFlow<RiderProfile> = _profile.asStateFlow()

    init {
        viewModelScope.launch {
            // Auto-sync first so the displayed values match Samsung Health on
            // open. User manual edits still hold for fields HC doesn't write
            // (FTP, tire type, riding position, bike weight, age). Weight gets
            // refreshed every time because the scale is the authoritative source.
            syncFromHealthConnect()
            store.profile.collect { _profile.value = it }
        }
    }

    fun save(profile: RiderProfile) {
        viewModelScope.launch {
            store.save(profile)
        }
    }

    /**
     * Pull the latest WeightRecord from Health Connect and write it into the
     * stored profile if it differs from the current value. Silent on failure —
     * if HC isn't installed, or weight permission isn't granted, the manual
     * value already in the store is correct and remains in place.
     */
    private suspend fun syncFromHealthConnect() {
        val sdkOk = HealthConnectClient.getSdkStatus(appContext) == HealthConnectClient.SDK_AVAILABLE
        if (!sdkOk) return
        val client = runCatching { HealthConnectClient.getOrCreate(appContext) }.getOrNull() ?: return
        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
        if (HealthPermission.getReadPermission(WeightRecord::class) !in granted) return

        val latestWeight: Float? = runCatching {
            val now = Instant.now()
            client.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minus(Duration.ofDays(30)), now),
                    ascendingOrder = false,
                    pageSize = 1,
                ),
            ).records.firstOrNull()?.weight?.inKilograms?.toFloat()
        }.onFailure { Log.w(TAG, "weight sync read failed", it) }.getOrNull()

        if (latestWeight != null) {
            val current = store.current()
            if (abs(current.riderWeightKg - latestWeight) > 0.05f) {
                store.save(current.copy(riderWeightKg = latestWeight))
            }
        }
    }

    companion object {
        private const val TAG = "URUJ-ProfileVM"
    }
}
