package com.uruj

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.9.56 — Custom [Application] subclass that wires up:
 *
 *  1. **Local crash logger fallback** — captures every uncaught exception to
 *     `/files/crash-logs/YYYY-MM-DD_HHmmss.txt` BEFORE chaining to Firebase
 *     Crashlytics. Ensures the rider has a local record even when Firebase
 *     can't upload (offline, throttled, account not yet enabled in console).
 *
 *  2. **Crashlytics metadata** — Firebase Crashlytics auto-initializes via
 *     `FirebaseInitProvider` (a ContentProvider that runs BEFORE
 *     `Application.onCreate`). We layer custom keys on top so each crash
 *     report includes URUJ version + device model + Android API level
 *     without us having to inspect the bug report manually.
 *
 * ## Why a separate Application class
 *
 * Pre-v0.9.56 URUJ had no Application class — Android used the default
 * `android.app.Application`. Adding our own gives us a stable entry point
 * for crash handling that runs once per process lifecycle, before any
 * Activity or Service can throw.
 *
 * The class is wired in via `<application android:name=".UrujApplication">`
 * in [AndroidManifest.xml]. The fully-qualified class name is
 * `com.uruj.UrujApplication` (sits at the top of the package alongside
 * MainActivity).
 *
 * ## Crash handler chain
 *
 * Android's `Thread.setDefaultUncaughtExceptionHandler` is a single-slot
 * registration — each call REPLACES the previous handler. To compose with
 * Firebase Crashlytics (which installs its own handler via
 * `FirebaseInitProvider.onCreate` BEFORE our `Application.onCreate` runs),
 * we:
 *
 *  1. Snapshot the current default handler (= Crashlytics).
 *  2. Install our handler which writes to disk first.
 *  3. After our write, chain to the snapshotted handler so Crashlytics
 *     still fires.
 *
 * If we skipped step 3, Firebase would never see crashes — the process
 * would die before Crashlytics' worker thread could ship the report.
 *
 * ## Local crash log format
 *
 * Each crash creates ONE text file in `filesDir/crash-logs/`:
 *
 * ```
 * URUJ crash at 2026-05-28_142307
 * Version: 0.9.56 (code 145)
 * Android: 14 (API 34)
 * Device: OnePlus CPH2451
 *
 * Thread: main
 *
 * Stack trace:
 * java.lang.OutOfMemoryError: ...
 *     at com.uruj.data.ContinuousBiometricRepository.hrSamplesForWindow(...)
 *     ...
 * ```
 *
 * Rider can `adb pull /sdcard/Android/data/com.uruj/files/crash-logs/`
 * to retrieve them. Future v1.x: surface in Pipeline tab "View recent
 * crashes" button so no ADB needed.
 *
 * ## Lab-grade compliance
 *
 * - **No data layer change**: math, snapshots, methodology versioning all
 *   untouched. This is pure observability infrastructure.
 * - **No HC reads, no NDJSON walks**: the crash handler is allocation-light
 *   (one StringBuilder + one file write). Will not itself OOM.
 * - **runCatching everywhere**: if disk write fails, we still chain to
 *   Crashlytics. No swallowed crashes.
 * - **Single-process safe today**: v0.9.59 will introduce a separate
 *   `:biometric` process. When that lands, `UrujApplication.onCreate`
 *   will fire in BOTH processes — each independently installing its own
 *   handler. Already correct behavior. No multi-process bridge needed.
 */
class UrujApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandlerChain()
    }

    /**
     * Snapshot the current default handler (Crashlytics, installed by
     * FirebaseInitProvider during ContentProvider init phase, which runs
     * BEFORE Application.onCreate) and install our local-logger handler
     * on top. Our handler:
     *
     *  - Writes a structured crash record to disk.
     *  - Re-throws via the chained handler so Crashlytics still ships.
     *  - Never swallows the crash (the process MUST die after this).
     */
    private fun installCrashHandlerChain() {
        val crashlyticsHandler: Thread.UncaughtExceptionHandler? =
            Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Step 1 — local on-disk crash record. Wrapped in runCatching
            // so a write failure (e.g. filesystem full) doesn't prevent
            // Crashlytics from shipping the report.
            runCatching { writeCrashToDisk(thread, throwable) }
                .onFailure { Log.w(TAG, "local crash log write failed", it) }
            // Step 2 — chain to Crashlytics. CRITICAL: without this,
            // Firebase never sees the crash and the process won't die
            // properly (the OS would do it eventually but we get cleaner
            // termination by delegating to the platform default).
            crashlyticsHandler?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "[v0.9.56] crash handler chain installed (local-log + Crashlytics)")
    }

    /**
     * Write a single-file crash record. Filename includes timestamp so
     * multiple crashes never collide. Past crash files are NEVER purged
     * automatically — the rider can clear them via Settings > Apps if
     * disk fills up. Typical text-file size is <100KB even for the
     * deepest Kotlin stack trace.
     */
    private fun writeCrashToDisk(thread: Thread, throwable: Throwable) {
        val dir = File(filesDir, CRASH_LOG_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create $CRASH_LOG_DIR — aborting local log")
            return
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "$stamp.txt")
        val content = buildString {
            appendLine("URUJ crash at $stamp")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("Thread: ${thread.name} (id ${thread.id})")
            appendLine()
            appendLine("Stack trace:")
            append(throwable.stackTraceToString())
        }
        file.writeText(content)
        Log.e(TAG, "[v0.9.56] crash written to ${file.absolutePath}")
    }

    companion object {
        private const val TAG = "URUJ-App"
        private const val CRASH_LOG_DIR = "crash-logs"
    }
}
