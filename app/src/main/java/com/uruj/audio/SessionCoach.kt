package com.uruj.audio

import com.uruj.domain.SessionIntent
import com.uruj.power.KarvonenZonesCalculator

/**
 * v0.8.0 — rule-based audio coach for live ride zone discipline.
 *
 * Watches live HR (BLE strap per-beat or HC fallback). When current HR
 * drifts outside the active SessionIntent's target zone for a sustained
 * period, fires a TTS cue via TtsAnnouncer.
 *
 * Design principles:
 *
 * 1. **Sustained-drift gate.** Brief drifts (1-2 brief beats over a zone
 *    boundary during a climb / descent / wind gust) DON'T trigger. Only
 *    drifts that hold for `SUSTAINED_DRIFT_SECONDS` (default 30s) speak.
 *    Otherwise the coach becomes an annoying chatterbox.
 *
 * 2. **Rate limit.** Even on sustained drift, the coach speaks AT MOST
 *    once per `MIN_CUE_INTERVAL_SECONDS` (default 60s). The rider got
 *    the message; don't nag every 5 sec.
 *
 * 3. **Direction-aware cues.** ABOVE target → "ease off." BELOW target
 *    → "pick up pace." Different lines for different recovery vs hard
 *    sessions (recovery session below = fine, no cue; threshold session
 *    below = "push harder").
 *
 * 4. **EXPLORATORY = silence.** Coach is opt-in. No target zone means
 *    no cues — just the existing km callouts + PRs.
 *
 * 5. **No live HR = silence.** If BLE strap is dropped + HC fallback
 *    isn't fresh, coach has no signal. Stays quiet.
 *
 * 6. **Override grace period.** When the rider switches session intent
 *    mid-ride, the coach goes quiet for `GRACE_PERIOD_SECONDS` so they
 *    can settle into the new target without immediate nagging.
 *
 * This class is pure logic: takes HR samples + state, returns "speak X"
 * or "stay quiet." Owner threads it into RideRecorderService.
 */
class SessionCoach {

    private var lastCueAtMs: Long = 0L
    private var driftStartedAtMs: Long = 0L
    private var lastDriftKey: String? = null
    private var overrideAtMs: Long = 0L

    /**
     * @param hrBpm current HR reading (BLE per-beat or recent HC sample)
     * @param zones current Karvonen zones for the rider (from RiderProfile)
     * @param intent active session intent
     * @param nowMs system time of this HR sample
     * @return TTS line to speak, or null to stay quiet
     */
    fun tick(
        hrBpm: Int,
        zones: KarvonenZonesCalculator.Result,
        intent: SessionIntent,
        nowMs: Long,
    ): String? {
        if (!intent.hasTarget()) return null
        if (hrBpm <= 0) return null

        // Honor override grace period — quiet for N sec after a session change
        if (nowMs - overrideAtMs < GRACE_PERIOD_MS) return null

        val currentZone = currentZoneFor(hrBpm, zones)
        val targetMin = intent.targetZoneMin!!
        val targetMax = intent.targetZoneMax!!

        // In target zone — clear drift state, no cue
        if (currentZone in targetMin..targetMax) {
            driftStartedAtMs = 0L
            lastDriftKey = null
            return null
        }

        // Out of target. Identify drift direction.
        val isAbove = currentZone > targetMax
        val driftKey = if (isAbove) "above:${currentZone}" else "below:${currentZone}"

        // New drift direction or first drift — start the sustained-timer
        if (driftStartedAtMs == 0L || driftKey != lastDriftKey) {
            driftStartedAtMs = nowMs
            lastDriftKey = driftKey
            return null
        }

        // Still drifting in the same direction — has it been sustained?
        if (nowMs - driftStartedAtMs < SUSTAINED_DRIFT_MS) return null

        // Rate limit — don't speak again within MIN_CUE_INTERVAL_MS of last cue
        if (nowMs - lastCueAtMs < MIN_CUE_INTERVAL_MS) return null

        // Commit to speaking
        lastCueAtMs = nowMs
        return buildCue(intent, currentZone, isAbove)
    }

    /**
     * Called when the rider overrides the session intent mid-ride. Starts
     * a grace period so the coach doesn't immediately nag about the new
     * target before they have a chance to adjust.
     */
    fun onIntentChanged(nowMs: Long) {
        overrideAtMs = nowMs
        driftStartedAtMs = 0L
        lastDriftKey = null
    }

    private fun currentZoneFor(hrBpm: Int, zones: KarvonenZonesCalculator.Result): Int {
        // Below Z1 lower bound → "Z0" effectively, but we report Z1 (below
        // recovery still rounds up to "easy"). Above Z5 upper bound → Z5
        // (capped). Inside a zone band → that zone.
        if (hrBpm < (zones.zones.firstOrNull()?.lowerBpm ?: 0)) return 1
        for (z in zones.zones) {
            if (hrBpm <= z.upperBpm) return z.number
        }
        return zones.zones.lastOrNull()?.number ?: 5
    }

    private fun buildCue(intent: SessionIntent, currentZone: Int, isAbove: Boolean): String {
        return when (intent) {
            SessionIntent.RECOVERY -> when {
                isAbove && currentZone >= 4 -> "Way too hard. Drop to recovery pace."
                isAbove -> "Recovery session — ease back to zone 1."
                else -> ""  // below Z1 means almost stopped; no cue
            }
            SessionIntent.ENDURANCE -> when {
                isAbove && currentZone >= 4 -> "Pulling into threshold. Ease back to zone 2 endurance."
                isAbove -> "Drifting into zone $currentZone. Ease back to zone 2 endurance."
                else -> "Below zone 2. Pick up the pace."
            }
            SessionIntent.TEMPO -> when {
                isAbove -> "Above tempo. Ease back to zone 3."
                else -> "Below tempo. Push harder into zone 3."
            }
            SessionIntent.THRESHOLD -> when {
                isAbove -> "Above threshold. Settle back to zone 4."
                else -> "Below threshold. Push harder into zone 4."
            }
            SessionIntent.VO2 -> when {
                isAbove -> "Recovery between intervals — let HR drop."
                else -> "VO2 effort. Push into zone 5."
            }
            SessionIntent.EXPLORATORY -> ""
        }.ifBlank { "Off target. Current zone $currentZone." }
    }

    companion object {
        /** Drift must hold this long before a cue fires. Hill climbs / wind
         *  gusts shorter than this don't trigger. */
        const val SUSTAINED_DRIFT_MS = 30_000L
        /** Minimum gap between successive cues — no nagging. */
        const val MIN_CUE_INTERVAL_MS = 60_000L
        /** Quiet period after a mid-ride intent change. */
        const val GRACE_PERIOD_MS = 60_000L
    }
}
