package com.uruj.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.uruj.MainActivity
import com.uruj.R

object RideNotifications {
    const val CHANNEL_ID = "uruj_ride_recording"
    const val NOTIFICATION_ID = 1

    fun ensureChannel(context: Context) {
        // IMPORTANCE_DEFAULT (not LOW) so OEM launchers don't bury this under "Silent".
        // We disable sound/vibration explicitly — the channel is silent but ranked normally
        // so it appears in the main notification list, not the collapsed silent section.
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    fun build(context: Context, state: RideState): Notification {
        // Bare-minimum notification for OEM compatibility debugging. Once we confirm it
        // shows reliably, we layer back the rich expanded view + actions.
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, RideRecorderService::class.java).apply {
                action = RideRecorderService.ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = "URUJ • ${"%.1f".format(state.currentSpeedKph)} kph"
        val text = buildString {
            append("%.2f km".format(state.totalDistanceMeters / 1000))
            append(" · ")
            append(formatDuration(state.movingTimeMs))
            if (state.isPaused) append(" · PAUSED")
            val hr = state.bleLiveBpm ?: state.latestSample?.hrBpm
            if (hr != null) append(" · $hr bpm")
            // v0.9.78 — cadence on the lock screen too, but only when a sensor
            // is paired: no sensor, no phantom "0 rpm".
            val cadence = state.cadenceRpm
            if (state.cadenceSensorName != null && cadence != null) append(" · $cadence rpm")
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_uruj)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "%d:%02d:%02d".format(h, m, s)
    }
}
