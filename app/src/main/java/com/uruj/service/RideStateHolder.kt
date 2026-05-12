package com.uruj.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single source of truth for ride state. The foreground service writes; the HUD,
 * checklist, and post-ride summary all subscribe.
 *
 * Two flows:
 *  - `state` is the LIVE state during a ride (and (), the initial state otherwise).
 *  - `completedRide` is the final-state snapshot when STOP is tapped — non-null only
 *    until the user dismisses the summary. The summary screen subscribes to this
 *    and clears it via `dismissCompleted()` when the user is done.
 */
object RideStateHolder {
    private val _state = MutableStateFlow(RideState())
    val state: StateFlow<RideState> = _state.asStateFlow()

    private val _completedRide = MutableStateFlow<RideState?>(null)
    val completedRide: StateFlow<RideState?> = _completedRide.asStateFlow()

    fun update(transform: (RideState) -> RideState) {
        _state.update(transform)
    }

    /** Called by the service when STOP is tapped — snapshots final state for the summary. */
    fun completeRide() {
        val final = _state.value
        if (final.isRecording || final.sessionId != null) {
            _completedRide.value = final.copy(isRecording = false)
        }
        _state.value = RideState()
    }

    /** Called by the summary screen when the user dismisses it. */
    fun dismissCompleted() {
        _completedRide.value = null
    }

    fun reset() {
        _state.value = RideState()
        _completedRide.value = null
    }
}
