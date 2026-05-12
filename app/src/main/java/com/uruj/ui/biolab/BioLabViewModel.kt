package com.uruj.ui.biolab

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uruj.data.BioLabRepository
import com.uruj.data.BioLabSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BioLabViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = BioLabRepository(application)

    private val _snapshot = MutableStateFlow<BioLabSnapshot?>(null)
    val snapshot: StateFlow<BioLabSnapshot?> = _snapshot.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                _snapshot.value = repo.snapshot()
            } finally {
                _isLoading.value = false
            }
        }
    }
}
