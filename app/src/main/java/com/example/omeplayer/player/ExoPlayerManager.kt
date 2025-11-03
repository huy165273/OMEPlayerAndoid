package com.example.omeplayer.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*

/**
 * ExoPlayer Manager for HLS and DASH streaming
 */
@UnstableApi
class ExoPlayerManager(
    private val context: Context,
    private val listener: ExoPlayerListener
) {
    private val tag = "ExoPlayerManager"

    private var player: ExoPlayer? = null
    private var statsJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    interface ExoPlayerListener {
        fun onPlayerReady()
        fun onPlayerError(error: String)
        fun onBuffering()
        fun onPlaying()
        fun onEnded()
        fun onStatsUpdate(stats: String)
    }

    /**
     * Initialize ExoPlayer
     */
    fun initialize(playerView: PlayerView) {
        release()

        player = ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build()
            .apply {
                addListener(playerListener)
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
            }

        playerView.player = player
        Log.d(tag, "ExoPlayer initialized")
    }

    /**
     * Play HLS stream
     * @param url HLS URL (e.g., http://host:8080/app/stream/llhls.m3u8)
     */
    fun playHLS(url: String) {
        try {
            val mediaSource = createHlsMediaSource(url)
            playMediaSource(mediaSource, url)
        } catch (e: Exception) {
            Log.e(tag, "Failed to play HLS", e)
            listener.onPlayerError("Failed to play HLS: ${e.message}")
        }
    }

    /**
     * Play DASH stream
     * @param url DASH URL (e.g., http://host:8080/app/stream/manifest.mpd)
     */
    fun playDASH(url: String) {
        try {
            val mediaSource = createDashMediaSource(url)
            playMediaSource(mediaSource, url)
        } catch (e: Exception) {
            Log.e(tag, "Failed to play DASH", e)
            listener.onPlayerError("Failed to play DASH: ${e.message}")
        }
    }

    private fun playMediaSource(mediaSource: MediaSource, url: String) {
        player?.apply {
            setMediaSource(mediaSource)
            prepare()
            Log.d(tag, "Playing: $url")
        }
        startStatsCollection()
    }

    private fun createHlsMediaSource(url: String): MediaSource {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(10000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("OMEPlayer/1.0 (Android)")

        return HlsMediaSource.Factory(dataSourceFactory)
            .setAllowChunklessPreparation(true)
            .createMediaSource(MediaItem.fromUri(url))
    }

    private fun createDashMediaSource(url: String): MediaSource {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(10000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("OMEPlayer/1.0 (Android)")

        return DashMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(url))
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_IDLE -> {
                    Log.d(tag, "Player idle")
                }
                Player.STATE_BUFFERING -> {
                    Log.d(tag, "Player buffering")
                    listener.onBuffering()
                }
                Player.STATE_READY -> {
                    Log.d(tag, "Player ready")
                    listener.onPlayerReady()
                }
                Player.STATE_ENDED -> {
                    Log.d(tag, "Playback ended")
                    listener.onEnded()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                listener.onPlaying()
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(tag, "Player error: ${error.message}", error)
            listener.onPlayerError("Playback error: ${error.message}")
        }
    }

    private fun startStatsCollection() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(2000) // Update every 2 seconds
                player?.let { player ->
                    val stats = buildStatsString(player)
                    listener.onStatsUpdate(stats)
                }
            }
        }
    }

    private fun buildStatsString(player: ExoPlayer): String {
        val format = player.videoFormat
        val videoSize = player.videoSize

        return if (format != null) {
            val codec = format.sampleMimeType ?: "Unknown"
            val bitrate = if (format.bitrate > 0) {
                "${format.bitrate / 1000} kbps"
            } else {
                "N/A"
            }
            val resolution = "${videoSize.width}x${videoSize.height}"

            "Codec: $codec | Bitrate: $bitrate | Resolution: $resolution"
        } else {
            "Stats: Loading..."
        }
    }

    fun stop() {
        statsJob?.cancel()
        player?.stop()
        player?.clearMediaItems()
        Log.d(tag, "Player stopped")
    }

    fun release() {
        statsJob?.cancel()
        player?.removeListener(playerListener)
        player?.release()
        player = null
        Log.d(tag, "Player released")
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun isPlaying(): Boolean {
        return player?.isPlaying == true
    }
}
