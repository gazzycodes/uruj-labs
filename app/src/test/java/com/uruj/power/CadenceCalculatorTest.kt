package com.uruj.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * v0.9.78 — covers the CSC decode + cadence math that the Magene S314 feeds.
 *
 * Everything the sensor can throw at us that is NOT reproducible on a bike ride
 * (counter wrap after an hour, a sensor power-cycle, a truncated packet, a
 * 64-second coast) is pinned here instead, because the alternative is finding
 * out at km 60.
 */
class CadenceCalculatorTest {

    // ─────────────────────────── packet decoding ───────────────────────────

    /** Crank-only packet — what the S314 sends mounted on the left crank. */
    private fun crankPacket(revs: Int, ticks: Int): ByteArray = byteArrayOf(
        0x02, // flags: crank present, wheel absent
        (revs and 0xFF).toByte(), ((revs shr 8) and 0xFF).toByte(),
        (ticks and 0xFF).toByte(), ((ticks shr 8) and 0xFF).toByte(),
    )

    @Test
    fun `decodes a crank-only measurement`() {
        val m = CscParser.parseMeasurement(crankPacket(revs = 1234, ticks = 40000))
        assertNotNull(m)
        assertEquals(1234, m!!.cumulativeCrankRevs)
        assertEquals(40000, m.lastCrankEventTicks)
        assertTrue(m.hasCrankData)
        assertFalse(m.hasWheelData)
    }

    @Test
    fun `decodes a combined wheel plus crank measurement`() {
        // flags=0x03, wheel revs=0x000004D2 (1234), wheel ticks=0x0100 (256),
        // crank revs=0x0064 (100), crank ticks=0x0200 (512)
        val bytes = byteArrayOf(
            0x03,
            0xD2.toByte(), 0x04, 0x00, 0x00,
            0x00, 0x01,
            0x64, 0x00,
            0x00, 0x02,
        )
        val m = CscParser.parseMeasurement(bytes)!!
        assertEquals(1234L, m.cumulativeWheelRevs)
        assertEquals(256, m.lastWheelEventTicks)
        assertEquals(100, m.cumulativeCrankRevs)
        assertEquals(512, m.lastCrankEventTicks)
    }

    @Test
    fun `wheel-only measurement decodes but carries no crank data`() {
        val bytes = byteArrayOf(0x01, 0x10, 0x00, 0x00, 0x00, 0x00, 0x01)
        val m = CscParser.parseMeasurement(bytes)!!
        assertTrue(m.hasWheelData)
        assertFalse(m.hasCrankData)
        // The tracker must refuse to invent cadence from wheel data.
        assertNull(CadenceTracker().onMeasurement(m, 1_000L))
    }

    @Test
    fun `truncated and empty packets are rejected, never half-decoded`() {
        assertNull(CscParser.parseMeasurement(byteArrayOf()))
        // Flags promise crank data but only 2 of the 4 payload bytes arrived.
        assertNull(CscParser.parseMeasurement(byteArrayOf(0x02, 0x01, 0x00)))
        // Flags promise wheel data, payload too short.
        assertNull(CscParser.parseMeasurement(byteArrayOf(0x01, 0x01, 0x00, 0x00)))
        // Flags declare no fields at all.
        assertNull(CscParser.parseMeasurement(byteArrayOf(0x00)))
    }

    @Test
    fun `feature bitfield and sensor location decode`() {
        val f = CscParser.parseFeature(byteArrayOf(0x02, 0x00))!!
        assertTrue(f.supportsCrankRevolutions)
        assertFalse(f.supportsWheelRevolutions)
        assertNull(CscParser.parseFeature(byteArrayOf(0x02)))
        // 5 = Left crank — exactly where the rider mounted the S314.
        assertEquals("Left crank", CscSensorLocation.label(CscParser.parseSensorLocation(byteArrayOf(0x05))))
        assertNull(CscSensorLocation.label(99))
    }

    // ─────────────────────────── cadence slope ───────────────────────────

    /**
     * Simulates a real sensor: one notification per second, but the crank
     * counters only advance on an actual stroke, and the event time stamps WHEN
     * that stroke happened (not when the packet was sent). That distinction is
     * the whole reason cadence needs event-time math instead of wall clocks.
     *
     * Returns the rpm after the final packet.
     */
    private fun steadyCadence(
        tracker: CadenceTracker,
        rpm: Int,
        seconds: Int,
        startWallMs: Long = 10_000L,
        startRevs: Int = 0,
        startTicks: Int = 0,
    ): Float {
        val ticksPerStroke = 61_440.0 / rpm // 1024 ticks/s × 60 s per minute
        var last = 0f
        for (s in 0..seconds) {
            val strokes = s * rpm / 60 // completed strokes by second s
            val revs = (startRevs + strokes) % 65536
            val ticks = ((startTicks + (strokes * ticksPerStroke).roundToInt()) % 65536)
            val r = tracker.onMeasurement(
                CscMeasurement(cumulativeCrankRevs = revs, lastCrankEventTicks = ticks),
                startWallMs + s * 1_000L,
            )
            if (r != null) last = r
        }
        return last
    }

    @Test
    fun `steady 90 rpm reads 90 rpm`() {
        val tracker = CadenceTracker()
        val rpm = steadyCadence(tracker, rpm = 90, seconds = 10)
        assertEquals(90f, rpm, 1.5f)
        assertTrue(tracker.hasSeenCrankData)
    }

    @Test
    fun `first packet alone cannot produce a cadence`() {
        val tracker = CadenceTracker()
        val rpm = tracker.onMeasurement(
            CscMeasurement(cumulativeCrankRevs = 500, lastCrankEventTicks = 2048),
            1_000L,
        )
        // A single cumulative reading carries no rate — must read 0, not 500.
        assertEquals(0f, rpm!!, 0.001f)
    }

    @Test
    fun `stroke total accumulates across the ride`() {
        val tracker = CadenceTracker()
        steadyCadence(tracker, rpm = 60, seconds = 30) // 1 stroke/s for 30 s
        assertEquals(30L, tracker.totalCrankRevs)
    }

    @Test
    fun `crank revolution counter wrap does not spike cadence`() {
        val tracker = CadenceTracker()
        // Start 3 strokes before the uint16 wrap and ride straight through it.
        val rpm = steadyCadence(tracker, rpm = 60, seconds = 8, startRevs = 65533)
        assertEquals(60f, rpm, 1.5f)
        assertEquals(8L, tracker.totalCrankRevs)
    }

    @Test
    fun `event time counter wrap does not spike cadence`() {
        val tracker = CadenceTracker()
        // Event ticks wrap every 64 s; start 2 s before the wrap.
        val rpm = steadyCadence(tracker, rpm = 90, seconds = 8, startTicks = 63488)
        assertEquals(90f, rpm, 1.5f)
    }

    @Test
    fun `coasting reads zero, not the last known cadence`() {
        val tracker = CadenceTracker()
        steadyCadence(tracker, rpm = 95, seconds = 10)
        val lastWallMs = 10_000L + 10_000L
        // Still fresh right after the last stroke.
        assertTrue(tracker.currentRpm(lastWallMs) > 90f)
        // Freewheeling: the sensor goes silent, the readout must fall to 0.
        assertEquals(0f, tracker.currentRpm(lastWallMs + CadenceTracker.WINDOW_MS + 500L), 0.001f)
    }

    @Test
    fun `packets that repeat the same crank event decay to zero`() {
        val tracker = CadenceTracker()
        steadyCadence(tracker, rpm = 80, seconds = 6)
        // The S314 keeps notifying ~1 Hz while awake but stops advancing the
        // crank counters once the rider stops pedalling.
        var wall = 16_000L
        var rpm = 0f
        repeat(6) {
            wall += 1_000L
            rpm = tracker.onMeasurement(
                CscMeasurement(cumulativeCrankRevs = 8, lastCrankEventTicks = 6 * 1024),
                wall,
            )!!
        }
        assertEquals(0f, rpm, 0.001f)
        assertEquals(0f, tracker.currentRpm(wall), 0.001f)
    }

    @Test
    fun `sensor power-cycle re-baselines instead of reporting a spike`() {
        val tracker = CadenceTracker()
        steadyCadence(tracker, rpm = 90, seconds = 10)
        val strokesBefore = tracker.totalCrankRevs
        // Magene sensors sleep when the bike is parked; on wake the cumulative
        // counters restart near zero. Naive modular subtraction would read that
        // as ~65500 strokes in one second.
        val rpm = tracker.onMeasurement(
            CscMeasurement(cumulativeCrankRevs = 1, lastCrankEventTicks = 700),
            21_000L,
        )!!
        assertEquals(0f, rpm, 0.001f)
        assertEquals(strokesBefore, tracker.totalCrankRevs)
        assertTrue(rpm < CadenceTracker.MAX_PLAUSIBLE_RPM)
    }

    @Test
    fun `a long stop re-baselines because the tick counter is ambiguous`() {
        val tracker = CadenceTracker()
        steadyCadence(tracker, rpm = 90, seconds = 10)
        // 5 minutes at a chai stop: the 64 s tick counter wrapped an unknown
        // number of times, so the delta means nothing.
        val rpm = tracker.onMeasurement(
            CscMeasurement(cumulativeCrankRevs = 200, lastCrankEventTicks = 3000),
            10_000L + 10_000L + 300_000L,
        )!!
        assertEquals(0f, rpm, 0.001f)
        // Then it picks straight back up on the next real strokes.
        var wall = 320_000L
        var revs = 200
        var ticks = 3000
        var latest = 0f
        repeat(6) {
            revs += 1
            ticks = (ticks + 1024) % 65536
            wall += 1_000L
            latest = tracker.onMeasurement(
                CscMeasurement(cumulativeCrankRevs = revs, lastCrankEventTicks = ticks),
                wall,
            )!!
        }
        assertEquals(60f, latest, 1.5f)
    }

    @Test
    fun `low grinding cadence is still measured, not treated as coasting`() {
        val tracker = CadenceTracker()
        // 24 rpm — one stroke every 2.5 s, standing on a steep ramp. Adjacent-
        // packet math would alternate spike/zero here; the window holds it.
        var wall = 10_000L
        var revs = 0
        var ticks = 0
        tracker.onMeasurement(CscMeasurement(cumulativeCrankRevs = revs, lastCrankEventTicks = ticks), wall)
        var rpm = 0f
        repeat(6) {
            revs += 1
            ticks = (ticks + 2560) % 65536 // 2.5 s of event time
            wall += 2_500L
            rpm = tracker.onMeasurement(
                CscMeasurement(cumulativeCrankRevs = revs, lastCrankEventTicks = ticks),
                wall,
            )!!
        }
        assertEquals(24f, rpm, 1f)
    }

    @Test
    fun `reset clears counters for a fresh connection`() {
        val tracker = CadenceTracker()
        steadyCadence(tracker, rpm = 90, seconds = 5)
        assertTrue(tracker.totalCrankRevs > 0)
        tracker.reset()
        assertEquals(0L, tracker.totalCrankRevs)
        assertFalse(tracker.hasSeenCrankData)
        assertEquals(0f, tracker.currentRpm(999_999L), 0.001f)
    }
}
