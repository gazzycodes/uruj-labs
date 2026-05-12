package com.uruj.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPauseDetectorTest {

    @Test
    fun `never pauses while speed stays above threshold`() {
        val d = AutoPauseDetector()
        // 30 seconds of brisk riding (20 kph) with normal road vibration.
        for (t in 0L..30_000L step 1_000L) {
            assertFalse("paused unexpectedly at $t ms", d.observe(t, 5.5f, 0.3f))
        }
    }

    @Test
    fun `pauses once 10 seconds of stillness have elapsed`() {
        val d = AutoPauseDetector()
        // Riding for one second, then a full stop.
        d.observe(0L, 5.5f, 0.3f)

        for (t in 1_000L..9_000L step 1_000L) {
            assertFalse("paused too early at $t ms", d.observe(t, 0.0f, 0.05f))
        }
        // 10s after the last movement: pause kicks in.
        assertTrue("expected paused at 10s mark", d.observe(10_000L, 0.0f, 0.05f))
        assertTrue("still paused at 12s", d.observe(12_000L, 0.0f, 0.05f))
    }

    @Test
    fun `resumes immediately when motion returns`() {
        val d = AutoPauseDetector()
        // Sit still long enough to be paused.
        for (t in 0L..12_000L step 1_000L) d.observe(t, 0.0f, 0.05f)
        assertTrue(d.observe(13_000L, 0.0f, 0.05f))

        // First moving sample should unpause immediately.
        assertFalse(d.observe(14_000L, 5.5f, 0.3f))
    }

    @Test
    fun `accelerometer alone keeps state moving even with zero GPS speed`() {
        val d = AutoPauseDetector()
        // GPS reports zero but the bike is vibrating from rough road — keep moving.
        for (t in 0L..30_000L step 1_000L) {
            assertFalse("paused despite vibration at $t ms", d.observe(t, 0.0f, 0.4f))
        }
    }
}
