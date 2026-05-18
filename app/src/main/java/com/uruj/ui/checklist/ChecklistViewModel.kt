package com.uruj.ui.checklist

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uruj.data.ReadinessRepository
import com.uruj.data.ReadinessSnapshot
import com.uruj.domain.ReadinessResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

private const val OPENTRACKS_PACKAGE = "de.dennisguse.opentracks"
private val HR_READ_PERMISSION = HealthPermission.getReadPermission(HeartRateRecord::class)

class ChecklistViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication()
    private val readinessRepo = ReadinessRepository(application)

    private val _state = MutableStateFlow(ChecklistState())
    val state: StateFlow<ChecklistState> = _state.asStateFlow()

    private val _readiness = MutableStateFlow<ReadinessResult?>(null)
    val readiness: StateFlow<ReadinessResult?> = _readiness.asStateFlow()

    private val _readinessSnapshot = MutableStateFlow<ReadinessSnapshot?>(null)
    val readinessSnapshot: StateFlow<ReadinessSnapshot?> = _readinessSnapshot.asStateFlow()

    private val _readinessSyncing = MutableStateFlow(false)
    val readinessSyncing: StateFlow<Boolean> = _readinessSyncing.asStateFlow()

    private var pollingJob: Job? = null
    private var readinessJob: Job? = null

    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(2_000L)
            }
        }
        // v0.8.4 — Readiness polling reduced from 60s → 5 min to stop the
        // HC rate-limit storm. Each Readiness compute fires 6-8 HC reads
        // (sleep windows, HR samples, RHR record, HRV record, exercise
        // sessions). At 60s cadence that was ~480 HC reads/hour just from
        // this poll, alone enough to consistently bump HC's foreground
        // quota ceiling.
        //
        // User-visible impact: readiness updates every 5 min in the
        // background. The SYNC button on the Readiness card stays available
        // for INSTANT manual refresh. Sleep / HRV / RHR / TSB don't change
        // minute-to-minute anyway — 5 min is plenty.
        //
        // App-open ALWAYS fires an immediate compute (the first iteration
        // of the loop runs before any delay). So opening the LAB tab gets
        // fresh data each time.
        if (readinessJob?.isActive != true) {
            readinessJob = viewModelScope.launch {
                while (isActive) {
                    val snap = readinessRepo.computeWithDiagnostics()
                    _readiness.value = snap.result
                    _readinessSnapshot.value = snap
                    delay(READINESS_POLL_INTERVAL_MS)
                }
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        readinessJob?.cancel()
        readinessJob = null
    }

    /** User-triggered readiness re-fetch with visible loading state. */
    fun refreshReadiness() {
        viewModelScope.launch {
            _readinessSyncing.value = true
            try {
                val snap = readinessRepo.computeWithDiagnostics()
                _readiness.value = snap.result
                _readinessSnapshot.value = snap
            } finally {
                _readinessSyncing.value = false
            }
        }
    }

    suspend fun refresh() {
        _state.value = _state.value.copy(refreshing = true)
        val items = buildList {
            add(checkLocationPermission())
            add(checkLocationServices())
            add(checkNotificationPermission())
            add(checkHealthConnectInstalled())
            add(checkHealthConnectPermission())
            add(checkHealthConnectRecentHr())
            add(checkBatteryOptimization())
            add(checkOpenTracksInstalled())
        }
        _state.value = ChecklistState(items = items, refreshing = false)
    }

    private fun checkNotificationPermission(): CheckItem {
        // Android 13+ requires runtime permission for notifications. Below 13 it's implicit.
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return CheckItem(
            id = CheckId.NotificationPermission,
            title = "Notification permission",
            description = if (granted) "Lock-screen telemetry will appear during rides"
            else "URUJ shows live numbers on your lock screen via a notification",
            status = if (granted) CheckStatus.Pass else CheckStatus.Fail,
            canFix = !granted,
        )
    }

    private fun checkLocationPermission(): CheckItem {
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return CheckItem(
            id = CheckId.LocationPermission,
            title = "Location permission",
            description = if (granted) "Granted"
            else "Required for GPS speed, distance, route",
            status = if (granted) CheckStatus.Pass else CheckStatus.Fail,
            canFix = !granted,
        )
    }

    private fun checkLocationServices(): CheckItem {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val enabled = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        return CheckItem(
            id = CheckId.LocationServices,
            title = "Location services (GPS)",
            description = if (enabled) "Enabled" else "Turn on GPS in system settings",
            status = if (enabled) CheckStatus.Pass else CheckStatus.Fail,
            canFix = !enabled,
        )
    }

    private fun checkHealthConnectInstalled(): CheckItem {
        return when (HealthConnectClient.getSdkStatus(ctx)) {
            HealthConnectClient.SDK_AVAILABLE -> CheckItem(
                id = CheckId.HealthConnectInstalled,
                title = "Health Connect",
                description = "Available",
                status = CheckStatus.Pass,
                canFix = false,
            )
            else -> CheckItem(
                id = CheckId.HealthConnectInstalled,
                title = "Health Connect",
                description = "Install or update Health Connect to read HR from your Fit Band 3",
                status = CheckStatus.Fail,
                canFix = true,
            )
        }
    }

    private suspend fun checkHealthConnectPermission(): CheckItem {
        if (HealthConnectClient.getSdkStatus(ctx) != HealthConnectClient.SDK_AVAILABLE) {
            return CheckItem(
                id = CheckId.HealthConnectPermission,
                title = "Heart-rate permission",
                description = "Waiting on Health Connect",
                status = CheckStatus.Pending,
                canFix = false,
            )
        }
        val granted = runCatching {
            val client = HealthConnectClient.getOrCreate(ctx)
            HR_READ_PERMISSION in client.permissionController.getGrantedPermissions()
        }.getOrDefault(false)
        return CheckItem(
            id = CheckId.HealthConnectPermission,
            title = "Heart-rate permission",
            description = if (granted) "Granted" else "Allow URUJ to read your HR from Samsung Health",
            status = if (granted) CheckStatus.Pass else CheckStatus.Fail,
            canFix = !granted,
        )
    }

    private suspend fun checkHealthConnectRecentHr(): CheckItem {
        val sdkOk = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE
        if (!sdkOk) {
            return CheckItem(
                id = CheckId.HealthConnectRecentHr,
                title = "Post-ride HR data",
                description = "Waiting on Health Connect",
                status = CheckStatus.Pending,
                canFix = false,
            )
        }
        // Verifying the pipeline works end-to-end: Samsung Health → Health Connect → URUJ.
        // 24-hour window because Samsung writes HR opportunistically (workouts, passive
        // samples) on its own schedule. If anything has landed in the last day, the
        // pipeline is healthy and post-ride analytics will work.
        val hasData = runCatching {
            val client = HealthConnectClient.getOrCreate(ctx)
            if (HR_READ_PERMISSION !in client.permissionController.getGrantedPermissions()) {
                return@runCatching false
            }
            val now = Instant.now()
            client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minus(Duration.ofHours(24)), now),
                    ascendingOrder = false,
                    pageSize = 1,
                )
            ).records.isNotEmpty()
        }.getOrDefault(false)
        return CheckItem(
            id = CheckId.HealthConnectRecentHr,
            title = "Post-ride HR data",
            description = if (hasData)
                "Pipeline healthy — Samsung Health → Health Connect → URUJ working"
            else
                "No HR in last 24h. Live HR isn't possible via Samsung; for analytics, wear the band and check Samsung Health → Permissions → Health Connect.",
            status = if (hasData) CheckStatus.Pass else CheckStatus.Warning,
            // Informational only — no FIX button. The "fix" is wearing the band, not
            // anything we can deep-link to. Live HR needs a BLE strap, which isn't a
            // button we can offer either.
            canFix = false,
        )
    }

    private fun checkBatteryOptimization(): CheckItem {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val unrestricted = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        return CheckItem(
            id = CheckId.BatteryOptimization,
            title = "Battery optimization disabled",
            description = if (unrestricted) "URUJ runs unrestricted"
            else "Set to 'Don't optimize' / 'Unrestricted' — Android will kill the recorder mid-ride otherwise",
            status = if (unrestricted) CheckStatus.Pass else CheckStatus.Fail,
            canFix = !unrestricted,
        )
    }

    private fun checkOpenTracksInstalled(): CheckItem {
        val installed = try {
            ctx.packageManager.getPackageInfo(OPENTRACKS_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        return CheckItem(
            id = CheckId.OpenTracksInstalled,
            title = "OpenTracks (companion logger)",
            description = if (installed) "Installed — canonical .gpx backup"
            else "Optional: run alongside URUJ for canonical .gpx recording",
            status = if (installed) CheckStatus.Pass else CheckStatus.Warning,
            canFix = !installed,
        )
    }

    private companion object {
        /** v0.8.4 — readiness re-poll interval. 5 min keeps HC reads under
         *  quota. SYNC button on the readiness card forces an immediate
         *  fresh fetch independent of this loop. */
        const val READINESS_POLL_INTERVAL_MS = 5L * 60L * 1000L
    }
}
