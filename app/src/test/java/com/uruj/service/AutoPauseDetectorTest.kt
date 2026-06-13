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
    fun `pauses once the stillness window has elapsed`() {
        // v0.9.76 (#167) — fixed a stale assertion: this test expected a 10s
        // window, but the detector's windowMs is 5_000L (matches Garmin / Strava
        // / Wahoo). The code is the intended behavior; the test was never updated
        // when the window was shortened. Now asserts the real 5s window.
        val d = AutoPauseDetector()
        // Riding at t=0, then a full stop.
        d.observe(0L, 5.5f, 0.3f)

        // Up to (but not including) the 5s window: still considered moving.
        for (t in 1_000L..4_000L step 1_000L) {
            assertFalse("paused too early at $t ms", d.observe(t, 0.0f, 0.05f))
        }
        // 5s after the last movement: pause kicks in.
        assertTrue("expected paused at the 5s mark", d.observe(5_000L, 0.0f, 0.05f))
        assertTrue("still paused at 7s", d.observe(7_000L, 0.0f, 0.05f))
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
