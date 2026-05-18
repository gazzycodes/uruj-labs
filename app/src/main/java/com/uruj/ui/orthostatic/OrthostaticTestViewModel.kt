package com.uruj.ui.orthostatic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uruj.data.BiometricSettingsStore
import com.uruj.data.BleSettingsStore
import com.uruj.data.OrthostaticTestRepository
import com.uruj.domain.OrthostaticInterpretation
import com.uruj.domain.OrthostaticTestResult
import com.uruj.power.OrthostaticTestCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlinx.coroutines.delay

/**
 * v0.7.1 — state machine driving the OrthostaticTestScreen. Phases:
 *
 *   Idle → Instructions → PrepCountdown(30s) → Seated(2min) → Transition(10s)
 *     → Standing(2min) → Computing → Result
 *
 * Captures rely on the 24/7 BiometricService writing RR samples to NDJSON
 * throughout the test. After Standing completes, we slice the NDJSON by the
 * recorded phase timestamps and run OrthostaticTestCalculator.
 *
 * Preconditions checked at start:
 *   - BLE strap paired in BiometricSettingsStore
 *   - 24/7 monitoring toggle enabled
 *
 * If either is missing, surface a friendly error rather than launching a test
 * that won't have any data to compute against.
 */
class OrthostaticTestViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = OrthostaticTestRepository(application)
    private val calc = OrthostaticTestCalculator()
    private val settingsStore = BiometricSettingsStore(application)
    private val bleStore = BleSettingsStore(application)

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun startTest() {
        val current = _state.value
        if (current !is UiState.Idle && current !is UiState.Error && current !is UiState.Result) return

        viewModelScope.launch {
            val settings = settingsStore.current()
            val paired = bleStore.current()
            if (!settings.enabled || paired == null) {
                _state.value = UiState.Error(
                    "24/7 monitoring is off or strap unpaired. Enable in Pipeline → " +
                        "Biometric monitoring + pair the strap, then return here."
                )
                return@launch
            }
            runProtocol()
        }
    }

    fun reset() {
        _state.value = UiState.Idle
    }

    private suspend fun runProtocol() {
        val sessionId = UUID.randomUUID().toString().take(8)
        val startedAtMs = System.currentTimeMillis()

        // ── Instructions (user-driven; auto-advance after 5s reading time) ──
        _state.value = UiState.Instructions(sessionId = sessionId)
        delay(4_000L)

        // ── Prep countdown (30s — let HR stabilize at seated baseline) ──
        for (sec in PREP_SECONDS downTo 1) {
            _state.value = UiState.PrepCountdown(sessionId = sessionId, secondsRemaining = sec)
            delay(1_000L)
        }

        // ── Seated capture (2 min) ──
        val seatedStart = System.currentTimeMillis()
        for (sec in PHASE_SECONDS downTo 1) {
            _state.value = UiState.Seated(
                sessionId = sessionId,
                secondsRemaining = sec,
                phaseStartMs = seatedStart,
            )
            delay(1_000L)
        }
        val seatedEnd = System.currentTimeMillis()

        // ── Transition (10s — sharp stand cue) ──
        for (sec in TRANSITION_SECONDS downTo 1) {
            _state.value = UiState.Transition(sessionId = sessionId, secondsRemaining = sec)
            delay(1_000L)
        }

        // ── Standing capture (2 min) ──
        val standingStart = System.currentTimeMillis()
        for (sec in PHASE_SECONDS downTo 1) {
            _state.value = UiState.Standing(
                sessionId = sessionId,
                secondsRemaining = sec,
                phaseStartMs = standingStart,
            )
            delay(1_000L)
        }
        val standingEnd = System.currentTimeMillis()

        // ── Computing — slice the 24/7 NDJSON and run the math ──
        _state.value = UiState.Computing
        val result = withContext(Dispatchers.IO) {
            val seatedSamples = repo.samplesForWindow(seatedStart, seatedEnd)
            val standingSamples = repo.samplesForWindow(standingStart, standingEnd)
            calc.compute(
                sessionId = sessionId,
                startedAtMs = startedAtMs,
                seatedSamples = seatedSamples,
                seatedStartMs = seatedStart,
                seatedEndMs = seatedEnd,
                standingSamples = standingSamples,
                standingStartMs = standingStart,
                standingEndMs = standingEnd,
            )
        }
        if (result == null) {
            _state.value = UiState.Error(
                "Not enough clean RR data captured. Check strap contact (electrodes " +
                    "moist + snug) and that 24/7 monitoring service is alive. Try again."
            )
            return
        }
        withContext(Dispatchers.IO) { repo.save(result) }
        val interp = calc.interpret(result)
        _state.value = UiState.Result(result = result, interpretation = interp)
    }

    sealed interface UiState {
        data object Idle : UiState
        data class Instructions(val sessionId: String) : UiState
        data class PrepCountdown(val sessionId: String, val secondsRemaining: Int) : UiState
        data class Seated(
            val sessionId: String,
            val secondsRemaining: Int,
            val phaseStartMs: Long,
        ) : UiState
        data class Transition(val sessionId: String, val secondsRemaining: Int) : UiState
        data class Standing(
            val sessionId: String,
            val secondsRemaining: Int,
            val phaseStartMs: Long,
        ) : UiState
        data object Computing : UiState
        data class Result(
            val result: OrthostaticTestResult,
            val interpretation: OrthostaticInterpretation,
        ) : UiState
        data class Error(val message: String) : UiState
    }

    companion object {
        const val PREP_SECONDS = 30
        const val PHASE_SECONDS = 120
        const val TRANSITION_SECONDS = 10
    }
}
