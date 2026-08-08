package com.huddlecommunity.better_native_video_player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.huddlecommunity.better_native_video_player.manager.SharedPlayerManager

/**
 * Foreground service of type mediaPlayback. Holds the process alive so the
 * shared ExoPlayer keeps decoding audio when the app is backgrounded / screen
 * locked — Android 17 silently silences background audio without a compliant
 * foreground service.
 *
 * Reuses the existing MediaSession notification from VideoPlayerNotificationHandler
 * (via SharedPlayerManager) when it's ready, so there is one media notification
 * with transport controls. If the session isn't ready yet (the service can be
 * started a beat before the first showNotification), it foregrounds with a
 * minimal placeholder so the process is kept alive without ever skipping the
 * mandatory startForeground call (which would crash the start).
 *
 * Started by `setBackgroundPlaybackEnabled(true)` *while the app is visible* so
 * the FGS is granted while-in-use capability; stopped when disabled.
 */
class PlaybackForegroundService : Service() {
    companion object {
        private const val TAG = "PlaybackFgService"
        private const val FALLBACK_CHANNEL_ID = "background_playback"
        private const val FALLBACK_NOTIFICATION_ID = 1002

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, PlaybackForegroundService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackForegroundService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val active = SharedPlayerManager.activeForegroundNotification()
        val id = active?.first ?: FALLBACK_NOTIFICATION_ID
        val notification = active?.second ?: buildFallbackNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                id,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(id, notification)
        }
        Log.d(TAG, "startForeground done (mediaNotification=${active != null})")
        return START_STICKY
    }

    /** Minimal notification used only when the MediaSession notification isn't
     *  ready yet, so the mandatory startForeground call always succeeds. */
    private fun buildFallbackNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(FALLBACK_CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    FALLBACK_CHANNEL_ID,
                    "Background playback",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }
        return NotificationCompat.Builder(this, FALLBACK_CHANNEL_ID)
            .setContentTitle("Playing in background")
            .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
