package com.example.omeplayer.webrtc

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    private var currentOfferId: Int = 0
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var pendingAnswer: SessionDescription? = null
    private var gatheringTimeoutJob: Job? = null

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
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(tag, "ICE Candidate collected: ${candidate.sdp}")
                    pendingCandidates.add(candidate)

                    // Timeout fallback: nếu sau 1.5s vẫn chưa COMPLETE thì gửi luôn
                    gatheringTimeoutJob?.cancel()
                    gatheringTimeoutJob = scope.launch {
                        delay(1500)
                        if (pendingAnswer != null && pendingCandidates.isNotEmpty()) {
                            Log.d(tag, "⏱️ Timeout: Sending answer with ${pendingCandidates.size} candidates (no COMPLETE event)")
                            pendingAnswer?.let { sendAnswerWithCandidates(it) }
                            pendingAnswer = null
                        }
                    }
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
                    if (state == IceGatheringState.COMPLETE) {
                        gatheringTimeoutJob?.cancel()
                        pendingAnswer?.let { answer ->
                            Log.d(tag, "✓ COMPLETE: Sending answer with ${pendingCandidates.size} candidates")
                            sendAnswerWithCandidates(answer)
                            pendingAnswer = null
                        }
                    }
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

        // Add transceivers for receive-only (UNIFIED_PLAN requires this)
        // This tells the peer connection we want to receive audio and video
        peerConnection?.apply {
            addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            Log.d(tag, "Added audio and video transceivers (recv-only)")
        }
    }

    private fun sendOffer() {
        // OME Protocol: Client requests offer from server (server creates offer)
        val requestJson = JSONObject().apply {
            put("command", "request_offer")
        }
        webSocket?.send(requestJson.toString())
        Log.d(tag, "Requested offer from server")
    }

    private fun handleSignalingMessage(message: String) {
        try {
            Log.d(tag, "Received message: $message")
            val json = JSONObject(message)
            val command = json.optString("command")

            when (command) {
                "offer" -> {
                    // OME format: {"command":"offer", "sdp": {"sdp":"v=0...", "type":"offer"}}
                    val sdpObject = json.optJSONObject("sdp")
                    val sdpString = sdpObject?.optString("sdp")

                    Log.d(tag, "FULL OFFER MESSAGE: $message")

                    val offerId = json.optInt("id", -1)
                    Log.d(tag, "Parsed offer ID: $offerId")

                    if (offerId == -1) {
                        Log.e(tag, "Offer ID missing or invalid in message")
                        // Thử parse as String
                        val offerIdStr = json.optString("id", "")
                        Log.e(tag, "Trying as String: '$offerIdStr'")
                        listener.onError("Invalid offer: missing or invalid ID")
                        return
                    }

                    if (sdpString.isNullOrEmpty()) {
                        Log.e(tag, "SDP is null or empty in offer")
                        listener.onError("Invalid offer: SDP is missing")
                        return
                    }

                    val offer = SessionDescription(SessionDescription.Type.OFFER, sdpString)
                    Log.d(tag, "Parsed SDP offer (${sdpString.length} chars)")

                    peerConnection?.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(tag, "✓ Remote offer set successfully")

                            // Add ICE candidates from offer message
                            val candidates = json.optJSONArray("candidates")
                            if (candidates != null) {
                                for (i in 0 until candidates.length()) {
                                    val candidateObj = candidates.getJSONObject(i)
                                    val candidateStr = candidateObj.optString("candidate")
                                    val sdpMLineIndex = candidateObj.optInt("sdpMLineIndex", 0)
                                    val sdpMid = candidateObj.optString("sdpMid", "0")

                                    if (candidateStr.isNotEmpty()) {
                                        val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateStr)
                                        peerConnection?.addIceCandidate(candidate)
                                        Log.d(tag, "Added ICE candidate from offer")
                                    }
                                }
                            }

                            currentOfferId = offerId
                            createAnswer(offerId)
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(tag, "Set remote offer failed: $error")
                            listener.onError("Failed to set remote offer: $error")
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, offer)
                }
                "candidate" -> {
                    val sdpMid = json.optString("sdpMid")
                    val sdpMLineIndex = json.optInt("sdpMLineIndex", 0)
                    val candidateStr = json.optString("candidate")

                    if (candidateStr.isNotEmpty()) {
                        val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateStr)
                        peerConnection?.addIceCandidate(candidate)
                        Log.d(tag, "ICE candidate added")
                    }
                }
                "notification" -> {
                    Log.d(tag, "Notification: ${json.optString("type")}")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to handle signaling message: ${e.message}", e)
            listener.onError("Signaling error: ${e.message}")
        }
    }

    private fun createAnswer(offerId: Int) {
        currentOfferId = offerId
        val constraints = MediaConstraints()

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(tag, "✓ Answer created (${sdp.description.length} chars)")

                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(tag, "✓ Local answer set successfully, waiting for ICE candidates...")
                        pendingAnswer = sdp
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(tag, "Set local description failed: $error")
                        listener.onError("Failed to set local description: $error")
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(tag, "Create answer failed: $error")
                listener.onError("Failed to create answer: $error")
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }


    private fun sendAnswerWithCandidates(answer: SessionDescription) {
        try {
            val candidatesArray = org.json.JSONArray()
            pendingCandidates.forEach { candidate ->
                candidatesArray.put(JSONObject().apply {
                    put("candidate", candidate.sdp)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("sdpMid", candidate.sdpMid)
                })
            }

            val answerJson = JSONObject().apply {
                put("command", "answer")
                put("id", currentOfferId)
                put("sdp", JSONObject().apply {
                    put("sdp", answer.description)
                    put("type", "answer")
                })
                put("candidates", candidatesArray)
            }

            webSocket?.send(answerJson.toString())
            Log.d(tag, "✓ Answer sent with ${pendingCandidates.size} candidates")
            Log.d(tag, "Answer JSON: $answerJson")

            pendingCandidates.clear()
        } catch (e: Exception) {
            Log.e(tag, "Failed to send answer with candidates", e)
            listener.onError("Failed to send answer: ${e.message}")
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
        gatheringTimeoutJob?.cancel()
        webSocket?.close(1000, "Normal closure")
        peerConnection?.close()

        webSocket = null
        peerConnection = null
        pendingCandidates.clear()
        pendingAnswer = null

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
