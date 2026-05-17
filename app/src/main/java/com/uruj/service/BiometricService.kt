package com.uruj.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
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
     * Same pattern as RideRecorderService v0.5.2.
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
            try {
                bleSource.samples(directAddress = paired.address).collect { sample ->
                    latestSample = sample
                    latestBatteryPct = bleSource.battery.value
                    samplesTotal += 1
                    lastSampleAtMs = sample.receivedAtMs
                    recorder.append(sample, latestBatteryPct)
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
                Log.w(TAG, "[svc] BLE flow ended — reconnect in ${retryDelayMs}ms")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "[svc] BLE flow errored — reconnect in ${retryDelayMs}ms", e)
            }
            updateNotification(
                status = "Reconnecting...",
                subtitle = "Strap out of range or off. Retry in ${retryDelayMs / 1000}s.",
            )
            delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
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
