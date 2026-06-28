package com.huddlecommunity.better_native_video_player.handlers

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.huddlecommunity.better_native_video_player.NativeVideoPlayerPlugin
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.huddlecommunity.better_native_video_player.hls.TwitchLowLatencyHlsPlaylistParserFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.huddlecommunity.better_native_video_player.manager.SharedPlayerManager

/**
 * Handles method calls from Flutter for video player control
 * Equivalent to iOS VideoPlayerMethodHandler
 */
@UnstableApi
class VideoPlayerMethodHandler(
    private val context: Context,
    private val player: ExoPlayer,
    private val eventHandler: VideoPlayerEventHandler,
    private val notificationHandler: VideoPlayerNotificationHandler,
    private val updateMediaInfo: ((Map<String, Any>?) -> Unit)? = null,
    private val controllerId: Int? = null,
    private val enableHDR: Boolean = false
) {
    companion object {
        private const val TAG = "VideoPlayerMethod"
    }

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    // Track whether we were playing before an audio focus loss so we can
    // resume correctly when focus is regained (e.g., after a phone call).
    private var wasPlayingBeforeFocusLoss = false
    // Volume before a duck so regaining focus restores what Flutter set
    // (e.g. a muted stream) instead of stomping it to full volume.
    private var volumeBeforeDuck: Float? = null
    // Set while a transient focus loss has us paused. The playback listener
    // must NOT abandon the focus request for this pause — leaving the focus
    // stack means AUDIOFOCUS_GAIN is never delivered, so playback could
    // never auto-resume after a call/alarm/assistant interruption.
    private var pausedByFocusLoss = false

    private fun isInPipMode(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NativeVideoPlayerPlugin.getActivity()?.isInPictureInPictureMode ?: false
        } else false
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss: AUDIOFOCUS_GAIN is never delivered after
                // this (per Android audio-focus guidance), so don't arm
                // auto-resume — restarting requires an explicit user action.
                // The pause flows through the playback listener below, which
                // abandons the now-dead focus request.
                wasPlayingBeforeFocusLoss = false
                pausedByFocusLoss = false
                if (player.isPlaying && !isInPipMode()) {
                    player.pause()
                    Log.d(TAG, "Audio focus lost permanently — paused")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                wasPlayingBeforeFocusLoss = player.isPlaying
                if (player.isPlaying && !isInPipMode()) {
                    // Keep the focus request alive across this pause so
                    // AUDIOFOCUS_GAIN can resume playback when the
                    // interruption (call, alarm, assistant) ends.
                    pausedByFocusLoss = true
                    player.pause()
                    Log.d(TAG, "Audio focus lost (transient) — paused")
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (!isInPipMode()) {
                    if (volumeBeforeDuck == null) volumeBeforeDuck = player.volume
                    player.volume = player.volume * 0.3f
                    Log.d(TAG, "Audio focus ducking — lowered volume")
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                volumeBeforeDuck?.let { player.volume = it }
                volumeBeforeDuck = null
                pausedByFocusLoss = false
                if (wasPlayingBeforeFocusLoss) {
                    player.play()
                    wasPlayingBeforeFocusLoss = false
                    Log.d(TAG, "Audio focus regained — resumed playback")
                }
            }
        }
    }

    private val audioFocusPlaybackListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                pausedByFocusLoss = false
                requestAudioFocusForPlayback()
            } else if (!player.playWhenReady && !pausedByFocusLoss) {
                // Only abandon focus on true pause/stop (playWhenReady=false),
                // not on buffering pauses where isPlaying briefly becomes false
                // but the player intends to resume once buffer refills — and
                // not on a transient-focus-loss pause, where abandoning would
                // remove us from the focus stack and kill the GAIN resume.
                abandonAudioFocusForPlayback()
            }
        }
    }

    init {
        player.addListener(audioFocusPlaybackListener)
        // A handler attaching to an already-playing shared player (PiP view
        // recreation) must take over the audio focus that the orphaned
        // handler's deferred cleanup is about to abandon — playback continues
        // across the handoff, so no isPlaying transition will re-request it.
        if (player.isPlaying) {
            requestAudioFocusForPlayback()
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var availableQualities: List<Map<String, Any>> = emptyList()
    private var currentVideoIsHls = false // Track if current video is HLS for quality switching
    private var masterHlsUrl: String? = null // Original master playlist URL for auto quality
    private var currentHeaders: Map<String, String>? = null

    // One-shot listener added during handleLoad; stored so it can be cleaned up on dispose
    private var loadListener: Player.Listener? = null

    // The MethodChannel.Result of an in-flight load. Resolved with an error
    // when a newer load or cleanup supersedes it — silently dropping it
    // leaves the Dart `load()` future hanging forever.
    private var pendingLoadResult: MethodChannel.Result? = null

    // Callback to handle fullscreen requests from Flutter
    var onFullscreenRequest: ((Boolean) -> Unit)? = null

    private fun requestAudioFocusForPlayback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
            }
            audioFocusRequest?.let { request ->
                val result = audioManager.requestAudioFocus(request)
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Log.d(TAG, "Audio focus requested and granted")
                }
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocusForPlayback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
                audioFocusRequest = null
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    /**
     * Handles incoming method calls from Flutter
     */
    fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        Log.d(TAG, "Handling method call: ${call.method}")

        when (call.method) {
            "load" -> handleLoad(call, result)
            "play" -> handlePlay(result)
            "pause" -> handlePause(result)
            "seekTo" -> handleSeekTo(call, result)
            "setVolume" -> handleSetVolume(call, result)
            "setSpeed" -> handleSetSpeed(call, result)
            "setLooping" -> handleSetLooping(call, result)
            "setQuality" -> handleSetQuality(call, result)
            "getAvailableQualities" -> handleGetAvailableQualities(result)
            "getAvailableSubtitleTracks" -> handleGetAvailableSubtitleTracks(result)
            "setSubtitleTrack" -> handleSetSubtitleTrack(call, result)
            "enterFullScreen" -> handleEnterFullScreen(result)
            "exitFullScreen" -> handleExitFullScreen(result)
            "isAirPlayAvailable" -> handleIsAirPlayAvailable(result)
            "showAirPlayPicker" -> handleShowAirPlayPicker(result)
            "startAirPlayDetection" -> handleStartAirPlayDetection(result)
            "stopAirPlayDetection" -> handleStopAirPlayDetection(result)
            "disconnectAirPlay" -> handleDisconnectAirPlay(result)
            "setMediaInfo" -> handleSetMediaInfo(call, result)
            "configureForLivePlayback" -> handleConfigureForLivePlayback(call, result)
            "getLatencyToLive" -> handleGetLatencyToLive(result)
            "seekToLiveEdge" -> handleSeekToLiveEdge(result)
            "dispose" -> handleDispose(result)
            else -> result.notImplemented()
        }
    }

    /**
     * Loads a video URL into the player
     */
    private fun handleLoad(call: MethodCall, result: MethodChannel.Result) {
        val args = call.arguments as? Map<*, *>
        val url = args?.get("url") as? String

        if (url == null) {
            result.error("INVALID_URL", "URL is required", null)
            return
        }

        val autoPlay = args["autoPlay"] as? Boolean ?: false
        val headers = args["headers"] as? Map<String, String>
        currentHeaders = headers
        val mediaInfo = args["mediaInfo"] as? Map<String, Any>
        val drmConfig = args["drmConfig"] as? Map<*, *>

        // Store media info in the VideoPlayerView
        updateMediaInfo?.invoke(mediaInfo)
        mediaInfo?.let {
            val title = it["title"] as? String
            Log.d(TAG, "📱 Stored media info during load: $title")
        }

        Log.d(TAG, "Loading video: $url (autoPlay: $autoPlay)")
        Log.d(TAG, "Current player state - playbackState: ${player.playbackState}, duration: ${player.duration}, hasMedia: ${player.currentMediaItem != null}")

        // Only send "loading" event if player is actually starting to load new media
        // Don't send if player is already in IDLE state with no media loaded
        // This prevents incorrect "loading" state when player is already idle
        // Check: STATE_IDLE means no media is loaded, and duration < 0 means C.TIME_UNSET (no duration)
        // Also check if player has a current media item - if not, it's truly idle with no media
        val isPlayerIdleWithNoMedia = player.playbackState == Player.STATE_IDLE && 
                                      player.duration < 0 && 
                                      player.currentMediaItem == null
        if (isPlayerIdleWithNoMedia) {
            Log.d(TAG, "Player is already idle with no media (playbackState=${player.playbackState}, duration=${player.duration}, hasMedia=${player.currentMediaItem != null}), skipping loading event")
            // Don't send loading event - the initial state should have already sent "idle"
            // If initial state wasn't sent yet, it will be sent when EventChannel connects
        } else {
            // Player has media or is in a different state, send loading event
            Log.d(TAG, "Sending loading event - player is not idle or has media")
            eventHandler.sendEvent("loading")
        }

        // Determine if this is a local file or remote URL
        val isLocalFile = url.startsWith("file://") || url.startsWith("/")
        val isHls = isHlsUrl(url)
        currentVideoIsHls = isHls // Track for quality switching
        if (isHls) masterHlsUrl = url

        Log.d(TAG, "Video source type - Local: $isLocalFile, HLS: $isHls")

        // Build data source factory
        // For remote URLs with custom headers, use HTTP-specific data source
        // For local files, use DefaultDataSource which supports file:// URIs
        val finalDataSourceFactory = if (!isLocalFile && headers != null) {
            DefaultHttpDataSource.Factory().apply {
                setDefaultRequestProperties(headers)
            }
        } else {
            DefaultDataSource.Factory(context)
        }

        // Build MediaItem with metadata
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(url)

        // Add metadata if provided
        if (mediaInfo != null) {
            val metadataBuilder = androidx.media3.common.MediaMetadata.Builder()
            (mediaInfo["title"] as? String)?.let { metadataBuilder.setTitle(it) }
            (mediaInfo["subtitle"] as? String)?.let { metadataBuilder.setArtist(it) }
            (mediaInfo["album"] as? String)?.let { metadataBuilder.setAlbumTitle(it) }
            mediaItemBuilder.setMediaMetadata(metadataBuilder.build())
        }

        // Configure DRM if provided
        if (drmConfig != null) {
            val drmType = drmConfig["type"] as? String
            val licenseUrl = drmConfig["licenseUrl"] as? String
            val drmHeaders = drmConfig["headers"] as? Map<String, String>

            // HLS AES-128 is not DRM — ExoPlayer decrypts it natively from
            // the playlist's #EXT-X-KEY, so no DrmConfiguration is needed
            // (mapping it to ClearKey would break playback).
            val uuid = when (drmType?.lowercase()) {
                "widevine" -> C.WIDEVINE_UUID
                "clearkey" -> C.CLEARKEY_UUID
                "aes-128" -> null
                else -> {
                    Log.w(TAG, "Unknown DRM type: $drmType, defaulting to Widevine")
                    C.WIDEVINE_UUID
                }
            }

            if (uuid != null && licenseUrl != null) {
                val drmBuilder = MediaItem.DrmConfiguration.Builder(uuid)
                    .setLicenseUri(android.net.Uri.parse(licenseUrl))

                if (drmHeaders != null) {
                    drmBuilder.setLicenseRequestHeaders(drmHeaders)
                }

                mediaItemBuilder.setDrmConfiguration(drmBuilder.build())
                Log.d(TAG, "DRM configured - Type: $drmType, License URL: $licenseUrl")
            } else if (uuid != null) {
                Log.w(TAG, "DRM config provided but licenseUrl is missing")
            } else {
                Log.d(TAG, "AES-128 HLS — decrypted natively by ExoPlayer, no DrmConfiguration applied")
            }
        }

        // Configure for low-latency live HLS.
        //
        // Target ~2s behind the live edge to match Twitch's "low latency" mode
        // (the old WebView player's default). media3 ignores Twitch's
        // proprietary #EXT-X-TWITCH-PREFETCH partial segments, so a full
        // segment (~2s) is the floor; the playback-speed window below lets the
        // player gently catch back up to target after a rebuffer instead of
        // permanently drifting toward maxOffset. Without an explicit target,
        // media3 defaults to 3×segmentDuration (~6s), which is the regression.
        if (isHls) {
            mediaItemBuilder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(2_000)
                    .setMinOffsetMs(1_000)
                    .setMaxOffsetMs(6_000)
                    .setMinPlaybackSpeed(0.95f)
                    .setMaxPlaybackSpeed(1.05f)
                    .build()
            )
        }

        val mediaItem = mediaItemBuilder.build()

        // Create appropriate MediaSource based on URL type
        val mediaSource: MediaSource = if (isHls) {
            // HLS stream
            Log.d(TAG, "Creating HLS media source")
            HlsMediaSource.Factory(finalDataSourceFactory)
                // Promote Twitch's #EXT-X-TWITCH-PREFETCH segments so playback
                // rides the live edge (low latency); no-op for non-Twitch HLS.
                .setPlaylistParserFactory(TwitchLowLatencyHlsPlaylistParserFactory())
                .createMediaSource(mediaItem)
        } else {
            // Progressive download/playback (MP4, local files, etc.)
            Log.d(TAG, "Creating progressive media source")
            ProgressiveMediaSource.Factory(finalDataSourceFactory)
                .createMediaSource(mediaItem)
        }

        // Set media source
        player.setMediaSource(mediaSource)
        player.prepare()

        // Configure HDR settings for ExoPlayer using TrackSelectionParameters
        if (!enableHDR) {
            Log.d(TAG, "🎨 HDR disabled - ExoPlayer will use automatic tone-mapping for HDR content")
            // Note: ExoPlayer automatically tone-maps HDR content to SDR on devices
            // that don't support HDR or when the display doesn't support it.
            //
            // For more explicit control over track selection to avoid HDR tracks entirely,
            // we would need to:
            // 1. Implement a custom TrackSelector that filters based on Format.colorInfo.colorTransfer
            // 2. Check for COLOR_TRANSFER_HLG, COLOR_TRANSFER_ST2084 (HDR10), etc.
            // 3. Configure this at player creation time with a DefaultTrackSelector.Builder
            //
            // However, this is complex and may break adaptive streaming benefits.
            // ExoPlayer's automatic tone-mapping is generally sufficient for most use cases.
            //
            // See: https://github.com/androidx/media/issues/1074
        } else {
            Log.d(TAG, "🎨 HDR enabled - allowing native HDR playback")
        }

        // Fetch qualities asynchronously for HLS streams
        if (url.contains(".m3u8")) {
            scope.launch {
                availableQualities = VideoPlayerQualityHandler.fetchHLSQualities(url)
                Log.d(TAG, "Fetched ${availableQualities.size} qualities")

                // Store in SharedPlayerManager if this is a shared player
                if (controllerId != null) {
                    SharedPlayerManager.setQualities(controllerId, availableQualities)
                }

                // Send qualityChange event to notify Flutter that qualities are loaded
                if (availableQualities.isNotEmpty()) {
                    val defaultQuality = availableQualities.first()
                    eventHandler.sendEvent("qualityChange", mapOf(
                        "url" to (defaultQuality["url"] ?: ""),
                        "label" to (defaultQuality["label"] ?: "Auto"),
                        "isAuto" to (defaultQuality["isAuto"] ?: true),
                        "quality" to defaultQuality,
                        "qualities" to availableQualities
                    ))
                    Log.d(TAG, "Sent qualityChange event with ${availableQualities.size} available qualities")
                }
            }
        }

        // NOTE: Media session will be set up when playback starts (in VideoPlayerObserver)
        // This ensures the correct video's metadata is displayed even when switching between videos

        // Remove any previous load listener before adding a new one, and
        // resolve its result so the superseded Dart future doesn't hang
        failPendingLoad("A newer load replaced this request")

        // Wait for player to be ready
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    eventHandler.sendEvent("loaded")
                    player.removeListener(this)
                    loadListener = null
                    pendingLoadResult = null

                    // Send AirPlay availability (always false on Android)
                    checkAndSendAirPlayAvailability()

                    // Auto play if requested - MUST be done after player is ready
                    if (autoPlay) {
                        Log.d(TAG, "Auto-playing video after ready")
                        requestAudioFocusForPlayback()
                        player.play()
                        // Play event will be sent automatically by VideoPlayerObserver
                    }

                    result.success(null)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                player.removeListener(this)
                loadListener = null
                pendingLoadResult = null
                result.error("LOAD_ERROR", error.message ?: "Unknown error", null)
            }
        }
        loadListener = listener
        pendingLoadResult = result
        player.addListener(listener)
    }

    /**
     * Starts playback
     */
    private fun handlePlay(result: MethodChannel.Result) {
        // Manual play takes over from any pending focus-loss auto-resume.
        pausedByFocusLoss = false
        wasPlayingBeforeFocusLoss = false
        requestAudioFocusForPlayback()
        player.play()
        result.success(null)
    }

    /**
     * Pauses playback
     */
    private fun handlePause(result: MethodChannel.Result) {
        // An explicit pause overrides any pending focus-loss auto-resume —
        // clear the latches so the abandon below isn't skipped and a later
        // GAIN can't restart a stream the user chose to stop.
        pausedByFocusLoss = false
        wasPlayingBeforeFocusLoss = false
        player.pause()
        abandonAudioFocusForPlayback()
        result.success(null)
    }

    /**
     * Seeks to a specific position
     */
    private fun handleSeekTo(call: MethodCall, result: MethodChannel.Result) {
        val args = call.arguments as? Map<*, *>
        val milliseconds = args?.get("milliseconds") as? Int
        if (milliseconds != null) {
            player.seekTo(milliseconds.toLong())
            eventHandler.sendEvent("seek", mapOf("position" to milliseconds))
        }
        result.success(null)
    }

    /**
     * Sets playback volume
     */
    private fun handleSetVolume(call: MethodCall, result: MethodChannel.Result) {
        val args = call.arguments as? Map<*, *>
        val volume = args?.get("volume") as? Double
        if (volume != null) {
            player.volume = volume.toFloat()
        }
        result.success(null)
    }

    /**
     * Sets playback speed
     */
    private fun handleSetSpeed(call: MethodCall, result: MethodChannel.Result) {
        val args = call.arguments as? Map<*, *>
        val speed = args?.get("speed") as? Double
        if (speed != null) {
            player.setPlaybackSpeed(speed.toFloat())
            eventHandler.sendEvent("speedChange", mapOf("speed" to speed))
        }
        result.success(null)
    }

    /**
     * Sets whether the video should loop
     */
    private fun handleSetLooping(call: MethodCall, result: MethodChannel.Result) {
        val args = call.arguments as? Map<*, *>
        val looping = args?.get("looping") as? Boolean
        if (looping != null) {
            player.repeatMode = if (looping) {
                androidx.media3.common.Player.REPEAT_MODE_ONE
            } else {
                androidx.media3.common.Player.REPEAT_MODE_OFF
            }
            Log.d(TAG, "Looping set to: $looping")
        }
        result.success(null)
    }

    /**
     * Changes video quality (for HLS streams).
     *
     * Uses DefaultTrackSelector constraints instead of replacing the HLS
     * source. This lets ExoPlayer switch variants within the existing
     * master playlist at the next segment boundary — no black-screen
     * flash or loading spinner.
     */
    private fun handleSetQuality(call: MethodCall, result: MethodChannel.Result) {
        if (!currentVideoIsHls) {
            result.error("NOT_HLS", "Quality switching is only available for HLS streams", null)
            return
        }

        val args = call.arguments as? Map<*, *>
        val qualityInfo = args?.get("quality") as? Map<*, *>

        if (qualityInfo == null) {
            result.error("INVALID_QUALITY", "Invalid quality data", null)
            return
        }

        val trackSelector = player.trackSelector as? DefaultTrackSelector
        if (trackSelector == null) {
            result.error("NO_TRACK_SELECTOR", "DefaultTrackSelector not available", null)
            return
        }

        val isAuto = qualityInfo["isAuto"] as? Boolean ?: false

        if (isAuto) {
            val qualityPayload = availableQualities.firstOrNull {
                it["isAuto"] as? Boolean == true
            } ?: mapOf(
                "url" to (masterHlsUrl ?: ""),
                "label" to "Auto",
                "isAuto" to true
            )

            // Remove constraints — ExoPlayer uses native ABR
            trackSelector.setParameters(
                trackSelector.buildUponParameters()
                    .clearVideoSizeConstraints()
                    .setMaxVideoBitrate(Int.MAX_VALUE)
                    .setMinVideoBitrate(0)
            )

            eventHandler.sendEvent("qualityChange", mapOf(
                "url" to (masterHlsUrl ?: ""),
                "label" to "Auto",
                "isAuto" to true,
                "quality" to qualityPayload,
                "qualities" to availableQualities
            ))

            result.success(null)
        } else {
            val url = qualityInfo["url"] as? String
            val label = qualityInfo["label"] as? String
            val bitrate = qualityInfo["bitrate"] as? Int ?: Int.MAX_VALUE
            val width = qualityInfo["width"] as? Int ?: Int.MAX_VALUE
            val height = qualityInfo["height"] as? Int ?: Int.MAX_VALUE

            // Pin to this quality — ExoPlayer switches at the next segment
            // boundary without interrupting playback. Clear size constraints
            // when width/height are unset (MAX_VALUE) to avoid filtering out
            // every track; same for audio-only (0,0) which has no video size.
            val params = trackSelector.buildUponParameters()
                .setMinVideoBitrate(bitrate)
                .setMaxVideoBitrate(bitrate)
            if (width > 0 && width != Int.MAX_VALUE && height > 0 && height != Int.MAX_VALUE) {
                params.setMinVideoSize(width, height).setMaxVideoSize(width, height)
            } else {
                params.clearVideoSizeConstraints()
            }
            trackSelector.setParameters(params)

            eventHandler.sendEvent("qualityChange", mapOf(
                "url" to (url ?: ""),
                "label" to (label ?: ""),
                "isAuto" to false,
                "quality" to mapOf(
                    "url" to (url ?: ""),
                    "label" to (label ?: ""),
                    "bitrate" to bitrate,
                    "width" to width,
                    "height" to height,
                    "isAuto" to false
                ),
                "qualities" to availableQualities
            ))

            result.success(null)
        }
    }


    /**
     * Returns available video qualities
     */
    private fun handleGetAvailableQualities(result: MethodChannel.Result) {
        // First check if we have qualities in this instance
        if (availableQualities.isNotEmpty()) {
            result.success(availableQualities)
        } else if (controllerId != null) {
            // If instance is empty but cache has qualities, restore them
            val cachedQualities = SharedPlayerManager.getQualities(controllerId)
            if (cachedQualities != null && cachedQualities.isNotEmpty()) {
                availableQualities = cachedQualities
                Log.d(TAG, "🔄 Restored ${cachedQualities.size} qualities from cache for controller $controllerId")
                result.success(cachedQualities)
            } else {
                result.success(availableQualities)
            }
        } else {
            result.success(availableQualities)
        }
    }

    /**
     * Cleans up listeners and coroutine scope without removing the shared player.
     * Called from VideoPlayerView.dispose() to prevent leaks when the platform
     * view is destroyed without a Flutter-initiated "dispose" method call.
     */
    fun cleanup() {
        scope.cancel()
        failPendingLoad("Player disposed before load completed")
        player.removeListener(audioFocusPlaybackListener)
        abandonAudioFocusForPlayback()
    }

    /**
     * Removes the in-flight load listener and resolves its result with
     * LOAD_SUPERSEDED — silently dropping it would leave the Dart `load()`
     * future hanging forever.
     */
    private fun failPendingLoad(message: String) {
        loadListener?.let { player.removeListener(it) }
        loadListener = null
        pendingLoadResult?.error("LOAD_SUPERSEDED", message, null)
        pendingLoadResult = null
    }

    /**
     * Disposes the player
     */
    private fun handleDispose(result: MethodChannel.Result) {
        cleanup()
        player.stop()

        if (controllerId != null) {
            // Shared manager owns the player — its removePlayer() handles
            // release() and notification teardown.
            SharedPlayerManager.removePlayer(controllerId)
            Log.d(TAG, "Removed shared player for controller ID: $controllerId")
        } else {
            // Non-shared player has no manager to release it. Per Media3
            // docs, callers must invoke release() so decoders, HTTP cache,
            // and Surface buffers don't linger past the view's lifetime.
            player.release()
        }

        eventHandler.sendEvent("stopped")
        result.success(null)
    }

    /**
     * Enters fullscreen mode
     * Triggers the native fullscreen dialog
     */
    private fun handleEnterFullScreen(result: MethodChannel.Result) {
        Log.d(TAG, "Flutter requested enter fullscreen")
        onFullscreenRequest?.invoke(true)
        result.success(null)
    }

    /**
     * Exits fullscreen mode
     * Dismisses the native fullscreen dialog
     */
    private fun handleExitFullScreen(result: MethodChannel.Result) {
        Log.d(TAG, "Flutter requested exit fullscreen")
        onFullscreenRequest?.invoke(false)
        result.success(null)
    }

    /**
     * Checks if AirPlay is available (iOS only - always false on Android)
     * AirPlay is an Apple technology and not available on Android
     */
    private fun handleIsAirPlayAvailable(result: MethodChannel.Result) {
        Log.d(TAG, "AirPlay availability checked - not supported on Android")
        // AirPlay is not available on Android
        result.success(false)
    }

    /**
     * Shows AirPlay picker (iOS only - no-op on Android)
     * AirPlay is an Apple technology and not available on Android
     */
    private fun handleShowAirPlayPicker(result: MethodChannel.Result) {
        Log.d(TAG, "AirPlay picker requested but not supported on Android")
        // Simply return success - AirPlay is not available on Android
        result.success(null)
    }

    /**
     * Starts AirPlay device detection (iOS only - no-op on Android)
     * AirPlay is an Apple technology and not available on Android
     */
    private fun handleStartAirPlayDetection(result: MethodChannel.Result) {
        Log.d(TAG, "AirPlay detection start requested but not supported on Android")
        // Simply return success - AirPlay is not available on Android
        result.success(null)
    }

    /**
     * Stops AirPlay device detection (iOS only - no-op on Android)
     * AirPlay is an Apple technology and not available on Android
     */
    private fun handleStopAirPlayDetection(result: MethodChannel.Result) {
        Log.d(TAG, "AirPlay detection stop requested but not supported on Android")
        // Simply return success - AirPlay is not available on Android
        result.success(null)
    }

    /**
     * Disconnects from AirPlay device (iOS only - no-op on Android)
     * AirPlay is an Apple technology and not available on Android
     */
    private fun handleDisconnectAirPlay(result: MethodChannel.Result) {
        Log.d(TAG, "AirPlay disconnect requested but not supported on Android")
        // Simply return success - AirPlay is not available on Android
        result.success(null)
    }

    /**
     * Sends AirPlay availability (always false on Android)
     * AirPlay is an Apple-only technology
     */
    private fun checkAndSendAirPlayAvailability() {
        Log.d(TAG, "📡 AirPlay availability check: false (Android)")
        eventHandler.sendEvent("airPlayAvailabilityChanged", mapOf("isAvailable" to false))
    }

    /**
     * Determines if a URL is an HLS stream
     * Checks for .m3u8 extension or common HLS patterns
     */
    private fun isHlsUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        // Check for .m3u8 extension (most reliable indicator)
        if (lowerUrl.contains(".m3u8")) {
            return true
        }
        // Check for /hls/ as a path segment
        if (lowerUrl.contains("/hls/")) {
            return true
        }
        return false
    }

    // MARK: - Subtitle Track Handling

    /**
     * Gets available subtitle tracks from the current player
     */
    private fun handleGetAvailableSubtitleTracks(result: MethodChannel.Result) {
        try {
            val tracks = mutableListOf<Map<String, Any>>()

            // Get the current tracks from the player
            val currentTracks = player.currentTracks

            // Get track selection parameters to find the selected track
            val trackSelectionParameters = player.trackSelectionParameters

            // Iterate through all track groups
            for (groupIndex in 0 until currentTracks.groups.size) {
                val group = currentTracks.groups[groupIndex]

                // Only process text (subtitle) tracks
                if (group.type == C.TRACK_TYPE_TEXT) {
                    // Iterate through all tracks in this group
                    for (trackIndex in 0 until group.length) {
                        val format = group.getTrackFormat(trackIndex)
                        val isSelected = group.isTrackSelected(trackIndex)

                        // Get language code (e.g., "en", "es", "fr")
                        val languageCode = format.language ?: "unknown"

                        // Get display name (use label if available, otherwise language code)
                        val displayName = format.label?.takeIf { it.isNotEmpty() }
                            ?: languageCode.let { code ->
                                // Try to get localized language name
                                try {
                                    val locale = java.util.Locale(code)
                                    locale.getDisplayLanguage(java.util.Locale.getDefault())
                                        .takeIf { it.isNotEmpty() } ?: code
                                } catch (e: Exception) {
                                    code
                                }
                            }

                        val trackInfo = mapOf(
                            "index" to trackIndex,
                            "language" to languageCode,
                            "displayName" to displayName,
                            "isSelected" to isSelected
                        )

                        tracks.add(trackInfo)
                        Log.d(TAG, "📝 Found subtitle track: $displayName ($languageCode) - Selected: $isSelected")
                    }
                }
            }

            Log.d(TAG, "📝 Total subtitle tracks found: ${tracks.size}")
            result.success(tracks)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting subtitle tracks: ${e.message}", e)
            result.success(emptyList<Map<String, Any>>())
        }
    }

    /**
     * Sets the subtitle track
     */
    private fun handleSetSubtitleTrack(call: MethodCall, result: MethodChannel.Result) {
        try {
            val args = call.arguments as? Map<*, *>
            val trackInfo = args?.get("track") as? Map<*, *>
            val index = trackInfo?.get("index") as? Int

            if (index == null) {
                result.error("INVALID_TRACK", "Invalid subtitle track data", null)
                return
            }

            // Index -1 means disable subtitles
            if (index == -1) {
                Log.d(TAG, "📝 Disabling subtitles")

                // Disable text track selection
                val newParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()

                player.trackSelectionParameters = newParameters

                eventHandler.sendEvent("subtitleChange", mapOf(
                    "index" to -1,
                    "language" to "off",
                    "displayName" to "Off",
                    "isSelected" to false
                ))

                result.success(null)
                return
            }

            // Enable text tracks first
            var parametersBuilder = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)

            // Find the track format for the requested index
            val currentTracks = player.currentTracks
            var trackFound = false
            var selectedLanguage = "unknown"
            var selectedDisplayName = "Unknown"

            for (groupIndex in 0 until currentTracks.groups.size) {
                val group = currentTracks.groups[groupIndex]

                if (group.type == C.TRACK_TYPE_TEXT && index < group.length) {
                    val format = group.getTrackFormat(index)
                    selectedLanguage = format.language ?: "unknown"
                    selectedDisplayName = format.label?.takeIf { it.isNotEmpty() }
                        ?: selectedLanguage

                    // Set preferred text language to the selected track's language
                    parametersBuilder = parametersBuilder
                        .setPreferredTextLanguage(selectedLanguage)

                    trackFound = true
                    break
                }
            }

            if (!trackFound) {
                result.error("INVALID_INDEX", "Invalid subtitle track index", null)
                return
            }

            player.trackSelectionParameters = parametersBuilder.build()

            Log.d(TAG, "📝 Selected subtitle track: $selectedDisplayName ($selectedLanguage)")

            eventHandler.sendEvent("subtitleChange", mapOf(
                "index" to index,
                "language" to selectedLanguage,
                "displayName" to selectedDisplayName,
                "isSelected" to true
            ))

            result.success(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting subtitle track: ${e.message}", e)
            result.error("ERROR", "Failed to set subtitle track: ${e.message}", null)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleSetMediaInfo(call: MethodCall, result: MethodChannel.Result) {
        val args = call.arguments as? Map<*, *>
        val mediaInfo = args?.get("mediaInfo") as? Map<String, Any>
        updateMediaInfo?.invoke(mediaInfo)
        if (mediaInfo != null) {
            notificationHandler.updateMediaMetadata(mediaInfo)
        }
        result.success(null)
    }

    private fun handleConfigureForLivePlayback(call: MethodCall, result: MethodChannel.Result) {
        result.success(null)
    }

    private fun handleGetLatencyToLive(result: MethodChannel.Result) {
        val offsetMs = player.currentLiveOffset
        if (offsetMs == C.TIME_UNSET) {
            result.success(null)
        } else {
            result.success(offsetMs / 1000.0)
        }
    }

    private fun handleSeekToLiveEdge(result: MethodChannel.Result) {
        player.seekToDefaultPosition()
        result.success(null)
    }
}
