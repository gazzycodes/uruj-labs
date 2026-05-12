package com.uruj.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uruj.data.RideHistoryRepository
import com.uruj.data.StoredRideSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RideHistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = RideHistoryRepository(application)

    private val _rides = MutableStateFlow<List<StoredRideSummary>>(emptyList())
    val rides: StateFlow<List<StoredRideSummary>> = _rides.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                // Sweep up orphan NDJSON files — typically rides whose service was killed
                // before stopRecording could write a summary. Idempotent and cheap.
                repo.recoverOrphanRides()
                repo.listAll()
            }
            _rides.value = list
        }
    }

    fun delete(sessionId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(sessionId) }
            refresh()
        }
    }
}
