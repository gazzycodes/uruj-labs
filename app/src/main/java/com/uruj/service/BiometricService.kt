package com.uruj.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.uruj.MainActivity
import com.uruj.R
import com.uruj.data.BiometricSettingsStore
import com.uruj.data.BleSettingsStore
import com.uruj.data.ContinuousBiometricRecorder
import com.uruj.data.PairedStrap
import com.uruj.sensor.HrSample
import com.uruj.sensor.android.BleHrSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * v0.6.0 — Persistent foreground service maintaining a 24/7 BLE chest strap
 * connection independent of any ride. Stores every notification (HR + RR
 * intervals) to a daily-rotated NDJSON file for offline HRV / sleep / CAR /
 * postprandial / stress-event analysis.
 *
 * Lifecycle:
 *   - User toggles "24/7 monitoring" ON in Diagnostics → start()
 *   - User toggles OFF → stop()
 *   - App opens (MainActivity onCreate) → start() if BiometricSettings.enabled
 *   - Service survives app close, screen off, OEM Doze (foreground service
 *     with WAKE_LOCK)
 *   - Phone reboot: service does NOT auto-resume (Android limit). Re-launching
 *     the app will restart it via MainActivity hook.
 *
 * Independent of RideRecorderService:
 *   - Both can run simultaneously (Magene H613 supports 3 BLE connections,
 *     we use 1-2 from this app; phone-side is fine with multiple GATT instances)
 *   - During a ride, both services maintain their own BLE connection to the
 *     strap. Slightly wasteful but works. Cleaner shared-source coordination
 *     is v0.6.x work.
 *   - If user has no paired strap, this service still runs but stays in
 *     "waiting for pair" state; no NDJSON written.
 *
 * Notification: ongoing, low-priority, shows live HR + battery + contact
 * status. Tapping opens MainActivity. No sound, no vibration.
 *
 * Verbose logging at every state change (lab-level rule 8 traceability).
 */
class BiometricService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var bleJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val bleSource by lazy { BleHrSource(this) }
    private val bleSettings by lazy { BleSettingsStore(this) }
    private val biometricSettings by lazy { BiometricSettingsStore(this) }
    private val recorder by lazy { ContinuousBiometricRecorder(this) }

    // Surfaced via foreground notification + future UI for "is monitoring alive"
    private var latestSample: HrSample? = null
    private var latestBatteryPct: Int? = null
    private var samplesTotal: Long = 0L
    private var lastSampleAtMs: Long = 0L

    // v0.9.60 — per-cycle counters for the BLE watchdog. `samplesThisCycle`
    // distinguishes "first sample never arrived" from "had samples then went
    // silent" so the watchdog can apply a longer startup grace before the
    // steady-state silence timeout. `reconnectCycle` is incremented every time
    // the retry loop iterates; surfaced in the notification + Crashlytics
    // breadcrumbs so we can correlate stuck-state frequency over time.
    private var samplesThisCycle: Long = 0L
    private var reconnectCycle: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
        Log.d(TAG, "[svc] onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            else -> startMonitoring() // default
        }
        // STICKY — if OS kills us under memory pressure, attempt to restart
        // (still requires user opt-in via BiometricSettings to survive cleanly)
        return START_STICKY
    }

    private fun startMonitoring() {
        if (bleJob?.isActive == true) {
            Log.d(TAG, "[svc] start ignored — already running")
            return
        }
        Log.d(TAG, "[svc] starting 24/7 monitoring")
        startInForeground()
        acquireWakeLock()
        scope.launch { runCatching { biometricSettings.touchStarted() } }

        bleJob = scope.launch {
            try {
                runBleLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "[svc] BLE loop crashed", e)
            }
        }
    }

    private fun stopMonitoring() {
        Log.d(TAG, "[svc] stopping")
        bleJob?.cancel()
        bleJob = null
        runCatching { recorder.close() }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * The main BLE loop — direct-connect to paired strap, write every sample
     * to NDJSON, auto-reconnect on disconnect with exponential back-off.
     *
     * v0.9.60 — adds three resilience guards against the "stuck" failure mode
     * documented in task #196 (2026-05-22 + 2026-05-24 — notification frozen,
     * no samples received, force-stop required):
     *
     *   1. Sample-arrival watchdog (the primary defense). A co-routine ticks
     *      every WATCHDOG_INTERVAL_MS and verifies that samples are actually
     *      arriving. If silence exceeds WATCHDOG_SAMPLE_TIMEOUT_MS (or the
     *      startup grace before the first sample), it throws to force the
     *      retry loop to tear down the flow and reconnect. This catches the
     *      Android BLE silent-disconnect class of bugs where
     *      `onConnectionStateChange` is never called and the callbackFlow
     *      hangs in `collect` forever.
     *
     *   2. Wake-lock re-acquisition every retry cycle. PowerManager caps a
     *      single acquire() at 24h; for 24/7 wearers that expires mid-night
     *      and the CPU starts permitting Doze suspension, which can also
     *      manifest as silent BLE stalls. Re-acquiring on every cycle keeps
     *      the lock fresh.
     *
     *   3. BLUETOOTH_CONNECT / BLUETOOTH_SCAN permission re-check after each
     *      flow termination. If permission was revoked while the service was
     *      running, the notification surfaces a clear call to action
     *      ("Open URUJ to re-grant") and the retry loop continues so the
     *      service auto-resumes the moment permission is restored.
     *
     * Same pattern as RideRecorderService v0.5.2 for the underlying retry +
     * exponential back-off.
     */
    private suspend fun runBleLoop() {
        val paired: PairedStrap? = bleSettings.current()
        if (paired == null) {
            Log.d(TAG, "[svc] no paired strap — service idle until pairing happens")
            updateNotification(status = "Waiting for pair", subtitle = "No strap paired yet")
            // Park here. When user pairs via Diagnostics, they need to restart
            // monitoring via toggle (v0.6.x can auto-react to pair via store flow).
            while (scope.isActive) delay(60_000L)
            return
        }
        Log.d(TAG, "[svc] paired strap: ${paired.address}, starting BLE")
        var retryDelayMs = 5_000L
        while (scope.isActive) {
            val cycleNum = reconnectCycle
            // v0.9.60 — refresh the wake lock at the start of every cycle.
            // PowerManager's 24h cap on a single acquire silently expires for
            // 24/7 wearers; re-acquiring is a no-op if still held, and creates
            // a fresh 24h lease if expired or released.
            acquireWakeLock()
            try {
                coroutineScope {
                    samplesThisCycle = 0L
                    val flowStartedAtMs = System.currentTimeMillis()
                    // v0.9.60 — sample-arrival watchdog. Co-runs with the
                    // collect block. If samples stop arriving (or never
                    // arrive past the startup grace), it throws to force the
                    // outer retry loop to tear down and reconnect.
                    // coroutineScope semantics: any child uncaught throwable
                    // cancels the parent scope, which closes the callbackFlow,
                    // which runs awaitClose { gatt.disconnect(); gatt.close() }
                    // — so GATT cleanup is guaranteed.
                    val watchdog = launch {
                        while (isActive) {
                            delay(WATCHDOG_INTERVAL_MS)
                            val now = System.currentTimeMillis()
                            if (samplesThisCycle == 0L) {
                                val sinceFlowStart = now - flowStartedAtMs
                                if (sinceFlowStart > WATCHDOG_STARTUP_GRACE_MS) {
                                    val msg = "no first sample after ${sinceFlowStart / 1000}s (cycle $cycleNum)"
                                    Log.w(TAG, "[svc] watchdog: $msg — forcing reconnect")
                                    crashlyticsBreadcrumb("watchdog startup timeout: $msg")
                                    throw BleStuckException("startup timeout: $msg")
                                }
                            } else {
                                val sinceLastSample = now - lastSampleAtMs
                                if (sinceLastSample > WATCHDOG_SAMPLE_TIMEOUT_MS) {
                                    val msg = "${sinceLastSample / 1000}s since last sample (cycle $cycleNum, $samplesThisCycle this cycle)"
                                    Log.w(TAG, "[svc] watchdog: $msg — forcing reconnect")
                                    crashlyticsBreadcrumb("watchdog silence timeout: $msg")
                                    throw BleStuckException("silence timeout: $msg")
                                }
                            }
                        }
                    }
                    try {
                        bleSource.samples(directAddress = paired.address).collect { sample ->
                            samplesThisCycle += 1
                            samplesTotal += 1
                            latestSample = sample
                            latestBatteryPct = bleSource.battery.value
                            lastSampleAtMs = sample.receivedAtMs
                            recorder.append(sample, latestBatteryPct)
                            // v0.8.1 — feed Live state for HUD inline waveform +
                            // Live tab. Only when this service is the active BLE
                            // owner (no ride recording). When a ride is recording,
                            // RideRecorderService takes over BLE and writes to Live
                            // state instead.
                            LiveStateHolder.onBleNotification(
                                receivedAtMs = sample.receivedAtMs,
                                bpm = sample.bpm,
                                rrIntervalsMs = sample.rrIntervalsMs,
                            )
                            // Update notification at most every 5s to keep it fresh
                            // without churning notification updates.
                            if (samplesTotal % 5 == 0L) {
                                updateNotification(
                                    status = "Live · ${sample.bpm} bpm",
                                    subtitle = buildSubtitle(sample, latestBatteryPct, samplesTotal),
                                )
                            }
                            retryDelayMs = 5_000L
                        }
                    } finally {
                        watchdog.cancel()
                    }
                }
                Log.w(TAG, "[svc] BLE flow ended — reconnect in ${retryDelayMs}ms (cycle $cycleNum)")
                crashlyticsBreadcrumb("flow ended cleanly cycle=$cycleNum samples=$samplesThisCycle")
            } catch (e: CancellationException) {
                throw e
            } catch (e: BleStuckException) {
                Log.w(TAG, "[svc] BLE flow torn down by watchdog — reconnect in ${retryDelayMs}ms (cycle $cycleNum): ${e.message}")
                // No need to re-breadcrumb — watchdog already did it on throw
            } catch (e: Throwable) {
                Log.w(TAG, "[svc] BLE flow errored — reconnect in ${retryDelayMs}ms (cycle $cycleNum)", e)
                crashlyticsBreadcrumb("flow errored cycle=$cycleNum: ${e.message}")
            }
            reconnectCycle += 1
            // v0.9.60 — surface permission state in the notification. If
            // BLUETOOTH_CONNECT/SCAN were revoked mid-operation we keep
            // retrying (rather than stopping the service) so the moment
            // permission is restored monitoring resumes without the user
            // having to toggle anything.
            val permsOk = hasBlePermissions()
            if (permsOk) {
                updateNotification(
                    status = "Reconnecting...",
                    subtitle = "Retry ${retryDelayMs / 1000}s · cycle ${reconnectCycle - 1}",
                )
            } else {
                crashlyticsBreadcrumb("permission revoked at cycle=${reconnectCycle - 1}")
                updateNotification(
                    status = "Bluetooth permission revoked",
                    subtitle = "Open URUJ to re-grant — auto-resumes when restored",
                )
            }
            delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
        }
    }

    /**
     * v0.9.60 — sentinel exception thrown by the sample-arrival watchdog. Kept
     * as a private nested class so the catch arm can distinguish "watchdog
     * forced a reconnect because samples stopped arriving" from "the underlying
     * BLE flow itself threw" — the former is a recovery action, not an error.
     */
    private class BleStuckException(message: String) : RuntimeException(message)

    /**
     * v0.9.60 — verify BLUETOOTH_CONNECT (Android 12+) is granted. SCAN is
     * also required for the scan path but BiometricService only uses direct-
     * connect via the saved MAC, so CONNECT alone is sufficient. Pre-Android
     * 12 the new runtime permissions don't exist; we trust the install-time
     * BLUETOOTH grants in AndroidManifest.
     */
    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val connectGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
        return connectGranted
    }

    /**
     * v0.9.60 — write a Crashlytics breadcrumb. These are visible inline in
     * any subsequent crash report, letting us correlate "service was stuck
     * in N reconnect cycles right before the OOM" with the crash itself.
     * Defensive runCatching — never let breadcrumb logging crash the service.
     */
    private fun crashlyticsBreadcrumb(message: String) {
        runCatching {
            FirebaseCrashlytics.getInstance().log("[BLE] $message")
        }
    }

    private fun buildSubtitle(sample: HrSample, battery: Int?, total: Long): String {
        val parts = mutableListOf<String>()
        battery?.let { parts += "$it%" }
        sample.contactDetected?.let {
            parts += if (it) "contact ✓" else "no contact"
        }
        parts += "$total samples"
        return parts.joinToString(" · ")
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "URUJ::Continuous",
        ).apply {
            // 24h cap — service should hold it indefinitely while running.
            // Re-acquired below in updateNotification path if ever released.
            acquire(24L * 60L * 60L * 1_000L)
        }
        Log.d(TAG, "[svc] wake lock acquired")
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
            if (wakeLock != null) Log.d(TAG, "[svc] wake lock released")
        }
        wakeLock = null
    }

    private fun startInForeground() {
        val notification = buildNotification("Starting...", "Connecting to chest strap")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(status: String, subtitle: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.notify(NOTIFICATION_ID, buildNotification(status, subtitle))
        }
    }

    private fun buildNotification(status: String, subtitle: String): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_uruj)
            .setContentTitle("URUJ · 24/7 monitoring")
            .setContentText("$status · $subtitle")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pendingOpen)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        runCatching { recorder.close() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "URUJ-Biometric"
        const val ACTION_START = "com.uruj.action.START_BIOMETRIC"
        const val ACTION_STOP = "com.uruj.action.STOP_BIOMETRIC"
        private const val NOTIFICATION_ID = 2_001
        private const val CHANNEL_ID = "uruj_continuous_biometric"
        private const val CHANNEL_NAME = "24/7 biometric monitoring"

        // v0.9.60 — BLE watchdog parameters. Tuned for Magene H613 emitting at
        // ~1 Hz: 15s tick is cheap, 60s of total silence is a real fault (the
        // strap can't go silent that long while worn), 30s startup grace is
        // generous given typical connect + service-discovery + 5-stage GATT
        // chain takes ~5-10s in normal operation.
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val WATCHDOG_SAMPLE_TIMEOUT_MS = 60_000L
        private const val WATCHDOG_STARTUP_GRACE_MS = 30_000L

        fun ensureNotificationChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent BLE chest strap monitoring"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        /** Convenience for callers to start the service. */
        fun start(context: Context) {
            val intent = Intent(context, BiometricService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BiometricService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
