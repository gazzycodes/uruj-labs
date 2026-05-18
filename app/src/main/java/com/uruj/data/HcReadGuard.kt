package com.uruj.data

import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * v0.8.5 — central observability + post-ride quiet window for Health
 * Connect reads. Single source of truth for "how hard is URUJ hitting HC."
 *
 * ## Why this exists
 *
 * v0.8.4 fixed the chronic HC rate-limit storm by removing background
 * polling, throttling inventory, and adding sticky caches. v0.8.5 v0.8.4
 * audit revealed two remaining gaps:
 *
 *   1. **No visibility** into HC reads/min across the app. When the user
 *      reported "Pipeline went red for ~2 min after ride end" we had no
 *      log trail to identify the culprit reader. Every HC entry point
 *      now records here, so logcat filter `URUJ-HC` shows the full
 *      pressure profile.
 *
 *   2. **Post-ride transient burst**. v0.8.4 covered chronic pressure
 *      but ride-end is a navigation cascade: HUD → Summary screen
 *      (LaunchedEffect kicks NDJSON-first enrichment — usually no HC),
 *      then user often taps PIPELINE / LAB / RIDES tabs in quick
 *      succession. Each tab opens fire their respective HC consumers.
 *      Within ~5 seconds we can hit HC's burst sub-limit (~10/sec).
 *      The guard's `isPostRideQuietWindow()` lets top-level readers
 *      serve cache for 30 seconds post-ride, bridging the navigation
 *      cascade until normal cache TTLs cover.
 *
 * ## Usage at HC entry points
 *
 * Top-level repositories + ViewModels call `recordRead("inventory")`
 * (or similar) before each batch of HC reads. Inventory + BioLab +
 * Readiness check `isPostRideQuietWindow()` and serve cache when true.
 *
 * ## Usage from service
 *
 * `RideRecorderService.stopRecording()` calls `recordRideEnded()` so
 * the 30s quiet window starts at the actual ride-stop moment, not
 * whenever the summary screen happens to open.
 *
 * ## Diagnostics
 *
 * `readsInLastMs(60_000)` returns the count of HC reads in the last
 * minute, exposed via the Pipeline diagnostics screen (future task)
 * for live HC pressure monitoring. Currently used by logging only.
 */
object HcReadGuard {

    private const val TAG = "URUJ-HC"

    /** Default post-ride cool-off window. Matches HC's typical burst window. */
    private const val DEFAULT_POST_RIDE_QUIET_MS = 30_000L

    private val rideEndedAtMs = AtomicLong(0L)

    /** Rolling log of HC read timestamps (last 5 min). Bounded so memory
     *  stays cheap even if we lose track of pruning. */
    private val recentReads = ConcurrentLinkedDeque<ReadEvent>()
    private const val MAX_RETAINED_EVENTS = 600  // ~10/sec for 60s, generous

    data class ReadEvent(val atMs: Long, val source: String)

    /**
     * Record an HC read attempt. Call this at the SAME granularity you
     * see in logcat — once per batch is fine (a single inventory call
     * that does 16 record reads counts as one "inventory.16" entry; we
     * don't need per-record events).
     *
     * Logs at DEBUG so production builds don't spam. Filter via
     * `adb logcat -s URUJ-HC` during diagnosis.
     */
    fun recordRead(source: String) {
        val now = System.currentTimeMillis()
        val event = ReadEvent(now, source)
        recentReads.addLast(event)
        // Bounded prune — drop the oldest if we're over the cap. Cheaper
        // than scanning the whole deque on each add.
        while (recentReads.size > MAX_RETAINED_EVENTS) {
            recentReads.pollFirst()
        }
        Log.d(TAG, "read: $source  (${readsInLastMs(60_000)}/min)")
    }

    /**
     * Mark a ride end. Starts the post-ride quiet window. Called from
     * `RideRecorderService.stopRecording()` so the timestamp is the
     * actual stop moment, not whenever the summary screen happens to
     * load.
     */
    fun recordRideEnded() {
        rideEndedAtMs.set(System.currentTimeMillis())
        Log.d(TAG, "ride ended — starting ${DEFAULT_POST_RIDE_QUIET_MS}ms quiet window")
    }

    /**
     * True if a ride ended within the quiet window. Top-level HC readers
     * (Inventory, BioLab, Readiness) should serve cache when this returns
     * true rather than firing fresh HC queries — the user is in the
     * post-ride navigation cascade and cumulative reads can blow HC's
     * burst sub-limit.
     */
    fun isPostRideQuietWindow(windowMs: Long = DEFAULT_POST_RIDE_QUIET_MS): Boolean {
        val ended = rideEndedAtMs.get()
        if (ended <= 0L) return false
        return (System.currentTimeMillis() - ended) < windowMs
    }

    /**
     * Diagnostic: how many HC reads we've recorded in the last `windowMs`
     * milliseconds. Useful for live load monitoring. O(n) over recent
     * events, n bounded by MAX_RETAINED_EVENTS.
     */
    fun readsInLastMs(windowMs: Long): Int {
        val cutoff = System.currentTimeMillis() - windowMs
        return recentReads.count { it.atMs >= cutoff }
    }

    /**
     * Diagnostic: breakdown of HC reads by source in the last `windowMs`.
     * Returns map of `source → count`. Useful for "who is hammering HC"
     * questions during incident response.
     */
    fun breakdownLastMs(windowMs: Long): Map<String, Int> {
        val cutoff = System.currentTimeMillis() - windowMs
        return recentReads.asSequence()
            .filter { it.atMs >= cutoff }
            .groupingBy { it.source }
            .eachCount()
    }

    /** Test-only — reset all state. */
    internal fun resetForTesting() {
        rideEndedAtMs.set(0L)
        recentReads.clear()
    }
}
