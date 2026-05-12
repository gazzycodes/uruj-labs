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

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                _inventory.value = repo.inventory()
                _lastRefreshAtMs.value = System.currentTimeMillis()
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
