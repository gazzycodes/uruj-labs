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
     * v0.9.15 — rotates the vocab pool index per cue. Within a single ride,
     * the rider hears 4 different variations before repeating. Across rides
     * (new SessionCoach instance) the cycle starts fresh — no per-rider
     * persistence yet, future improvement.
     */
    private var cueIndex: Int = 0

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

    /**
     * v0.9.15 — vocab expansion. Pre-fix: one phrase per situation, robotic
     * repetition within a ride. Now: pool of 4 variations per situation,
     * deterministic rotation by cue-fire counter + ride-start-of-day so the
     * sequence varies across rides + within a single ride. Tone shifted
     * from clinical to "real coach talking to you" — short, direct,
     * encouraging not nagging.
     *
     * Coach voice principles applied to the vocab:
     * - Lead with the action ("ease off", "push", "hold it") not the data
     * - Acknowledge effort ("you've got the ride", "settle in")
     * - Direction-specific phrasing — "above" cues vs "below" cues differ
     *   meaningfully, not just inverted
     * - Recovery-tier intents (RECOVERY, EXPLORATORY) stay extra-light tone
     * - Threshold/VO2 cues sharper ("hold it", "push") — match effort
     */
    private fun buildCue(intent: SessionIntent, currentZone: Int, isAbove: Boolean): String {
        val pool = pickVocabPool(intent, currentZone, isAbove)
        if (pool.isEmpty()) return ""
        cueIndex++
        return pool[((cueIndex % pool.size) + pool.size) % pool.size]
    }

    private fun pickVocabPool(
        intent: SessionIntent,
        currentZone: Int,
        isAbove: Boolean,
    ): List<String> = when (intent) {
        SessionIntent.RECOVERY -> when {
            isAbove && currentZone >= 4 -> POOL_RECOVERY_WAY_OVER
            isAbove -> POOL_RECOVERY_ABOVE
            else -> emptyList()  // below Z1 on recovery = fine, no cue
        }
        SessionIntent.ENDURANCE -> when {
            isAbove && currentZone >= 4 -> POOL_ENDURANCE_WAY_OVER
            isAbove -> POOL_ENDURANCE_ABOVE
            else -> POOL_ENDURANCE_BELOW
        }
        SessionIntent.TEMPO -> when {
            isAbove -> POOL_TEMPO_ABOVE
            else -> POOL_TEMPO_BELOW
        }
        SessionIntent.THRESHOLD -> when {
            isAbove -> POOL_THRESHOLD_ABOVE
            else -> POOL_THRESHOLD_BELOW
        }
        SessionIntent.VO2 -> when {
            isAbove -> POOL_VO2_RECOVERY
            else -> POOL_VO2_BELOW
        }
        SessionIntent.EXPLORATORY -> emptyList()
    }

    companion object {
        /** Drift must hold this long before a cue fires. Hill climbs / wind
         *  gusts shorter than this don't trigger. */
        const val SUSTAINED_DRIFT_MS = 30_000L
        /** Minimum gap between successive cues — no nagging. */
        const val MIN_CUE_INTERVAL_MS = 60_000L
        /** Quiet period after a mid-ride intent change. */
        const val GRACE_PERIOD_MS = 60_000L

        // region v0.9.15 vocab pools — 4 variations per situation
        // Coach voice: lead with action, acknowledge effort, direction-specific
        // phrasing. Tone matches the intensity tier (recovery = soft, threshold
        // = sharp).

        private val POOL_RECOVERY_WAY_OVER = listOf(
            "That's threshold work. Pull right back to recovery.",
            "Whoa — drop the pace. This is a recovery day.",
            "Way too hard for today. Easy gears, easy breath.",
            "Stop pushing. Recovery means recovery.",
        )

        private val POOL_RECOVERY_ABOVE = listOf(
            "Ease back to zone one. Recovery day.",
            "Drop the gear, let the heart rate settle.",
            "Easy spin only. You're above recovery.",
            "Pull it back. Tomorrow you train, today you recover.",
        )

        private val POOL_ENDURANCE_WAY_OVER = listOf(
            "Pulling into threshold. Settle back to zone two.",
            "Too hot for endurance. Drop a gear and breathe.",
            "Above zone three. Ease back, save it for the intervals.",
            "You're cooking. Pull back to zone two — long ride ahead.",
        )

        private val POOL_ENDURANCE_ABOVE = listOf(
            "Drifting up. Ease back into zone two.",
            "Climbing the zones — bring it down to zone two.",
            "Slight tap on the brakes. Zone two endurance pace.",
            "Above target. Settle back, conversational pace.",
        )

        private val POOL_ENDURANCE_BELOW = listOf(
            "Pick up the pace into zone two.",
            "You've got more — push to zone two.",
            "Cruising too easy. Step it up a notch.",
            "Find the rhythm — zone two endurance.",
        )

        private val POOL_TEMPO_ABOVE = listOf(
            "Above tempo. Settle back to zone three.",
            "Sharpening into threshold — back off to tempo.",
            "Hold zone three. Save the legs.",
            "Tempo, not threshold. Ease the watts.",
        )

        private val POOL_TEMPO_BELOW = listOf(
            "Push into zone three. Tempo pace.",
            "Step it up — tempo work today.",
            "Below target. Find tempo, hold it.",
            "More gear. Zone three sustained.",
        )

        private val POOL_THRESHOLD_ABOVE = listOf(
            "Above threshold. Hold it, don't blow up.",
            "Too hot — settle back to threshold pace.",
            "Manage it. Sustain, don't surge.",
            "Settle. Threshold is hard but steady.",
        )

        private val POOL_THRESHOLD_BELOW = listOf(
            "Push to threshold. Hold zone four.",
            "Step it up — threshold work, no easing.",
            "More watts. Threshold pace, sustain it.",
            "Find zone four. Commit to the effort.",
        )

        private val POOL_VO2_RECOVERY = listOf(
            "Recovery between intervals. Let it drop.",
            "Easy spin. Get ready for the next one.",
            "Breathe. Heart rate down, prep for the push.",
            "Recover hard. Next interval needs your best.",
        )

        private val POOL_VO2_BELOW = listOf(
            "Push to zone five. VO2 effort.",
            "All in. Maximum sustainable for this interval.",
            "Bury it — VO2 max work.",
            "Full effort. Hold zone five.",
        )

        // endregion
    }
}
