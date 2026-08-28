package com.uruj.power

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.83 — regression suite for [SleepingRhrCalculator].
 *
 * WHY THIS FILE EXISTS. Until v0.9.83 this class returned `nightSamples.min()`
 * — the single lowest BEAT of the night — while its own KDoc claimed it matched
 * "Garmin / Whoop's definition: the lowest SUSTAINED HR". Nobody checked, for
 * months. On the rider's own 2026-08-28 night the two differ by 6.5 bpm
 * (single-min 43 vs sustained-5min 49.5), and because VO2max is 15 x maxHR/RHR
 * with dVO2/dRHR = -1.51 per bpm, that error alone moved his reported VO2max
 * from ~57 ("Excellent") to 64.9 ("Elite, top 5%").
 *
 * The guarantees locked here are therefore not stylistic:
 *  - the returned statistic is SUSTAINED, never a single beat
 *  - respiratory sinus arrhythmia troughs cannot drag the value down
 *  - a "sustained" window is CONTIGUOUS, never stitched across a coverage gap
 *  - a corrupt night (hours at 150 bpm) is REJECTED, not averaged in
 *  - dropout samples (bpm 0) are never treated as a heart rate
 *  - provenance is reported so a stored value can be re-derived later
 */
class SleepingRhrCalculatorTest {

    private val calc = SleepingRhrCalculator()
    private val nightStart: Instant = Instant.parse("2026-08-28T20:52:39Z") // 02:22 IST
    private val nightEnd: Instant = nightStart.plusSeconds(9 * 3600)

    /** One sample every 2 s across [minutes], at [bpm] plus optional per-minute dip. */
    private fun samples(
        from: Instant,
        minutes: Int,
        bpm: Int,
        dipEvery: Int = 0,
        dipTo: Int = 0,
    ): List<Pair<Instant, Int>> {
        val out = ArrayList<Pair<Instant, Int>>()
        for (m in 0 until minutes) {
            for (s in 0 until 30) {
                val t = from.plusSeconds(m * 60L + s * 2L)
                val isDip = dipEvery > 0 && s % dipEvery == 0
                out += t to (if (isDip) dipTo else bpm)
            }
        }
        return out
    }

    // ---------------------------------------------------------------- core fix

    @Test
    fun `sustained statistic ignores single-beat troughs`() {
        // A flat 52 bpm night with a 43 bpm respiratory trough twice a minute.
        // The old code returned 43. The sustained mean must stay near 52.
        val night = samples(nightStart, 240, bpm = 52, dipEvery = 15, dipTo = 43)
        val r = calc.compute(emptyList(), listOf(nightStart to nightEnd), night)
        assertNotNull(r)
        assertEquals("single-beat trough must be preserved for reconciliation", 43, r!!.mostRecentNightSingleMinBpm)
        assertTrue(
            "sustained RHR ${r.mostRecentNightBpm} must be well above the 43 bpm trough",
            r.mostRecentNightBpm >= 49,
        )
        assertEquals(SleepingRhrCalculator.STAT_SUSTAINED, r.statistic)
    }

    @Test
    fun `sustained value tracks the genuinely lowest stretch of the night`() {
        // 60 min at 58, then 30 min at 47 (the real overnight trough), then 60 at 55.
        val a = samples(nightStart, 60, 58)
        val b = samples(nightStart.plusSeconds(60 * 60), 30, 47)
        val c = samples(nightStart.plusSeconds(90 * 60), 60, 55)
        val r = calc.compute(emptyList(), listOf(nightStart to nightEnd), a + b + c)
        assertNotNull(r)
        assertEquals("must find the 47 bpm stretch", 47, r!!.mostRecentNightBpm)
    }

    // ------------------------------------------------------------- contiguity

    @Test
    fun `a sustained window must be contiguous, not stitched across a coverage gap`() {
        // Two isolated low minutes hours apart, plus a solid 20-min block at 55.
        // Averaging the two low minutes would be meaningless; the answer must
        // come from the contiguous block.
        val lowA = samples(nightStart, 1, 40)
        val lowB = samples(nightStart.plusSeconds(3 * 3600), 1, 40)
        val block = samples(nightStart.plusSeconds(5 * 3600), 20, 55)
        val r = calc.compute(emptyList(), listOf(nightStart to nightEnd), lowA + lowB + block)
        assertNotNull(r)
        assertEquals("must not stitch isolated minutes into a sustained window", 55, r!!.mostRecentNightBpm)
        assertEquals(SleepingRhrCalculator.STAT_SUSTAINED, r.statistic)
    }

    @Test
    fun `too few contiguous minutes falls back to lowest MINUTE and says so`() {
        // Only 3 contiguous covered minutes — shorter than the 5-minute window.
        val night = samples(nightStart, 3, 48)
        val r = calc.compute(emptyList(), listOf(nightStart to nightEnd), night)
        assertNotNull(r)
        assertEquals(SleepingRhrCalculator.STAT_SINGLE_MINUTE_FALLBACK, r!!.statistic)
        assertEquals("fallback is the lowest MINUTE, never a single beat", 48, r.mostRecentNightBpm)
    }

    // ------------------------------------------------------- plausibility gate

    @Test
    fun `a corrupt night sitting at 150 bpm is rejected, not averaged in`() {
        // The 2026-08-15 shape: hours above 140 straight through a scored sleep session.
        val corrupt = samples(nightStart, 240, 152)
        val r = calc.compute(emptyList(), listOf(nightStart to nightEnd), corrupt)
        assertNull("a 152 bpm 'sleep' night is corrupt data and must yield no RHR", r)
    }

    @Test
    fun `rejected nights are counted so a shrinking sample cannot hide`() {
        val goodStart = nightStart.minusSeconds(24 * 3600)
        val good = samples(goodStart, 60, 50)
        val corrupt = samples(nightStart, 60, 155)
        val r = calc.compute(
            emptyList(),
            listOf(goodStart to goodStart.plusSeconds(9 * 3600), nightStart to nightEnd),
            good + corrupt,
        )
        assertNotNull(r)
        assertEquals("the corrupt night must be reported, not silently dropped", 1, r!!.rejectedNights)
        assertEquals(50, r.mostRecentNightBpm)
    }

    @Test
    fun `a genuinely poor night's sleep is NOT rejected`() {
        // Stress / illness / heat can push sleeping HR to 75. That is real and
        // must survive the gate — the gate exists for 150 bpm corruption only.
        val poor = samples(nightStart, 120, 75)
        val r = calc.compute(emptyList(), listOf(nightStart to nightEnd), poor)
        assertNotNull("75 bpm is a bad night, not corrupt data", r)
        assertEquals(75, r!!.mostRecentNightBpm)
        assertEquals(0, r.rejectedNights)
    }

    // -------------------------------------------------------------- dropouts

    @Test
    fun `zero-bpm dropout samples are never treated as a heart rate`() {
        // ~22% of this strap's raw samples are bpm 0 / contactDetected false.
        val real = samples(nightStart, 60, 51)
        val dropouts = (0 until 400).map { nightStart.plusSeconds(it * 3L) to 0 }
        val r = calc.compute(emptyList(), listOf(nightStart to nightEnd), real + dropouts)
        assertNotNull(r)
        assertTrue("dropouts must not drag RHR toward zero", r!!.mostRecentNightBpm >= 45)
    }

    // ------------------------------------------------------------- provenance

    @Test
    fun `provenance is reported so a stored value can be re-derived`() {
        val night = samples(nightStart, 120, 49)
        val r = calc.compute(emptyList(), listOf(nightStart to nightEnd), night)
        assertNotNull(r)
        assertEquals(SleepingRhrCalculator.STAT_SUSTAINED, r!!.statistic)
        assertNotNull("the old single-min value is kept for reconciliation", r.mostRecentNightSingleMinBpm)
        assertTrue("coverage must be reported", r.mostRecentNightCoverage > 0f)
        assertTrue("coverage is a fraction", r.mostRecentNightCoverage <= 1f)
    }

    @Test
    fun `empty input yields null rather than a fabricated number`() {
        assertNull(calc.compute(emptyList(), listOf(nightStart to nightEnd), emptyList()))
        assertNull(calc.compute(emptyList(), emptyList(), samples(nightStart, 60, 50)))
    }

    // ------------------------------------------------- the real regression case

    @Test
    fun `the rider's 2026-08-28 night reads sustained, not the 43 bpm single beat`() {
        // Reconstructed shape of the real night: median ~53, a genuine quiet
        // stretch near 49-50, and RSA troughs reaching 43.
        val early = samples(nightStart, 90, 56, dipEvery = 20, dipTo = 47)
        val quiet = samples(nightStart.plusSeconds(90 * 60), 40, 50, dipEvery = 20, dipTo = 43)
        val late = samples(nightStart.plusSeconds(130 * 60), 120, 54, dipEvery = 20, dipTo = 46)
        val r = calc.compute(emptyList(), listOf(nightStart to nightEnd), early + quiet + late)
        assertNotNull(r)
        assertEquals("the single lowest beat is still 43", 43, r!!.mostRecentNightSingleMinBpm)
        assertTrue(
            "reported RHR ${r.mostRecentNightBpm} must land in the high 40s, not 43",
            r.mostRecentNightBpm in 46..52,
        )
    }
}
