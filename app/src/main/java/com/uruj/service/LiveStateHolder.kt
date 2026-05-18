package com.uruj.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.sqrt

/**
 * v0.8.1 — beat-by-beat live state shared between the BiometricService
 * (24/7 BLE) and RideRecorderService (active ride). Both services write
 * here on every BLE notification; LiveScreen + HUD inline waveform observe
 * via StateFlow.
 *
 * Singleton pattern mirrors RideStateHolder. No DI framework needed; the
 * BLE pipeline is owned by foreground services + the observer is the UI.
 *
 * Holds a rolling ring buffer of the most recent `MAX_BEATS` beats. Each
 * beat carries timestamp + bpm + the RR interval that ENDED at that beat.
 *
 * Why beats (not raw HrSamples): each BLE notification can carry 1-3 RR
 * intervals representing 1-3 distinct beats. Expanding to per-beat upfront
 * makes the waveform + RMSSD computations trivial downstream.
 */
object LiveStateHolder {

    /** One detected heartbeat with the RR interval ending at it.
     *
     *  v0.8.1 fix — `isSynthetic` flags beats whose RR was derived from bpm
     *  (the BLE notification didn't include real RR data). Synthetic beats
     *  are FINE for the waveform display (we still know the BPM accurately)
     *  but MUST be excluded from rolling RMSSD because their RRs have zero
     *  variance by construction → would falsely report RMSSD≈0. */
    data class Beat(
        val timestampMs: Long,
        val bpm: Int,
        val rrMs: Int,
        val isSynthetic: Boolean = false,
    )

    data class State(
        /** Most recent ring-buffered beats (oldest first). */
        val recentBeats: List<Beat> = emptyList(),
        /** When the most recent BLE notification arrived. */
        val lastUpdatedMs: Long = 0L,
    ) {
        val latestBpm: Int? get() = recentBeats.lastOrNull()?.bpm
        val latestRrMs: Int? get() = recentBeats.lastOrNull()?.rrMs

        /** True if a beat has arrived in the last 5 seconds. */
        fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
            lastUpdatedMs > 0 && (nowMs - lastUpdatedMs) < FRESHNESS_MS

        /**
         * Rolling RMSSD over the last [windowMs] of beats. Returns null when
         * fewer than [MIN_DIFFS_FOR_RMSSD] qualifying consecutive-pair diffs
         * exist. Same physiological + ectopic filters as overnight HrvCalc.
         */
        fun rollingRmssdMs(
            windowMs: Long = ROLLING_RMSSD_WINDOW_MS,
            nowMs: Long = System.currentTimeMillis(),
        ): Float? {
            val cutoff = nowMs - windowMs
            val recent = recentBeats
                .filter { it.timestampMs >= cutoff }
                .filter { !it.isSynthetic } // v0.8.1 fix — synthetic RR pollutes RMSSD
                .filter { it.rrMs in PHYSIOLOGICAL_RR_MIN..PHYSIOLOGICAL_RR_MAX }
            if (recent.size < 2) return null
            val diffs = mutableListOf<Int>()
            for (i in 1 until recent.size) {
                val prev = recent[i - 1].rrMs
                val curr = recent[i].rrMs
                val deltaPct = kotlin.math.abs(curr - prev).toFloat() / prev
                if (deltaPct > ECTOPIC_THRESHOLD) continue
                diffs.add(curr - prev)
            }
            if (diffs.size < MIN_DIFFS_FOR_RMSSD) return null
            val meanSq = diffs.map { (it * it).toDouble() }.average()
            return sqrt(meanSq).toFloat()
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Called by BiometricService + RideRecorderService on each BLE
     * notification. Expands each RR interval into a separate Beat with
     * back-calculated timestamps (latest beat at sampleReceivedAtMs, prior
     * beats stepped back by their RR).
     */
    fun onBleNotification(
        receivedAtMs: Long,
        bpm: Int,
        rrIntervalsMs: List<Int>,
    ) {
        if (bpm <= 0) {
            // Off-skin / no contact / strap noise — skip. v0.8.x notification
            // hygiene defer still emits these to NDJSON but Live state filters.
            return
        }
        val newBeats = mutableListOf<Beat>()
        if (rrIntervalsMs.isEmpty()) {
            // BLE notification without RR data — synthesize a single beat at
            // receivedAtMs with rrMs derived from bpm. Lower precision but keeps
            // the waveform alive when strap reports beats without RR.
            // Flagged isSynthetic=true so rolling RMSSD excludes it (zero
            // variance by construction would falsely report ~0 ms RMSSD).
            val syntheticRr = (60_000 / bpm.coerceAtLeast(1)).coerceIn(
                PHYSIOLOGICAL_RR_MIN, PHYSIOLOGICAL_RR_MAX,
            )
            newBeats += Beat(receivedAtMs, bpm, syntheticRr, isSynthetic = true)
        } else {
            // Per BLE HRP spec: RR intervals are ordered oldest-to-newest.
            // Latest beat lands at receivedAtMs; earlier beats step back by RR.
            var t = receivedAtMs
            for (i in rrIntervalsMs.indices.reversed()) {
                val rr = rrIntervalsMs[i]
                if (rr <= 0) continue
                newBeats.add(0, Beat(t, bpm, rr))
                t -= rr
            }
        }
        if (newBeats.isEmpty()) return
        _state.update { current ->
            val combined = current.recentBeats + newBeats
            val trimmed = if (combined.size > MAX_BEATS) {
                combined.subList(combined.size - MAX_BEATS, combined.size)
            } else combined
            current.copy(
                recentBeats = trimmed,
                lastUpdatedMs = receivedAtMs,
            )
        }
    }

    /** Reset on demand (e.g. strap disconnected for a long time, app teardown). */
    fun clear() {
        _state.value = State()
    }

    // ────── helpers ──────
    // v0.8.1 fix — removed local non-atomic `update` extension. Now using
    // `kotlinx.coroutines.flow.update` (imported at top) which uses CAS and
    // is safe under concurrent writes from both services.

    /** Max beats held in the ring buffer. ~300 = 5 min at HR 60. */
    private const val MAX_BEATS = 300
    /** Beat is "fresh" if received within this window. */
    private const val FRESHNESS_MS = 5_000L
    /** Rolling RMSSD computation window. */
    private const val ROLLING_RMSSD_WINDOW_MS = 60_000L
    /** Min consecutive-pair diffs needed for a meaningful rolling RMSSD. */
    private const val MIN_DIFFS_FOR_RMSSD = 10
    /** Physiological RR range — 300-2000ms = 30-200 BPM. */
    private const val PHYSIOLOGICAL_RR_MIN = 300
    private const val PHYSIOLOGICAL_RR_MAX = 2000
    /** Ectopic delta cap (Kubios). */
    private const val ECTOPIC_THRESHOLD = 0.20f
}
