package com.example.omeplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.example.omeplayer.player.ExoPlayerManager
import com.example.omeplayer.webrtc.WebRTCClient
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Main Activity - OMEPlayer
 * Supports WebRTC, HLS, and DASH streaming from OvenMediaEngine
 */
@UnstableApi
class MainActivity : AppCompatActivity() {

    private val tag = "MainActivity"

    // UI Components
    private lateinit var videoContainer: FrameLayout
    private lateinit var exoPlayerView: PlayerView
    private lateinit var webrtcSurfaceView: SurfaceViewRenderer
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var urlInput: TextInputEditText
    private lateinit var playButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var streamTypeRadioGroup: RadioGroup

    // Players
    private var webRTCClient: WebRTCClient? = null
    private var exoPlayerManager: ExoPlayerManager? = null
    private var eglBase: EglBase? = null

    // State
    private enum class StreamType { WEBRTC, HLS, DASH }
    private var currentStreamType = StreamType.WEBRTC
    private var isPlaying = false

    // Permissions
    private val requiredPermissions = arrayOf(
        Manifest.permission.INTERNET,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.MODIFY_AUDIO_SETTINGS
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initializePlayers()
        } else {
            Toast.makeText(this, R.string.error_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupListeners()
        checkPermissionsAndInitialize()
    }

    private fun initializeViews() {
        videoContainer = findViewById(R.id.videoContainer)
        exoPlayerView = findViewById(R.id.exoPlayerView)
        webrtcSurfaceView = findViewById(R.id.webrtcSurfaceView)
        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        urlInput = findViewById(R.id.urlInput)
        playButton = findViewById(R.id.playButton)
        stopButton = findViewById(R.id.stopButton)
        streamTypeRadioGroup = findViewById(R.id.streamTypeRadioGroup)
    }

    private fun setupListeners() {
        playButton.setOnClickListener { onPlayClicked() }
        stopButton.setOnClickListener { onStopClicked() }

        streamTypeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioWebRTC -> {
                    currentStreamType = StreamType.WEBRTC
                    urlInput.setText(R.string.default_webrtc_url)
                }
                R.id.radioHLS -> {
                    currentStreamType = StreamType.HLS
                    urlInput.setText(R.string.default_hls_url)
                }
                R.id.radioDASH -> {
                    currentStreamType = StreamType.DASH
                    urlInput.setText(R.string.default_dash_url)
                }
            }
        }
    }

    private fun checkPermissionsAndInitialize() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            initializePlayers()
        } else {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun initializePlayers() {
        try {
            // Initialize EglBase for WebRTC
            eglBase = EglBase.create()

            // Initialize WebRTC Client
            webRTCClient = WebRTCClient(
                context = this,
                eglBase = eglBase!!,
                listener = webRTCListener
            )

            // Initialize WebRTC Surface
            webrtcSurfaceView.init(eglBase!!.eglBaseContext, null)
            webrtcSurfaceView.setEnableHardwareScaler(true)
            webrtcSurfaceView.setMirror(false)

            // Initialize ExoPlayer Manager
            exoPlayerManager = ExoPlayerManager(
                context = this,
                listener = exoPlayerListener
            )
            exoPlayerManager?.initialize(exoPlayerView)

            updateStatus(getString(R.string.status_ready))
            Log.d(tag, "Players initialized")

        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize players", e)
            Toast.makeText(this, "Initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun onPlayClicked() {
        val url = urlInput.text?.toString()?.trim()

        if (url.isNullOrEmpty()) {
            Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }

        // Stop current playback
        stopPlayback()

        // Start new playback
        when (currentStreamType) {
            StreamType.WEBRTC -> playWebRTC(url)
            StreamType.HLS -> playHLS(url)
            StreamType.DASH -> playDASH(url)
        }

        isPlaying = true
        playButton.isEnabled = false
        stopButton.isEnabled = true
    }

    private fun onStopClicked() {
        stopPlayback()
        updateStatus(getString(R.string.status_stopped))
        statsText.text = getString(R.string.stats_idle)

        isPlaying = false
        playButton.isEnabled = true
        stopButton.isEnabled = false
    }

    private fun playWebRTC(url: String) {
        Log.d(tag, "Playing WebRTC: $url")
        showWebRTCView()
        webRTCClient?.connect(url)
    }

    private fun playHLS(url: String) {
        Log.d(tag, "Playing HLS: $url")
        showExoPlayerView()
        exoPlayerManager?.playHLS(url)
    }

    private fun playDASH(url: String) {
        Log.d(tag, "Playing DASH: $url")
        showExoPlayerView()
        exoPlayerManager?.playDASH(url)
    }

    private fun stopPlayback() {
        webRTCClient?.disconnect()
        exoPlayerManager?.stop()
        hideAllViews()
    }

    private fun showWebRTCView() {
        exoPlayerView.visibility = View.GONE
        webrtcSurfaceView.visibility = View.VISIBLE
    }

    private fun showExoPlayerView() {
        webrtcSurfaceView.visibility = View.GONE
        exoPlayerView.visibility = View.VISIBLE
    }

    private fun hideAllViews() {
        exoPlayerView.visibility = View.GONE
        webrtcSurfaceView.visibility = View.GONE
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            statusText.text = status
        }
    }

    private fun updateStats(stats: String) {
        runOnUiThread {
            statsText.text = stats
        }
    }

    // WebRTC Listener
    private val webRTCListener = object : WebRTCClient.WebRTCListener {
        override fun onConnecting() {
            updateStatus(getString(R.string.status_connecting))
        }

        override fun onConnected() {
            updateStatus(getString(R.string.status_playing))
        }

        override fun onDisconnected() {
            updateStatus(getString(R.string.status_stopped))
        }

        override fun onError(error: String) {
            updateStatus(getString(R.string.status_error, error))
            runOnUiThread {
                Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
            }
        }

        override fun onRemoteStream(videoTrack: VideoTrack?) {
            videoTrack?.addSink(webrtcSurfaceView)
        }

        override fun onStatsUpdate(stats: String) {
            updateStats(stats)
        }
    }

    // ExoPlayer Listener
    private val exoPlayerListener = object : ExoPlayerManager.ExoPlayerListener {
        override fun onPlayerReady() {
            updateStatus(getString(R.string.status_playing))
        }

        override fun onPlayerError(error: String) {
            updateStatus(getString(R.string.status_error, error))
            runOnUiThread {
                Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
            }
        }

        override fun onBuffering() {
            updateStatus(getString(R.string.status_connecting))
        }

        override fun onPlaying() {
            updateStatus(getString(R.string.status_playing))
        }

        override fun onEnded() {
            updateStatus("Playback ended")
        }

        override fun onStatsUpdate(stats: String) {
            updateStats(stats)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        webRTCClient?.release()
        exoPlayerManager?.release()
        webrtcSurfaceView.release()
        eglBase?.release()

        webRTCClient = null
        exoPlayerManager = null
        eglBase = null
    }

    override fun onPause() {
        super.onPause()
        exoPlayerManager?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (isPlaying && currentStreamType != StreamType.WEBRTC) {
            exoPlayerManager?.resume()
        }
    }
}
