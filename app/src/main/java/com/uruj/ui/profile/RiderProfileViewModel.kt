package com.uruj.ui.profile

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uruj.data.RiderProfileStore
import com.uruj.data.WeightAutoSync
import com.uruj.domain.RiderProfile
import com.uruj.power.PrTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RiderProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val store = RiderProfileStore(application)
    private val appContext = application.applicationContext
    // v0.9.12 — extracted HC weight sync to its own class with HcReadGuard +
    // 5-min cooldown + post-ride quiet window skip + last-sync timestamp.
    // Old syncFromHealthConnect() inline impl removed (no gating, no audit).
    private val weightSync = WeightAutoSync(application)

    private val _profile = MutableStateFlow(RiderProfile())
    val profile: StateFlow<RiderProfile> = _profile.asStateFlow()

    /** v0.9.12 — surfaces "synced from Samsung X ago" on the weight field. */
    val lastWeightSyncMs: StateFlow<Long?> = MutableStateFlow<Long?>(null).also { sf ->
        viewModelScope.launch {
            store.lastWeightSyncMs.collect { sf.value = it }
        }
    }.asStateFlow()

    init {
        viewModelScope.launch {
            // Auto-sync first so the displayed values match Samsung Health on
            // open. User manual edits still hold for fields HC doesn't write
            // (FTP, tire type, riding position, bike weight, age). Weight gets
            // refreshed via WeightAutoSync (idempotent, cooldown-gated).
            runCatching { weightSync.trySync() }
                .onFailure { Log.w(TAG, "weight sync failed", it) }
            store.profile.collect { _profile.value = it }
        }
    }

    fun save(profile: RiderProfile) {
        viewModelScope.launch {
            store.save(profile)
        }
    }

    /**
     * Clears all stored Personal Records — wipes the 60s / 5min / 20min power
     * ceilings so future rides build fresh PRs. Used to remove pollution from
     * early test rides that left inflated ceilings (pre-v0.1 GPS-quality fix
     * registered indoor walking as kilowatt power spikes).
     */
    fun resetPrs(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                val tracker = PrTracker(appContext)
                tracker.reset()
                Log.d(TAG, "Personal records reset")
            }.onFailure { Log.w(TAG, "PR reset failed", it) }
            onDone()
        }
    }

    companion object {
        private const val TAG = "URUJ-ProfileVM"
    }
}
