package com.uruj.power

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.27 — validation suite for FrequencyDomainCalculator (lab-grade math).
 *
 * The CRITICAL test is the per-window math invariant `SD1 ≡ RMSSD/√2`.
 * v0.9.25 + v0.9.26 had subtle filter bugs that caused SD1 to drift up to
 * 37% above the invariant. v0.9.27's aligned filter (mirrors
 * HrvCalculator.consecutiveDiffsMs) brings them into exact agreement.
 *
 * If any test here regresses, the freq-domain math has drifted from
 * canonical Task Force 1996 + Kubios convention.
 */
class FrequencyDomainCalculatorTest {

    private val freq = FrequencyDomainCalculator()
    private val rmssdCalc = HrvCalculator()

    // ────────────────────────────────────────────────────────────────────
    // Mathematical invariants (the canonical correctness tests)
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `poincare SD1 equals RMSSD over sqrt(2) on clean stationary signal`() {
        // 300-beat synthetic series with no ectopics — SD1 = RMSSD/√2 must hold
        val rr = (0 until 300).map { 800f + (if (it % 2 == 0) 50f else -50f) }
        val sd1 = freq.poincareSd1(rr)!!
        val diffs = (1 until rr.size).map { rr[it] - rr[it - 1] }
        val rmssd = sqrt(diffs.map { it * it }.average()).toFloat()
        assertEquals(rmssd / sqrt(2f), sd1, 0.01f)
    }

    @Test
    fun `v0_9_27 INVARIANT - computeWindowed SD1 reconciles with RMSSD per window`() {
        // The actual production-path invariant: feed identical Beat list to
        // both HrvCalculator.computeWindowed AND FrequencyDomainCalculator.
        // computeWindowed → median(per-window SD1) must = median(per-window RMSSD)/√2
        // (because filter is now aligned).
        val beats = synthBeats(durationMs = 30 * 60_000L, meanRrMs = 800, sdMs = 50, seed = 7L)
        val td = rmssdCalc.computeWindowed(beats)!!
        val fd = freq.computeWindowed(beats)!!
        val expectedSd1 = td.rmssdMs / sqrt(2f)
        // After v0.9.27 filter alignment, SD1 must reconcile within 0.5 ms
        // (small variance from median-of-medians vs direct calculation).
        assertEquals(
            "SD1=${fd.sd1Ms} must reconcile with RMSSD/√2=${expectedSd1} after v0.9.27 filter alignment",
            expectedSd1, fd.sd1Ms!!, 0.5f,
        )
    }

    @Test
    fun `filter alignment rejects ectopic-spanning pairs`() {
        // Build a sequence with a clear ectopic beat. HrvCalculator should
        // skip both pairs (pre→ectopic, ectopic→next). Freq-domain consecu-
        // tiveDiffsMs must do the same.
        val beats = listOf(
            HrvCalculator.Beat(timestampMs = 0L, rrMs = 800),
            HrvCalculator.Beat(timestampMs = 800L, rrMs = 800),
            HrvCalculator.Beat(timestampMs = 1600L, rrMs = 800),
            HrvCalculator.Beat(timestampMs = 2000L, rrMs = 400),    // ectopic (50% drop)
            HrvCalculator.Beat(timestampMs = 2800L, rrMs = 800),
            HrvCalculator.Beat(timestampMs = 3600L, rrMs = 800),
        )
        val diffs = freq.consecutiveDiffsMs(beats)
        // Valid pairs: (0,1), (1,2), (4,5) — that's 3 diffs
        // (2,3) rejected for ectopic delta. (3,4) rejected for ectopic delta.
        assertEquals("Should reject the 2 ectopic-spanning pairs", 3, diffs.size)
        // All valid diffs should be 0 (consecutive 800ms beats)
        assertTrue("All clean diffs should be 0", diffs.all { it == 0 })
    }

    @Test
    fun `filter alignment rejects timestamp-gap pairs`() {
        // Sequence where one beat is missing (long gap). HrvCalculator
        // rejects the cross-gap pair via the timestamp-consecutiveness check.
        val beats = listOf(
            HrvCalculator.Beat(timestampMs = 0L, rrMs = 800),
            HrvCalculator.Beat(timestampMs = 800L, rrMs = 800),
            // (missing beat that should have been at 1600)
            HrvCalculator.Beat(timestampMs = 2400L, rrMs = 800),  // 1600ms gap, expected 800
            HrvCalculator.Beat(timestampMs = 3200L, rrMs = 800),
        )
        val diffs = freq.consecutiveDiffsMs(beats)
        // Valid pairs: (0,1) and (2,3). Pair (1,2) rejected: actualGap=1600, expectedGap=800, |diff|=800 > tolerance(240).
        assertEquals("Should reject the cross-gap pair", 2, diffs.size)
    }

    // ────────────────────────────────────────────────────────────────────
    // Poincaré invariants
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `poincare SD2 is at least SD1`() {
        val rr = (0 until 200).map { 800f + (it % 5) * 10f }
        val sd1 = freq.poincareSd1(rr)!!
        val sd2 = freq.poincareSd2(rr)!!
        assertTrue("SD2=$sd2 should be >= SD1=$sd1", sd2 >= sd1)
    }

    // ────────────────────────────────────────────────────────────────────
    // FFT correctness — known synthetic spectrum
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `v0_9_28 Lomb-Scargle of synthetic HF oscillation peaks in HF band`() {
        // Synthetic 0.25 Hz oscillation (breathing rhythm, inside HF
        // band 0.15-0.4 Hz). Lomb-Scargle should isolate the peak to HF.
        // This is the lab-grade truth check: if our Lomb-Scargle math is
        // correct, a pure HF signal produces almost all power in HF band,
        // not in LF or VLF.
        val beats = mutableListOf<HrvCalculator.Beat>()
        var t = 0L
        val freqHz = 0.25
        val amplitudeMs = 30.0
        val meanRrMs = 800
        repeat(1500) {
            val phase = 2 * PI * freqHz * (t / 1000.0)
            val rr = (meanRrMs + amplitudeMs * sin(phase)).toInt().coerceIn(500, 1100)
            t += rr
            beats.add(HrvCalculator.Beat(timestampMs = t, rrMs = rr))
        }
        val (vlf, lf, hf) = freq.lombScargleBands(beats)
        assertNotNull(vlf); assertNotNull(lf); assertNotNull(hf)
        // HF should dominate — pure 0.25 Hz signal
        assertTrue("HF=$hf must dominate LF=$lf on pure 0.25 Hz", hf!! > lf!! * 2f)
        assertTrue("HF=$hf must dominate VLF=$vlf on pure 0.25 Hz", hf > vlf!! * 2f)
    }

    @Test
    fun `v0_9_28 Lomb-Scargle and Welch agree on clean uniform data`() {
        // Critical cross-check: on CLEAN data (no ectopic gaps), Lomb-Scargle
        // and Welch should produce similar LF/HF ratios — within 50%.
        // If they diverge sharply, one method is wrong. Lomb-Scargle is
        // the truth-revealer on noisy data; on clean data both must agree.
        val beats = synthBeats(durationMs = 30 * 60_000L, meanRrMs = 800, sdMs = 50, seed = 99L)
        val (lsVlf, lsLf, lsHf) = freq.lombScargleBands(beats)
        val (wVlf, wLf, wHf) = freq.welchBands(beats)
        assertNotNull(lsLf); assertNotNull(lsHf); assertNotNull(wLf); assertNotNull(wHf)
        val lsRatio = lsLf!! / lsHf!!
        val wRatio = wLf!! / wHf!!
        // Allow generous tolerance — the two methods have different
        // normalization conventions, but the ratio should be within 2x.
        val ratio = lsRatio / wRatio
        assertTrue(
            "Lomb-Scargle LF/HF=$lsRatio and Welch LF/HF=$wRatio should agree within 2x " +
                "on clean data (ratio of ratios = $ratio)",
            ratio in 0.5f..2.0f,
        )
    }

    @Test
    fun `FFT of synthetic HF oscillation produces HF peak`() {
        // Build a 30-min sequence with synthetic 0.25 Hz oscillation
        // (breathing rhythm). HF band (0.15-0.4 Hz) should contain
        // most of the spectral power.
        val beats = mutableListOf<HrvCalculator.Beat>()
        var t = 0L
        val freqHz = 0.25  // Inside HF band
        val amplitudeMs = 30.0
        val meanRrMs = 800
        // ~30 min × 60 sec × 1.25 beats/sec = 2250 beats
        repeat(2250) {
            val phase = 2 * PI * freqHz * (t / 1000.0)
            val rr = (meanRrMs + amplitudeMs * sin(phase)).toInt().coerceIn(500, 1100)
            t += rr
            beats.add(HrvCalculator.Beat(timestampMs = t, rrMs = rr))
        }
        val result = freq.computeWindowed(beats)
        assertNotNull(result)
        val lf = result!!.lfMs2!!
        val hf = result.hfMs2!!
        // HF should dominate LF for a pure 0.25 Hz signal
        assertTrue("HF=$hf should be > LF=$lf for synthetic 0.25 Hz signal", hf > lf)
    }

    // ────────────────────────────────────────────────────────────────────
    // Sample entropy + DFA sanity
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `sample entropy of periodic signal is low`() {
        val rr = (0 until 200).map { 800f + (if (it % 4 < 2) 40f else -40f) }
        val sampEn = freq.sampleEntropy(rr)
        assertNotNull(sampEn)
        assertTrue("Periodic signal sampEn=$sampEn should be < 1.0", sampEn!! < 1.0f)
    }

    @Test
    fun `DFA alpha1 on correlated signal exceeds 0_5`() {
        val rng = java.util.Random(42L)
        var v = 800f
        val rr = (0 until 200).map {
            v += rng.nextGaussian().toFloat() * 5f
            v
        }
        val dfa = freq.dfaAlpha1(rr)
        assertNotNull(dfa)
        assertTrue("DFA α1 = $dfa on correlated walk should be > 0.5", dfa!! > 0.5f)
    }

    // ────────────────────────────────────────────────────────────────────
    // Boundary conditions
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `compute returns null when too few beats`() {
        val beats = (0 until 20).map { HrvCalculator.Beat(timestampMs = it * 800L, rrMs = 800) }
        assertNull(freq.computeWindowed(beats))
    }

    @Test
    fun `computeWindowed returns valid result for 30 min synthetic data`() {
        val beats = synthBeats(durationMs = 30 * 60_000L, meanRrMs = 800, sdMs = 50, seed = 123L)
        val result = freq.computeWindowed(beats)
        assertNotNull("Should produce result for 30 min synthetic data", result)
        assertTrue("Should have ≥3 valid windows", result!!.windowCount >= 3)
        // Sanity ranges (synthetic clean data should be tame)
        result.lfHfRatio?.let { lfHf ->
            assertTrue("LF/HF = $lfHf should be in range [0.05, 20]", lfHf in 0.05f..20f)
        }
        result.sd1Ms?.let { sd1 ->
            assertTrue("SD1 = $sd1 ms should be in range [1, 200]", sd1 in 1f..200f)
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────

    /**
     * Build a clean synthetic Beat sequence — no ectopics, no gaps.
     * Used by tests where the filter MUST keep all pairs so SD1 ≡
     * RMSSD/√2 holds exactly.
     */
    private fun synthBeats(
        durationMs: Long,
        meanRrMs: Int,
        sdMs: Int,
        seed: Long,
    ): List<HrvCalculator.Beat> {
        val rng = java.util.Random(seed)
        val beats = mutableListOf<HrvCalculator.Beat>()
        var t = 0L
        while (t < durationMs) {
            // Bounded Gaussian — keep within ±15% of mean so the 20%
            // ectopic threshold doesn't accidentally reject pairs in
            // the invariant test.
            val deltaMs = (rng.nextGaussian() * sdMs).toInt().coerceIn(-meanRrMs * 12 / 100, meanRrMs * 12 / 100)
            val rr = (meanRrMs + deltaMs).coerceIn(
                HrvCalculator.PHYSIOLOGICAL_MIN, HrvCalculator.PHYSIOLOGICAL_MAX,
            )
            t += rr
            beats.add(HrvCalculator.Beat(timestampMs = t, rrMs = rr))
        }
        return beats
    }
}
