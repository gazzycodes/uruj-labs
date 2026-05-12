package com.uruj.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uruj.data.RiderProfileStore
import com.uruj.domain.RiderProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RiderProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val store = RiderProfileStore(application)

    private val _profile = MutableStateFlow(RiderProfile())
    val profile: StateFlow<RiderProfile> = _profile.asStateFlow()

    init {
        viewModelScope.launch {
            store.profile.collect { _profile.value = it }
        }
    }

    fun save(profile: RiderProfile) {
        viewModelScope.launch {
            store.save(profile)
        }
    }
}
