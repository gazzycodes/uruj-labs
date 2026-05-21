package com.uruj.power

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.26 — validation suite for FrequencyDomainCalculator.
 *
 * Catches the v0.9.25 regression class (LF/HF=22, SD1=0.8 ms) via the
 * Poincaré SD1 ≡ RMSSD/√2 mathematical invariant + sanity ranges on the
 * other metrics computed against synthetic signals with known properties.
 */
class FrequencyDomainCalculatorTest {

    private val freq = FrequencyDomainCalculator()
    private val rmssdCalc = HrvCalculator()

    /**
     * MATHEMATICAL INVARIANT: Poincaré SD1 = RMSSD / √2. Always. If this
     * ever fails the freq-domain math has a bug. This is the test that
     * would have caught the v0.9.25 → v0.9.26 windowing regression.
     */
    @Test
    fun `poincare SD1 equals RMSSD over sqrt(2) - mathematical invariant`() {
        // Synthetic RR series with known variability — 300 beats with ~50ms
        // alternating perturbations (high-frequency RSA-like pattern)
        val rr = (0 until 300).map { 800f + (if (it % 2 == 0) 50f else -50f) }
        val sd1 = freq.poincareSd1(rr)!!
        // Compute RMSSD directly for comparison
        val diffs = (1 until rr.size).map { rr[it] - rr[it - 1] }
        val rmssd = sqrt(diffs.map { it * it }.average()).toFloat()
        // Invariant: SD1 ≡ RMSSD / √2 (within float precision)
        val expectedSd1 = rmssd / sqrt(2f)
        assertEquals(expectedSd1, sd1, 0.01f)
    }

    /** Sanity: SD2 ≥ SD1 always (long-term variability ≥ short-term). */
    @Test
    fun `poincare SD2 is at least SD1`() {
        val rr = (0 until 200).map { 800f + (it % 5) * 10f }
        val sd1 = freq.poincareSd1(rr)!!
        val sd2 = freq.poincareSd2(rr)!!
        assertTrue("SD2=$sd2 should be >= SD1=$sd1", sd2 >= sd1)
    }

    /**
     * Sample entropy of a perfectly periodic signal should be very low
     * (the algorithm detects the repetition).
     */
    @Test
    fun `sample entropy of periodic signal is low`() {
        val rr = (0 until 200).map { 800f + (if (it % 4 < 2) 40f else -40f) }
        val sampEn = freq.sampleEntropy(rr)
        assertNotNull(sampEn)
        // Periodic signals: sample entropy should be < 1.0
        assertTrue("Periodic signal sampEn=$sampEn should be < 1.0", sampEn!! < 1.0f)
    }

    /**
     * DFA α1 of "pink noise" (1/f scaling) should be approximately 1.0.
     * We can't easily synthesize true pink noise here, but a clearly
     * trended signal (cumulative random walk) should produce α1 > 1.0
     * (long-range correlation).
     */
    @Test
    fun `DFA alpha1 on correlated signal exceeds 0_5`() {
        val rng = java.util.Random(42L)
        // Cumulative random walk → strongly correlated → α1 > 1.0 expected
        var v = 800f
        val rr = (0 until 200).map {
            v += rng.nextGaussian().toFloat() * 5f
            v
        }
        val dfa = freq.dfaAlpha1(rr)
        assertNotNull(dfa)
        assertTrue("DFA α1 = $dfa on correlated walk should be > 0.5", dfa!! > 0.5f)
    }

    /** Insufficient beats → null result (no fake numbers, lab-level rule 4). */
    @Test
    fun `compute returns null when too few beats`() {
        val beats = (0 until 20).map { HrvCalculator.Beat(timestampMs = it * 800L, rrMs = 800) }
        val result = freq.computeWindowed(beats)
        assertNull(result)
    }

    /**
     * Smoke test: feed 30 minutes of synthetic beats spanning 6 5-min
     * windows → compute returns non-null with at least 3 valid windows
     * (matches MIN_VALID_WINDOWS = 3).
     */
    @Test
    fun `computeWindowed returns valid result for 30 min synthetic data`() {
        val rng = java.util.Random(123L)
        // 30 min at ~75 bpm → ~2250 beats. Add small random variation
        // (50ms SD around 800ms mean RR).
        val beats = mutableListOf<HrvCalculator.Beat>()
        var t = 0L
        repeat(2250) {
            val rr = (800 + rng.nextGaussian() * 50).toInt().coerceIn(600, 1000)
            t += rr
            beats.add(HrvCalculator.Beat(timestampMs = t, rrMs = rr))
        }
        val result = freq.computeWindowed(beats)
        assertNotNull("Should produce a result for 30 min synthetic data", result)
        assertTrue("Should have at least 3 valid windows", result!!.windowCount >= 3)
        // LF/HF on noisy data should be in a sane range (not >10, not <0.1)
        result.lfHfRatio?.let { lfHf ->
            assertTrue("LF/HF = $lfHf should be in sane range [0.1, 10]", lfHf in 0.1f..10f)
        }
        // SD1 should be in a sane ms range for human HRV (1-200 ms)
        result.sd1Ms?.let { sd1 ->
            assertTrue("SD1 = $sd1 ms should be in range [1, 200]", sd1 in 1f..200f)
        }
    }
}
