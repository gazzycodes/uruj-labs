package com.uruj.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.uruj.data.BiometricSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * v0.7.0 — Listens for the ACTION_BOOT_COMPLETED broadcast and starts
 * BiometricService if the user has 24/7 monitoring toggled ON.
 *
 * Why this exists: 2026-05-18 morning, the user's phone died overnight
 * (Spotify drained the battery). On power-up Samsung Health auto-resumed
 * its background tracking, but URUJ did not — required manually opening
 * the app for the service to come back. This fixes that.
 *
 * Limitations:
 *   - Won't fire on first power-up after install (Android security: app
 *     must have been launched at least once for broadcasts to be delivered)
 *   - Some aggressive OEM ROMs (MIUI, ColorOS, OxygenOS) may delay the
 *     broadcast or refuse to deliver to background apps. User may need
 *     to allow URUJ to "autostart" in OEM settings.
 *
 * BroadcastReceiver runs on the main thread with a short timeout (~10s)
 * before Android kills it. We need to:
 *   1. Read the toggle from DataStore (suspend / IO) — wrapped in a
 *      goAsync() pending result so we can finish before the receiver
 *      lifetime expires.
 *   2. Start the service via context.startForegroundService if enabled.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        Log.d(TAG, "[boot] received ${intent.action}, checking 24/7 monitoring toggle")
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                val settings = BiometricSettingsStore(context).current()
                if (settings.enabled) {
                    Log.d(TAG, "[boot] toggle ON — starting BiometricService")
                    BiometricService.start(context)
                } else {
                    Log.d(TAG, "[boot] toggle OFF — skipping service start")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "[boot] BOOT_COMPLETED handler failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "URUJ-BootReceiver"
    }
}
