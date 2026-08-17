package com.uruj.power

/**
 * v0.9.78 — SINGLE SOURCE OF TRUTH for Cycling Speed and Cadence (CSC) math.
 *
 * Pure Kotlin, zero Android imports, so every branch here is covered by plain
 * JVM unit tests ([com.uruj.power.CadenceCalculatorTest]) — the BLE transport
 * in [com.uruj.sensor.android.BleCadenceSource] only feeds bytes in and reads
 * rpm out. Same split as [TrainingLoad] / [HrPeakCalculator]: the arithmetic
 * that can silently lie lives where a test can pin it down.
 *
 * ## The wire format (Bluetooth SIG CSC Profile, service 0x1816)
 *
 * CSC Measurement (0x2A5B), little-endian, NOTIFY:
 * ```
 *   byte 0        flags
 *                   bit 0: Wheel Revolution Data Present
 *                   bit 1: Crank Revolution Data Present
 *   if bit 0:     uint32 Cumulative Wheel Revolutions
 *                 uint16 Last Wheel Event Time   (1/1024 s)
 *   if bit 1:     uint16 Cumulative Crank Revolutions
 *                 uint16 Last Crank Event Time   (1/1024 s)
 * ```
 *
 * Both event-time fields are **uint16 counters ticking at 1024 Hz**, so they
 * wrap every 64 seconds; crank revolutions are uint16 and wrap every 65536
 * strokes (~12 h at 90 rpm). Every delta below is computed modulo 2^16 for
 * exactly that reason — a naive subtraction produces a huge negative number
 * once per hour of riding and would have shown a garbage cadence spike.
 *
 * ## How cadence is derived
 *
 * The sensor does NOT send rpm. It sends "how many strokes so far" and "when
 * the last stroke happened". Cadence is the slope between two of those reports:
 * ```
 *   rpm = Δrevolutions / (Δevent_ticks / 1024) × 60
 * ```
 * [CadenceTracker] evaluates that slope across a rolling [CadenceTracker.WINDOW_MS]
 * window rather than between adjacent packets. Why: at low cadence a crank event
 * can be rarer than the ~1 Hz notification rate, so adjacent-packet math
 * alternates between a spike and a zero. A window gives the same number a head
 * unit shows, with no EMA lag bolted on top — the value is still an exact
 * revolutions-over-time measurement, just over a slightly longer base.
 *
 * ## Coasting is a first-class state, not a gap
 *
 * No crank event inside the window ⇒ the rider is freewheeling ⇒ **0 rpm**, not
 * "last known rpm" and not null. A cadence readout that holds 90 while you
 * coast downhill is the kind of quiet lie this codebase exists to avoid.
 */

/** One decoded CSC Measurement (0x2A5B) notification. Nulls mean "field absent". */
data class CscMeasurement(
    /** Cumulative wheel revolutions (uint32). Present only in speed-sensor mode. */
    val cumulativeWheelRevs: Long? = null,
    /** Last wheel event time, 1/1024 s units (uint16). */
    val lastWheelEventTicks: Int? = null,
    /** Cumulative crank revolutions (uint16). Present in cadence-sensor mode. */
    val cumulativeCrankRevs: Int? = null,
    /** Last crank event time, 1/1024 s units (uint16). */
    val lastCrankEventTicks: Int? = null,
) {
    val hasCrankData: Boolean get() = cumulativeCrankRevs != null && lastCrankEventTicks != null
    val hasWheelData: Boolean get() = cumulativeWheelRevs != null && lastWheelEventTicks != null
}

/** Where the sensor says it is mounted — Sensor Location characteristic (0x2A5D). */
object CscSensorLocation {
    private val NAMES = arrayOf(
        "Other", "Top of shoe", "In shoe", "Hip", "Front wheel", "Left crank",
        "Right crank", "Left pedal", "Right pedal", "Front hub", "Rear dropout",
        "Chainstay", "Rear wheel", "Rear hub", "Chest", "Spider", "Chain ring",
    )

    /** Human label, or null when the code is outside the SIG-assigned range. */
    fun label(code: Int?): String? = code?.takeIf { it in NAMES.indices }?.let { NAMES[it] }
}

/** CSC Feature bitfield (0x2A5C, uint16) — what the sensor claims it can report. */
data class CscFeature(val raw: Int) {
    val supportsWheelRevolutions: Boolean get() = (raw and 0x0001) != 0
    val supportsCrankRevolutions: Boolean get() = (raw and 0x0002) != 0
    val supportsMultipleSensorLocations: Boolean get() = (raw and 0x0004) != 0
}

/** Decoder for the raw characteristic payloads. Returns null on anything malformed. */
object CscParser {

    /**
     * Parse a CSC Measurement (0x2A5B) payload.
     *
     * Returns null when the packet is empty, declares no data fields at all, or
     * is shorter than the fields its own flags promise — a truncated notification
     * is dropped rather than half-decoded into plausible-looking garbage.
     */
    fun parseMeasurement(value: ByteArray): CscMeasurement? {
        if (value.isEmpty()) return null
        val flags = value[0].toInt() and 0xFF
        val wheelPresent = (flags and 0x01) != 0
        val crankPresent = (flags and 0x02) != 0
        if (!wheelPresent && !crankPresent) return null

        var idx = 1
        var wheelRevs: Long? = null
        var wheelTicks: Int? = null
        if (wheelPresent) {
            if (value.size < idx + 6) return null
            wheelRevs = u32(value, idx)
            idx += 4
            wheelTicks = u16(value, idx)
            idx += 2
        }
        var crankRevs: Int? = null
        var crankTicks: Int? = null
        if (crankPresent) {
            if (value.size < idx + 4) return null
            crankRevs = u16(value, idx)
            idx += 2
            crankTicks = u16(value, idx)
        }
        return CscMeasurement(
            cumulativeWheelRevs = wheelRevs,
            lastWheelEventTicks = wheelTicks,
            cumulativeCrankRevs = crankRevs,
            lastCrankEventTicks = crankTicks,
        )
    }

    /** Parse the CSC Feature characteristic (0x2A5C, uint16 LE). */
    fun parseFeature(value: ByteArray): CscFeature? {
        if (value.size < 2) return null
        return CscFeature(u16(value, 0))
    }

    /** Parse the Sensor Location characteristic (0x2A5D, uint8). */
    fun parseSensorLocation(value: ByteArray): Int? =
        value.firstOrNull()?.toInt()?.and(0xFF)

    private fun u16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xFF) or
            ((b[i + 1].toLong() and 0xFF) shl 8) or
            ((b[i + 2].toLong() and 0xFF) shl 16) or
            ((b[i + 3].toLong() and 0xFF) shl 24)
}

/**
 * Turns the stream of cumulative crank counters into a live rpm readout.
 *
 * Thread-safety: the BLE notification callback and the ride service's 1 Hz
 * ticker touch this from two different coroutines, so every entry point is
 * `@Synchronized`. Contention is ~2 calls/second — the lock costs nothing and
 * removes a whole class of torn-read weirdness that would only ever show up
 * mid-ride, 40 km from home, where it can't be debugged.
 */
class CadenceTracker {

    /** One packet's unwrapped (monotonically increasing) counters. */
    private data class Reading(val wallMs: Long, val revs: Long, val ticks: Long)

    private val history = ArrayDeque<Reading>()
    private var lastRawRevs: Int? = null
    private var lastRawTicks: Int? = null
    private var unwrappedRevs = 0L
    private var unwrappedTicks = 0L
    private var lastPacketWallMs = 0L
    private var lastCrankEventWallMs = 0L
    private var currentRpm = 0f

    /** Total crank revolutions counted since [reset] — the ride's pedal strokes. */
    @Volatile
    var totalCrankRevs: Long = 0L
        private set

    /** True once at least one crank-bearing packet has been decoded. */
    @Volatile
    var hasSeenCrankData: Boolean = false
        private set

    /**
     * Feed one decoded measurement. Returns the rpm to display right now
     * (0 while coasting), or null when this packet carried no crank data at all
     * (wheel-only sensor, i.e. mounted/configured as a speed sensor).
     */
    @Synchronized
    fun onMeasurement(measurement: CscMeasurement, receivedAtMs: Long): Float? {
        val revs = measurement.cumulativeCrankRevs ?: return null
        val ticks = measurement.lastCrankEventTicks ?: return null
        hasSeenCrankData = true

        val prevRevs = lastRawRevs
        val prevTicks = lastRawTicks
        val prevWallMs = lastPacketWallMs
        lastRawRevs = revs
        lastRawTicks = ticks
        lastPacketWallMs = receivedAtMs

        // First packet of a connection: establish the baseline, emit nothing.
        // A single cumulative reading carries no rate information.
        if (prevRevs == null || prevTicks == null) {
            rebase(receivedAtMs)
            return currentRpm
        }

        val dRevs = (revs - prevRevs + COUNTER_MODULO) % COUNTER_MODULO
        val dTicks = (ticks - prevTicks + COUNTER_MODULO) % COUNTER_MODULO
        val wallGapMs = receivedAtMs - prevWallMs

        // Re-baseline instead of guessing when the delta can't be trusted:
        //  - a gap longer than the 64 s tick-counter period makes Δticks
        //    ambiguous (did it wrap once, or three times?),
        //  - an implausible stroke count in one packet means the sensor
        //    power-cycled and restarted its counters from zero (Magene sensors
        //    sleep when the bike is parked and come back with fresh counters).
        if (wallGapMs > AMBIGUOUS_GAP_MS || wallGapMs < 0 || dRevs > MAX_REVS_PER_PACKET) {
            rebase(receivedAtMs)
            return currentRpm
        }

        unwrappedRevs += dRevs
        unwrappedTicks += dTicks
        totalCrankRevs += dRevs
        if (dTicks > 0 || dRevs > 0) lastCrankEventWallMs = receivedAtMs
        history.addLast(Reading(receivedAtMs, unwrappedRevs, unwrappedTicks))

        // Trim to the rolling window, always keeping at least two readings so a
        // slope is still computable after a long quiet stretch.
        val cutoff = receivedAtMs - WINDOW_MS
        while (history.size > 2 && history[1].wallMs <= cutoff) history.removeFirst()

        currentRpm = computeRpm(receivedAtMs)
        return currentRpm
    }

    /**
     * The rpm to show at [nowMs]. Independent of packet arrival, so the HUD's
     * 1 Hz tick can drop the readout to 0 the moment the rider stops pedalling —
     * cadence sensors go silent while coasting, and "no packet" must never read
     * as "still spinning at 92".
     */
    @Synchronized
    fun currentRpm(nowMs: Long): Float {
        if (lastCrankEventWallMs == 0L) return 0f
        if (nowMs - lastCrankEventWallMs > WINDOW_MS) return 0f
        return currentRpm
    }

    /** Wall-clock time of the most recent packet, or 0 when nothing has arrived. */
    @Synchronized
    fun lastPacketAtMs(): Long = lastPacketWallMs

    /** Clear all state — called when a connection opens so counters start clean. */
    @Synchronized
    fun reset() {
        history.clear()
        lastRawRevs = null
        lastRawTicks = null
        unwrappedRevs = 0L
        unwrappedTicks = 0L
        lastPacketWallMs = 0L
        lastCrankEventWallMs = 0L
        currentRpm = 0f
        totalCrankRevs = 0L
        hasSeenCrankData = false
    }

    /**
     * Drop the slope history but keep the ride's stroke total. Used when a delta
     * is untrustworthy (counter wrap ambiguity / sensor power-cycle): the next
     * packet becomes the new baseline instead of producing a fabricated spike.
     */
    private fun rebase(receivedAtMs: Long) {
        history.clear()
        unwrappedRevs = 0L
        unwrappedTicks = 0L
        history.addLast(Reading(receivedAtMs, 0L, 0L))
        currentRpm = 0f
    }

    /** Slope across the retained window, with a hard physiological ceiling. */
    private fun computeRpm(nowMs: Long): Float {
        val oldest = history.firstOrNull() ?: return 0f
        val newest = history.lastOrNull() ?: return 0f
        val dRevs = newest.revs - oldest.revs
        val dTicks = newest.ticks - oldest.ticks
        // No crank event inside the window → freewheeling, not "unknown".
        if (dTicks <= 0L || dRevs <= 0L) {
            return if (nowMs - lastCrankEventWallMs > WINDOW_MS) 0f else currentRpm
        }
        val rpm = dRevs.toFloat() * 60f * TICKS_PER_SECOND / dTicks.toFloat()
        // A road bike cannot be pedalled past ~250 rpm. Anything above is a
        // decode artifact; keep the previous value rather than flashing garbage.
        return if (rpm > MAX_PLAUSIBLE_RPM) currentRpm else rpm
    }

    companion object {
        /** Crank revolutions and event times are both uint16. */
        private const val COUNTER_MODULO = 65536

        /** Event-time counters tick at 1024 Hz per the CSC spec. */
        private const val TICKS_PER_SECOND = 1024f

        /**
         * Rolling window for the rpm slope AND the coasting timeout — one
         * concept, one constant: "crank revolutions in the last 3.5 s, scaled
         * to a minute". Long enough that a 20 rpm grind up a steep ramp still
         * catches an event, short enough that stopping pedalling shows as 0
         * within about the time it takes to notice you stopped.
         */
        const val WINDOW_MS = 3_500L

        /**
         * Beyond the 64 s wrap period of the uint16 tick counter a delta is
         * ambiguous, so we re-baseline. 60 s leaves margin for scheduler jitter.
         */
        private const val AMBIGUOUS_GAP_MS = 60_000L

        /** More strokes than this in one ~1 s packet ⇒ the sensor reset its counters. */
        private const val MAX_REVS_PER_PACKET = 300

        /** Physiological ceiling. Track sprinters peak near 200; 250 is generous. */
        const val MAX_PLAUSIBLE_RPM = 250f
    }
}
