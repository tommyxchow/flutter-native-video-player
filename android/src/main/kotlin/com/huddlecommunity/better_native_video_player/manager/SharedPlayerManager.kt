package com.huddlecommunity.better_native_video_player.manager

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.huddlecommunity.better_native_video_player.handlers.VideoPlayerMethodHandler
import com.huddlecommunity.better_native_video_player.handlers.VideoPlayerNotificationHandler
import com.huddlecommunity.better_native_video_player.handlers.VideoPlayerEventHandler
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages shared ExoPlayer instances and NotificationHandlers across multiple platform views
 * Keeps players and notification handlers alive even when platform views are disposed
 * Note: Each platform view gets its own PlayerView, but they share the same ExoPlayer and NotificationHandler
 */
object SharedPlayerManager {
    private const val TAG = "SharedPlayerManager"

    private val players = ConcurrentHashMap<Int, ExoPlayer>()
    private val notificationHandlers = ConcurrentHashMap<Int, VideoPlayerNotificationHandler>()

    // Track active platform views for each controller
    // Map<ControllerId, Map<ViewId, SurfaceReconnectCallback>>
    private val activeViews = ConcurrentHashMap<Int, ConcurrentHashMap<Long, () -> Unit>>()

    // Store available qualities for each controller
    // This ensures qualities persist across view recreations
    private val qualitiesCache = ConcurrentHashMap<Int, List<Map<String, Any>>>()

    // Method handlers whose platform view was disposed during PiP. Their
    // cleanup() is deferred so audio-focus handling keeps working while
    // playback continues without a view — but they must be cleaned up when a
    // successor view takes over or the controller is removed, or each PiP
    // cycle leaks a live focus listener on the shared player.
    private val orphanedMethodHandlers = ConcurrentHashMap<Int, VideoPlayerMethodHandler>()

    /**
     * Defers cleanup of a method handler whose view was disposed during PiP.
     * Any previously orphaned handler for the controller is cleaned up now.
     */
    fun adoptOrphanedMethodHandler(controllerId: Int, handler: VideoPlayerMethodHandler) {
        orphanedMethodHandlers.put(controllerId, handler)?.cleanup()
    }

    /**
     * Cleans up the orphaned handler (if any) once a successor view's
     * handler is attached to the controller's player.
     */
    fun clearOrphanedMethodHandler(controllerId: Int) {
        orphanedMethodHandlers.remove(controllerId)?.cleanup()
    }

    /**
     * Gets or creates a player for the given controller ID
     * Returns a Pair<ExoPlayer, Boolean> where the Boolean indicates if the player already existed (true) or was newly created (false)
     */
    fun getOrCreatePlayer(context: Context, controllerId: Int): Pair<ExoPlayer, Boolean> {
        val alreadyExisted = players.containsKey(controllerId)
        val player = players.getOrPut(controllerId) { newPlayer(context) }
        return Pair(player, alreadyExisted)
    }

    /// Builds an ExoPlayer wired with the player flags every call site needs:
    /// default audio focus and a network-scoped wake lock so screen-off live
    /// HLS keeps playing without holding the CPU during pauses (the wake
    /// lock auto-releases on pause/stop). Requires the WAKE_LOCK manifest
    /// permission.
    fun newPlayer(context: Context): ExoPlayer =
        ExoPlayer.Builder(context)
            .setTrackSelector(DefaultTrackSelector(context))
            .setAudioAttributes(AudioAttributes.DEFAULT, false)
            .setLoadControl(lowLatencyLoadControl())
            .build()
            .apply { setWakeMode(C.WAKE_MODE_NETWORK) }

    /// LoadControl tuned for low-latency live HLS.
    ///
    /// ExoPlayer's defaults gate (re)start of playback behind a 2.5s/5s buffer.
    /// After any rebuffer the 5s `bufferForPlaybackAfterRebuffer` forces the
    /// player ~5s behind the live edge and the gentle live-offset catch-up
    /// never recovers it — so a single stall permanently inflates latency to
    /// ~9s. Resuming with a small buffer keeps playback near the edge; the
    /// live-offset target (set per MediaItem) does the rest. Buffer ceilings
    /// stay modest since a live window can't be buffered past its edge anyway.
    private fun lowLatencyLoadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 2_000,
                /* maxBufferMs = */ 10_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500,
            )
            .build()

    /**
     * Gets or creates a notification handler for the given controller ID
     */
    fun getOrCreateNotificationHandler(
        context: Context,
        controllerId: Int,
        player: ExoPlayer,
        eventHandler: VideoPlayerEventHandler
    ): VideoPlayerNotificationHandler {
        return notificationHandlers.getOrPut(controllerId) {
            VideoPlayerNotificationHandler(context, player, eventHandler)
        }
    }

    /**
     * The `(id, notification)` for any active player, for the foreground service
     * to call `startForeground` with. Returns the first ready notification, or
     * null if no player has an initialized MediaSession yet.
     */
    fun activeForegroundNotification(): Pair<Int, android.app.Notification>? {
        for (handler in notificationHandlers.values) {
            val notification = handler.foregroundNotification() ?: continue
            return VideoPlayerNotificationHandler.NOTIFICATION_ID to notification
        }
        return null
    }

    /**
     * Registers a platform view for a controller
     * The callback will be called when another view using the same controller is disposed
     */
    fun registerView(controllerId: Int, viewId: Long, reconnectCallback: () -> Unit) {
        // A live view (with its own method handler) supersedes any handler
        // orphaned by a PiP-time view disposal — clean the orphan up here so
        // every registration path enforces the one-focus-handler invariant.
        clearOrphanedMethodHandler(controllerId)
        val views = activeViews.getOrPut(controllerId) { ConcurrentHashMap() }
        views[viewId] = reconnectCallback
        Log.d(TAG, "Registered view $viewId for controller $controllerId (total views: ${views.size})")
    }

    /**
     * Unregisters a platform view and notifies other views to reconnect
     */
    @Synchronized
    fun unregisterView(controllerId: Int, viewId: Long) {
        val views = activeViews[controllerId]
        if (views != null) {
            views.remove(viewId)
            Log.d(TAG, "Unregistered view $viewId for controller $controllerId (remaining views: ${views.size})")

            // Snapshot remaining callbacks to avoid concurrent modification
            val callbacks = views.values.toList()
            for (callback in callbacks) {
                try {
                    callback()
                } catch (e: Exception) {
                    Log.e(TAG, "Error calling reconnect callback: ${e.message}", e)
                }
            }

            // Clean up empty maps
            if (views.isEmpty()) {
                activeViews.remove(controllerId)
            }
        }
    }

    /**
     * Sets available qualities for a controller
     * This ensures qualities persist across view recreations
     */
    fun setQualities(controllerId: Int, qualities: List<Map<String, Any>>) {
        qualitiesCache[controllerId] = qualities
        Log.d(TAG, "Stored ${qualities.size} qualities for controller $controllerId")
    }

    /**
     * Gets available qualities for a controller
     * Returns null if no qualities have been stored for this controller
     */
    fun getQualities(controllerId: Int): List<Map<String, Any>>? {
        return qualitiesCache[controllerId]
    }

    /**
     * Stops all views for a given controller
     */
    fun stopAllViewsForController(controllerId: Int) {
        val player = players[controllerId] ?: return

        // Stop playback
        player.stop()

        Log.d(TAG, "Stopped all views for controller $controllerId")
    }

    /**
     * Removes a player (called when explicitly disposed)
     */
    fun removePlayer(controllerId: Int) {
        // First stop all views using this player
        stopAllViewsForController(controllerId)

        // Clean up any handler orphaned by a PiP-time view disposal before
        // the player is released (cleanup removes its player listener).
        clearOrphanedMethodHandler(controllerId)

        // Release notification handler
        notificationHandlers[controllerId]?.release()
        notificationHandlers.remove(controllerId)

        // Release player
        players[controllerId]?.release()
        players.remove(controllerId)

        // Remove qualities cache
        qualitiesCache.remove(controllerId)

        // Clear active views for this controller
        activeViews.remove(controllerId)

        Log.d(TAG, "Removed player for controller $controllerId")
    }

    /**
     * Clears all players (e.g., on engine detach)
     */
    fun clearAll() {
        // Clean up handlers orphaned by PiP-time view disposals — their
        // focus listeners reference players that are about to be released.
        orphanedMethodHandlers.keys.toList().forEach { clearOrphanedMethodHandler(it) }

        // Release all notification handlers
        notificationHandlers.values.forEach { it.release() }
        notificationHandlers.clear()

        // Release all players
        players.values.forEach { it.release() }
        players.clear()

        // Clear qualities cache
        qualitiesCache.clear()
    }
}
