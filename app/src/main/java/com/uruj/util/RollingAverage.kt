package com.uruj.util

/**
 * Time-windowed rolling average. Tracks values within the last `windowSeconds` and
 * returns the mean. Used for the 3s and 30s smoothed power displays on the HUD —
 * raw 1-Hz power numbers are too noisy to display; 3s smoothing matches what most
 * cycling computers do, 30s is the input to the Normalized Power calculation.
 */
class RollingAverage(private val windowSeconds: Int) {

    private data class Entry(val timestampMs: Long, val value: Float)

    private val entries = ArrayDeque<Entry>()

    fun add(timestampMs: Long, value: Float): Float {
        entries.addLast(Entry(timestampMs, value))
        val cutoff = timestampMs - windowSeconds * 1_000L
        while (entries.isNotEmpty() && entries.first().timestampMs < cutoff) {
            entries.removeFirst()
        }
        return if (entries.isEmpty()) 0f
        else entries.sumOf { it.value.toDouble() }.toFloat() / entries.size
    }

    /** True once the window contains samples spanning at least [windowSeconds]. PR
     *  detection waits on this so a 1-second 200W spike doesn't register as a
     *  "5-minute average of 200W". */
    fun isFull(): Boolean {
        if (entries.size < 2) return false
        val span = entries.last().timestampMs - entries.first().timestampMs
        return span >= (windowSeconds - 1) * 1_000L
    }

    fun reset() {
        entries.clear()
    }
}
