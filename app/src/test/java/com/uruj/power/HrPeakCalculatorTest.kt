package com.uruj.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HrPeakCalculatorTest {

    // Rider's real age → credible ceiling = 220-26+16 = 210 bpm.
    private val age = 26

    @Test
    fun `strap ride peak beats lower Health Connect peak — the 174 vs 177 bug`() {
        // HC (Samsung wrist) topped out at 174; the chest strap saw the true 177.
        val hc = listOf(120, 150, 168, 174)
        val rides = listOf(177)
        assertEquals(177, HrPeakCalculator.observedPeak(hc, rides, age))
    }

    @Test
    fun `Health Connect peak wins when higher than any ride`() {
        val hc = listOf(120, 188, 150)
        val rides = listOf(177, 160)
        assertEquals(188, HrPeakCalculator.observedPeak(hc, rides, age))
    }

    @Test
    fun `strap artifact above the rider ceiling is rejected, real peak survives`() {
        // 232 = chest-strap double-count artifact (> ceiling 210) → ignored.
        val hc = listOf(150, 170)
        val rides = listOf(232, 176)
        assertEquals(176, HrPeakCalculator.observedPeak(hc, rides, age))
    }

    @Test
    fun `Health Connect artifact above ceiling is rejected too`() {
        val hc = listOf(150, 245) // 245 = motion artifact
        val rides = listOf(170)
        assertEquals(170, HrPeakCalculator.observedPeak(hc, rides, age))
    }

    @Test
    fun `no rides falls back to Health Connect peak`() {
        val hc = listOf(120, 150, 174)
        assertEquals(174, HrPeakCalculator.observedPeak(hc, emptyList(), age))
    }

    @Test
    fun `no credible data returns null`() {
        assertNull(HrPeakCalculator.observedPeak(emptyList(), emptyList(), age))
        // all garbage-low or above ceiling
        assertNull(HrPeakCalculator.observedPeak(listOf(0, 255), listOf(240), age))
    }

    @Test
    fun `garbage-low values are ignored`() {
        val hc = listOf(0, 10, 30, 155)
        val rides = listOf(170)
        assertEquals(170, HrPeakCalculator.observedPeak(hc, rides, age))
    }

    @Test
    fun `credible ceiling is rider-aware and clamped`() {
        assertEquals(210, HrPeakCalculator.credibleCeiling(26))  // 220-26+16
        assertEquals(195, HrPeakCalculator.credibleCeiling(60))  // 176 -> clamp up to 195
        assertEquals(215, HrPeakCalculator.credibleCeiling(10))  // 226 -> clamp down to 215
    }

    @Test
    fun `a genuine new max above formula default is preserved for display`() {
        // Real all-out 198 sprint on the strap (age 26, ceiling 210) → shown.
        val hc = listOf(150, 174)
        val rides = listOf(198)
        assertEquals(198, HrPeakCalculator.observedPeak(hc, rides, age))
    }
}
