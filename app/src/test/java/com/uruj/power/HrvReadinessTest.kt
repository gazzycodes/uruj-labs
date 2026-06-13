package com.uruj.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.74 — validation suite for [HrvReadiness], the single source of truth for
 * "is today's overnight HRV suppressed for THIS rider?"
 *
 * The canonical cases use the rider's real numbers (baseline ~13.8 ms, CV 5.9%)
 * so a regression here means the personal-baseline engine has drifted from the
 * verified design in
 * docs/research/2026-06-13-hrv-personal-baseline-readiness.md.
 *
 * Core guarantees being locked:
 *  - His constitutional baseline (14.1 vs 13.8) reads NORMAL — no flag, no cap
 *    (this is the exact 2026-06-13 bug the release fixes).
 *  - A genuine crash well below his own baseline still fires SEVERE (the
 *    v0.9.41 chronic-baseline-trap safety net, #192, is preserved).
 *  - The parasympathetic-saturation guard: a dip with a CALM RHR caps at MILD
 *    (benign high vagal tone), but the SAME dip with an ELEVATED RHR escalates
 *    to SEVERE (corroborated genuine suppression).
 *  - New users (< 7 nights) get NO_BASELINE so callers fall back to the wide
 *    absolute floor.
 */
class HrvReadinessTest {

    // His real baseline context.
    private val baseline = 13.8f
    private val cv = 5.9f
    private val days = 10 // ≥ MIN_DAYS

    // ────────────────────────────────────────────────────────────────────
    // The canonical real-number cases (mandated by the build spec)
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `today at his baseline is NORMAL — no flag, no cap (the 2026-06-13 bug)`() {
        val a = HrvReadiness.assess(
            todayMs = 14.1f, baselineMs = baseline, cvPercent = cv,
            rhrDelta = 0, daysOfData = days,
        )
        assertEquals(HrvReadiness.Verdict.NORMAL, a.verdict)
        assertFalse(a.saturationLikely)
        // 14.1 is ABOVE 13.8 → deviation must be positive.
        assertNotNull(a.deviationSds)
        assertTrue("deviation should be > 0 (above baseline)", a.deviationSds!! > 0f)
        assertEquals(baseline, a.baselineMs)
    }

    @Test
    fun `crash to 11 ms is SEVERE regardless of RHR (extreme-crash safety net)`() {
        val calm = HrvReadiness.assess(
            todayMs = 11f, baselineMs = baseline, cvPercent = cv,
            rhrDelta = 0, daysOfData = days,
        )
        // ~ -3.85 SD below baseline → extreme crash → SEVERE even with a calm RHR.
        assertEquals(HrvReadiness.Verdict.SEVERE, calm.verdict)
        assertTrue(calm.deviationSds!! < -2.5f)
        // Same crash with elevated RHR is also SEVERE.
        val elevated = HrvReadiness.assess(
            todayMs = 11f, baselineMs = baseline, cvPercent = cv,
            rhrDelta = 6, daysOfData = days,
        )
        assertEquals(HrvReadiness.Verdict.SEVERE, elevated.verdict)
    }

    @Test
    fun `dip to 12_5 with a CALM RHR is MILD — parasympathetic saturation`() {
        val a = HrvReadiness.assess(
            todayMs = 12.5f, baselineMs = baseline, cvPercent = cv,
            rhrDelta = 0, daysOfData = days,
        )
        // ~ -1.68 SD: past the severe edge but RHR calm → treat as benign vagal
        // saturation, capped at MILD (don't slam the brakes).
        assertEquals(HrvReadiness.Verdict.MILD, a.verdict)
        assertTrue(a.saturationLikely)
        assertTrue(a.deviationSds!! < -1.5f)
    }

    @Test
    fun `same dip to 12_5 with RHR plus 5 is SEVERE — corroborated suppression`() {
        val a = HrvReadiness.assess(
            todayMs = 12.5f, baselineMs = baseline, cvPercent = cv,
            rhrDelta = 5, daysOfData = days,
        )
        assertEquals(HrvReadiness.Verdict.SEVERE, a.verdict)
        assertFalse(a.saturationLikely)
    }

    @Test
    fun `fewer than 7 nights is NO_BASELINE — caller falls back to absolute floor`() {
        val a = HrvReadiness.assess(
            todayMs = 14.1f, baselineMs = baseline, cvPercent = cv,
            rhrDelta = 0, daysOfData = 5,
        )
        assertEquals(HrvReadiness.Verdict.NO_BASELINE, a.verdict)
        assertNull(a.deviationSds)
    }

    // ────────────────────────────────────────────────────────────────────
    // Boundaries + degenerate inputs
    // ────────────────────────────────────────────────────────────────────

    @Test
    fun `mild dip with calm RHR between -0_5 and -1_5 SD is MILD`() {
        // 13.0 vs 13.8 ⇒ ~ -1.0 SD.
        val a = HrvReadiness.assess(
            todayMs = 13.0f, baselineMs = baseline, cvPercent = cv,
            rhrDelta = 0, daysOfData = days,
        )
        assertEquals(HrvReadiness.Verdict.MILD, a.verdict)
        assertTrue(a.deviationSds!! < -0.5f && a.deviationSds!! > -1.5f)
    }

    @Test
    fun `well above baseline is NORMAL`() {
        val a = HrvReadiness.assess(
            todayMs = 16.5f, baselineMs = baseline, cvPercent = cv,
            rhrDelta = 0, daysOfData = days,
        )
        assertEquals(HrvReadiness.Verdict.NORMAL, a.verdict)
        assertFalse(a.saturationLikely)
    }

    @Test
    fun `null or invalid inputs yield NO_BASELINE`() {
        assertEquals(
            HrvReadiness.Verdict.NO_BASELINE,
            HrvReadiness.assess(null, baseline, cv, 0, days).verdict,
        )
        assertEquals(
            HrvReadiness.Verdict.NO_BASELINE,
            HrvReadiness.assess(14.1f, null, cv, 0, days).verdict,
        )
        assertEquals(
            HrvReadiness.Verdict.NO_BASELINE,
            HrvReadiness.assess(14.1f, baseline, null, 0, days).verdict,
        )
        assertEquals(
            HrvReadiness.Verdict.NO_BASELINE,
            HrvReadiness.assess(14.1f, baseline, 0f, 0, days).verdict,
        )
    }

    @Test
    fun `null RHR is treated as not-elevated — saturation guard holds`() {
        // -1.68 SD with unknown RHR must NOT escalate to SEVERE (no corroboration).
        val a = HrvReadiness.assess(
            todayMs = 12.5f, baselineMs = baseline, cvPercent = cv,
            rhrDelta = null, daysOfData = days,
        )
        assertEquals(HrvReadiness.Verdict.MILD, a.verdict)
        assertTrue(a.saturationLikely)
    }

    @Test
    fun `self-recalibration — a HIGHER baseline reclassifies the same reading`() {
        // 14.1 ms reads NORMAL at a 13.8 baseline, but SEVERE/MILD once the
        // rider's baseline has risen (e.g. nicotine taper / better sleep) — proof
        // the band tracks the person, not a fixed number.
        val atLowBaseline = HrvReadiness.assess(14.1f, 13.8f, cv, 0, days)
        val atHighBaseline = HrvReadiness.assess(14.1f, 17.0f, cv, 0, days)
        assertEquals(HrvReadiness.Verdict.NORMAL, atLowBaseline.verdict)
        assertTrue(
            "same 14.1 ms should no longer be normal once baseline rises to 17",
            atHighBaseline.verdict != HrvReadiness.Verdict.NORMAL,
        )
        assertTrue(atHighBaseline.deviationSds!! < atLowBaseline.deviationSds!!)
    }

    @Test
    fun `label never leaks a population number for an established baseline`() {
        val a = HrvReadiness.assess(14.1f, baseline, cv, 0, days)
        // Should reference HIS baseline, not a generic athletic threshold.
        assertTrue(a.label.contains("baseline"))
        assertFalse(a.label.contains("athletic"))
    }
}
