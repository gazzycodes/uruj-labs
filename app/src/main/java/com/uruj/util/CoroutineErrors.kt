package com.uruj.util

import kotlinx.coroutines.CancellationException

/**
 * Re-throws CancellationException to preserve Kotlin's structured-concurrency
 * contract. Use in any `runCatching { suspendingCall() }` chain that swallows
 * the result with `.getOrDefault()` / `.getOrNull()` / `.getOrElse { ... }`.
 *
 * Pattern (v0.9.20+):
 * ```
 * runCatching { client.readRecords(...) }
 *     .rethrowCancellation()
 *     .onFailure { Log.w(TAG, "read failed", it) }
 *     .getOrDefault(emptyList())
 * ```
 *
 * Why this matters: Kotlin's `runCatching` is implemented as `try { ... }
 * catch (Throwable)`, which ALSO catches `kotlinx.coroutines.JobCancellation
 * Exception`. Without an explicit rethrow, when the parent coroutine cancels
 * a suspending read (Activity navigation, refresh-cancels-prior-refresh,
 * scope death), the read returns an empty list silently → downstream code
 * believes "no data exists" and computes with partial inputs → wrong value
 * persisted to disk.
 *
 * Logcat evidence (2026-05-21 ~00:23): three HC reads (HRV / RHR / sleeping
 * HR) cancelled simultaneously → TSB compute fired with athleticRhr=null +
 * 0 Samsung sessions → got -21.42 instead of -23.63. Clean retry overwrote
 * within seconds, but a cancellation at app-swipe time would have persisted
 * the wrong value.
 *
 * Cost of calling: one closure allocation. Negligible vs. the data-integrity
 * win.
 */
fun <T> Result<T>.rethrowCancellation(): Result<T> = onFailure {
    if (it is CancellationException) throw it
}
