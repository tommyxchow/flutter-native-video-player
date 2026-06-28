package com.huddlecommunity.better_native_video_player.hls

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Adds Twitch low-latency support on top of media3's HLS parser.
 *
 * Twitch advertises the bleeding-edge segments the encoder is still writing via
 * a proprietary `#EXT-X-TWITCH-PREFETCH:<url>` tag at the tail of each media
 * playlist (two of them, ~1 segment apart). The web player plays these to ride
 * the live edge; media3 doesn't know the tag and silently drops it, leaving
 * playback ~2 segments (~4s) behind — the latency regression vs the old WebView
 * player.
 *
 * This factory wraps the stock parser and rewrites prefetch lines into normal
 * `#EXTINF` segments *before* media3 parses it, so ExoPlayer treats them as
 * playable.
 *
 * Twitch advertises two prefetch segments. The second is the bleeding edge the
 * encoder is still writing, so reaching for it occasionally hits a not-ready
 * segment → a brief stall, after which the player sits ~1 segment further back
 * and the gentle live-offset catch-up never recovers it. media3 (unlike the web
 * player) can't consume the partial segment, so we promote only the first
 * [maxPromoted] (oldest, reliably-ready) prefetch segments and drop the rest —
 * trading the theoretical minimum latency for stable playback near the edge.
 */
@UnstableApi
class TwitchLowLatencyHlsPlaylistParserFactory(
    private val delegate: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory(),
    private val maxPromoted: Int = 1,
) : HlsPlaylistParserFactory {

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        RewritingParser(delegate.createPlaylistParser(), maxPromoted)

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> =
        RewritingParser(
            delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist),
            maxPromoted,
        )

    /** Transforms the playlist text, then delegates to the real parser. */
    private class RewritingParser(
        private val inner: ParsingLoadable.Parser<HlsPlaylist>,
        private val maxPromoted: Int,
    ) : ParsingLoadable.Parser<HlsPlaylist> {
        override fun parse(uri: Uri, inputStream: InputStream): HlsPlaylist {
            val text = inputStream.readBytes().toString(Charsets.UTF_8)
            // Master playlists never carry the tag, so this is a no-op for them.
            val rewritten =
                if (text.contains(PREFETCH_TAG)) promotePrefetch(text, maxPromoted) else text
            return inner.parse(
                uri,
                ByteArrayInputStream(rewritten.toByteArray(Charsets.UTF_8)),
            )
        }
    }

    companion object {
        private const val TAG = "TwitchLowLatency"
        private const val PREFETCH_TAG = "#EXT-X-TWITCH-PREFETCH:"
        private const val EXTINF_TAG = "#EXTINF:"
        private const val TARGETDURATION_TAG = "#EXT-X-TARGETDURATION:"
        // Twitch low-latency segments are ~2s; only used if no real segment is
        // present to copy a duration from (effectively never).
        private const val DEFAULT_SEGMENT_DURATION = "2.000"

        /**
         * Rewrites the first [maxPromoted] `#EXT-X-TWITCH-PREFETCH:<url>` lines
         * (oldest first — they're in playback order at the playlist tail) into
         * standard `#EXTINF` + URL segments and drops any beyond that. Each
         * promoted segment continues the media sequence, so the live edge
         * advances and ExoPlayer plays closer to live, while dropping the
         * newest (still-being-written) prefetch avoids stalls.
         */
        fun promotePrefetch(playlist: String, maxPromoted: Int): String {
            val lines = playlist.split("\n")

            // Reuse the stream's own segment duration so ExoPlayer's buffering
            // and live-offset math stay accurate.
            val duration = lines
                .lastOrNull { it.startsWith(EXTINF_TAG) }
                ?.substringAfter(EXTINF_TAG)
                ?.substringBefore(",")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_SEGMENT_DURATION

            // Twitch advertises #EXT-X-TARGETDURATION:5 but emits ~2s segments.
            // media3 paces media-playlist reloads off the target duration, so
            // with TD=5 it only polls every ~5-6s while segments arrive every
            // 2s — the player starves, stalls, and ratchets ~2s further behind
            // each cycle until it sits far enough back to bridge the slow
            // reload. Rewriting TD down to the real segment cadence makes
            // media3 poll in step with the segments, so it sustains a low
            // offset without starving. (Spec-valid: TD must be >= the rounded
            // max segment duration, which is what we set it to.)
            val reloadTarget =
                kotlin.math.max(1, kotlin.math.ceil(duration.toFloatOrNull() ?: 2f).toInt())

            val out = StringBuilder(playlist.length + 128)
            var promoted = 0
            var dropped = 0
            for (line in lines) {
                when {
                    line.startsWith(PREFETCH_TAG) -> {
                        val url = line.substringAfter(PREFETCH_TAG).trim()
                        when {
                            url.isEmpty() -> {}
                            promoted < maxPromoted -> {
                                out.append(EXTINF_TAG).append(duration).append(",\n")
                                out.append(url).append('\n')
                                promoted++
                            }
                            else -> dropped++
                        }
                    }
                    line.startsWith(TARGETDURATION_TAG) ->
                        out.append(TARGETDURATION_TAG).append(reloadTarget).append('\n')
                    else -> out.append(line).append('\n')
                }
            }
            Log.d(TAG, "promoted=$promoted dropped=$dropped dur=${duration}s targetDuration=$reloadTarget")
            return out.toString()
        }
    }
}
