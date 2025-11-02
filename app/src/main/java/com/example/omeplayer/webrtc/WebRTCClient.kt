package com.example.omeplayer.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.PeerConnection.*
import java.util.concurrent.TimeUnit

/**
 * WebRTC Client for OME (OvenMediaEngine)
 * Handles WebRTC signaling and peer connection management
 */
class WebRTCClient(
    private val context: Context,
    private val eglBase: EglBase,
    private val listener: WebRTCListener
) {
    private val tag = "WebRTCClient"

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var streamUrl: String = ""
    private var isReconnecting = false
    private val maxReconnectAttempts = 5
    private var reconnectAttempts = 0

    interface WebRTCListener {
        fun onConnecting()
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
        fun onRemoteStream(videoTrack: VideoTrack?)
        fun onStatsUpdate(stats: String)
    }

    init {
        initializePeerConnectionFactory()
    }

    private fun initializePeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            .createInitializationOptions()

        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            true,
            true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()
    }

    /**
     * Connect to OME WebRTC stream
     * @param url WebSocket URL (e.g., ws://host:4333/app/stream)
     */
    fun connect(url: String) {
        streamUrl = url
        reconnectAttempts = 0
        isReconnecting = false
        listener.onConnecting()

        Log.d(tag, "Connecting to: $url")
        startWebSocketConnection(url)
    }

    private fun startWebSocketConnection(url: String) {
        try {
            // Create WebSocket signaling connection
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(tag, "WebSocket opened")
                    createPeerConnection()
                    sendOffer()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(tag, "WebSocket message: $text")
                    handleSignalingMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(tag, "WebSocket failure", t)
                    listener.onError("Connection failed: ${t.message}")
                    attemptReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(tag, "WebSocket closed: $code - $reason")
                    listener.onDisconnected()
                }
            })

        } catch (e: Exception) {
            Log.e(tag, "Failed to connect", e)
            listener.onError("Failed to connect: ${e.message}")
        }
    }

    private fun createPeerConnection() {
        val rtcConfig = RTCConfiguration(listOf()).apply {
            // STUN servers for NAT traversal
            iceServers = listOf(
                IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )
            sdpSemantics = SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = RtcpMuxPolicy.REQUIRE
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(tag, "ICE Candidate: ${candidate.sdp}")
                    sendIceCandidate(candidate)
                }

                override fun onIceConnectionChange(state: IceConnectionState) {
                    Log.d(tag, "ICE Connection State: $state")
                    when (state) {
                        IceConnectionState.CONNECTED, IceConnectionState.COMPLETED -> {
                            listener.onConnected()
                            reconnectAttempts = 0
                            startStatsCollection()
                        }
                        IceConnectionState.DISCONNECTED -> {
                            listener.onDisconnected()
                            attemptReconnect()
                        }
                        IceConnectionState.FAILED -> {
                            listener.onError("ICE connection failed")
                            attemptReconnect()
                        }
                        else -> {}
                    }
                }

                override fun onAddStream(stream: MediaStream) {
                    Log.d(tag, "Remote stream added")
                    val videoTrack = stream.videoTracks?.firstOrNull()
                    listener.onRemoteStream(videoTrack)
                }

                override fun onRemoveStream(stream: MediaStream) {
                    Log.d(tag, "Remote stream removed")
                }

                override fun onSignalingChange(state: SignalingState) {
                    Log.d(tag, "Signaling State: $state")
                }

                override fun onIceGatheringChange(state: IceGatheringState) {
                    Log.d(tag, "ICE Gathering State: $state")
                }

                override fun onDataChannel(channel: DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    Log.d(tag, "ICE Connection Receiving Change: $receiving")
                }
            }
        )
    }

    private fun sendOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        // Send offer to OME via WebSocket
                        val offerJson = JSONObject().apply {
                            put("command", "request_offer")
                            put("type", "offer")
                            put("sdp", sdp.description)
                        }
                        webSocket?.send(offerJson.toString())
                        Log.d(tag, "Offer sent")
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(tag, "Set local description failed: $error")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(tag, "Create offer failed: $error")
                listener.onError("Failed to create offer: $error")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private fun handleSignalingMessage(message: String) {
        try {
            val json = JSONObject(message)
            val command = json.optString("command")

            when (command) {
                "answer" -> {
                    val sdp = json.getString("sdp")
                    val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                    peerConnection?.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(tag, "Remote description set successfully")
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(tag, "Set remote description failed: $error")
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, answer)
                }
                "candidate" -> {
                    val sdpMid = json.getString("sdpMid")
                    val sdpMLineIndex = json.getInt("sdpMLineIndex")
                    val sdp = json.getString("candidate")
                    val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
                    peerConnection?.addIceCandidate(candidate)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to handle signaling message", e)
        }
    }

    private fun sendIceCandidate(candidate: IceCandidate) {
        try {
            val json = JSONObject().apply {
                put("command", "candidate")
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("candidate", candidate.sdp)
            }
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(tag, "Failed to send ICE candidate", e)
        }
    }

    private fun startStatsCollection() {
        scope.launch {
            while (isActive) {
                delay(2000) // Update every 2 seconds
                peerConnection?.getStats { report ->
                    val stats = parseStats(report)
                    listener.onStatsUpdate(stats)
                }
            }
        }
    }

    private fun parseStats(report: RTCStatsReport): String {
        val statsBuilder = StringBuilder()

        report.statsMap.values.forEach { stats ->
            when (stats.type) {
                "inbound-rtp" -> {
                    val bytesReceived = stats.members["bytesReceived"]
                    val packetsReceived = stats.members["packetsReceived"]
                    statsBuilder.append("Received: $bytesReceived bytes, $packetsReceived packets\n")
                }
            }
        }

        return statsBuilder.toString().ifEmpty { "No stats available" }
    }

    private fun attemptReconnect() {
        if (isReconnecting || reconnectAttempts >= maxReconnectAttempts) {
            return
        }

        isReconnecting = true
        reconnectAttempts++

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(2000L * reconnectAttempts) // Exponential backoff
            Log.d(tag, "Reconnecting... Attempt $reconnectAttempts")
            disconnect()
            connect(streamUrl)
            isReconnecting = false
        }
    }

    fun disconnect() {
        Log.d(tag, "Disconnecting")

        reconnectJob?.cancel()
        webSocket?.close(1000, "Normal closure")
        peerConnection?.close()

        webSocket = null
        peerConnection = null

        listener.onDisconnected()
    }

    fun release() {
        disconnect()
        scope.cancel()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase.release()
    }
}
