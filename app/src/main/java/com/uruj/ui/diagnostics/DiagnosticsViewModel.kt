package com.uruj.ui.diagnostics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uruj.data.HcDataTypeStatus
import com.uruj.data.HealthConnectInventoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = HealthConnectInventoryRepository(application)

    private val _inventory = MutableStateFlow<List<HcDataTypeStatus>>(emptyList())
    val inventory: StateFlow<List<HcDataTypeStatus>> = _inventory.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _lastRefreshAtMs = MutableStateFlow<Long?>(null)
    val lastRefreshAtMs: StateFlow<Long?> = _lastRefreshAtMs.asStateFlow()

    /**
     * v0.7.10 — monotonic counter that increments on every `refresh()`.
     * Child cards on the Pipeline tab (WearTimeCard, ContinuousMonitorCard,
     * future cards) observe this and re-fetch their own data when the
     * counter changes. Previously the refresh button only refreshed the
     * HC inventory listing — now it cascades to every card on the tab.
     */
    private val _refreshTrigger = MutableStateFlow(0L)
    val refreshTrigger: StateFlow<Long> = _refreshTrigger.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                _inventory.value = repo.inventory()
                _lastRefreshAtMs.value = System.currentTimeMillis()
                _refreshTrigger.value = _refreshTrigger.value + 1
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
