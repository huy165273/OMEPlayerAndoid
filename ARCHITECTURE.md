# OMEPlayer - Kiến Trúc Chi Tiết

Tài liệu này giải thích chi tiết về kiến trúc, luồng dữ liệu và cơ chế hoạt động của OMEPlayer.

---

## 📊 Tổng Quan Kiến Trúc

```
┌─────────────────────────────────────────────────────────────┐
│                      OMEPlayer Application                   │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌───────────────────────────────────────────────────────┐  │
│  │              MainActivity (UI Layer)                   │  │
│  │  - User Input Handling                                │  │
│  │  - Stream Type Selection (WebRTC/HLS/DASH)            │  │
│  │  - Lifecycle Management                               │  │
│  │  - Permission Handling                                │  │
│  └───────────────────────────────────────────────────────┘  │
│           │                                    │              │
│           │                                    │              │
│           ▼                                    ▼              │
│  ┌──────────────────────┐         ┌──────────────────────┐  │
│  │   WebRTCClient       │         │  ExoPlayerManager    │  │
│  │  (WebRTC Layer)      │         │  (Media Layer)       │  │
│  ├──────────────────────┤         ├──────────────────────┤  │
│  │ - PeerConnection     │         │ - ExoPlayer Instance │  │
│  │ - WebSocket Client   │         │ - HLS MediaSource    │  │
│  │ - ICE Handling       │         │ - DASH MediaSource   │  │
│  │ - SDP Exchange       │         │ - Adaptive Bitrate   │  │
│  │ - Auto-Reconnect     │         │ - Stats Collection   │  │
│  └──────────────────────┘         └──────────────────────┘  │
│           │                                    │              │
│           │                                    │              │
└───────────┼────────────────────────────────────┼──────────────┘
            │                                    │
            ▼                                    ▼
   ┌─────────────────┐                 ┌─────────────────┐
   │  OME Edge       │                 │  OME Edge       │
   │  (WebRTC)       │                 │  (HTTP)         │
   │  Port: 4333     │                 │  Port: 8080     │
   └─────────────────┘                 └─────────────────┘
```

---

## 🏗️ Layer Architecture

### 1. UI Layer (MainActivity.kt)

**Responsibilities:**
- Render UI components
- Handle user interactions
- Manage app lifecycle
- Coordinate between WebRTC and ExoPlayer layers
- Display real-time stats

**Key Components:**
```kotlin
class MainActivity : AppCompatActivity() {
    // UI Components
    - videoContainer: FrameLayout
    - exoPlayerView: PlayerView
    - webrtcSurfaceView: SurfaceViewRenderer
    - urlInput: TextInputEditText
    - playButton, stopButton: MaterialButton
    - streamTypeRadioGroup: RadioGroup

    // Player Managers
    - webRTCClient: WebRTCClient?
    - exoPlayerManager: ExoPlayerManager?
    - eglBase: EglBase?

    // State
    - currentStreamType: StreamType
    - isPlaying: Boolean
}
```

**Lifecycle Events:**
```
onCreate()
    └─> initializeViews()
    └─> setupListeners()
    └─> checkPermissionsAndInitialize()
        └─> initializePlayers()
            ├─> Initialize EglBase
            ├─> Create WebRTCClient
            └─> Create ExoPlayerManager

onDestroy()
    └─> stopPlayback()
    └─> webRTCClient.release()
    └─> exoPlayerManager.release()
    └─> eglBase.release()
```

---

### 2. WebRTC Layer (WebRTCClient.kt)

**Responsibilities:**
- Establish WebSocket connection to OME
- Manage WebRTC PeerConnection
- Handle SDP offer/answer exchange
- Process ICE candidates
- Render remote video stream
- Auto-reconnect on failures

**Architecture:**
```
WebRTCClient
    │
    ├─> PeerConnectionFactory
    │   └─> PeerConnection
    │       ├─> ICE Candidates
    │       ├─> Remote MediaStream
    │       └─> Stats Reports
    │
    ├─> OkHttp WebSocket
    │   ├─> Signaling Messages
    │   ├─> SDP Exchange
    │   └─> Connection Management
    │
    └─> EglBase
        └─> Hardware Video Rendering
```

**Signaling Protocol:**
```json
// Client → Server (Offer)
{
  "command": "request_offer",
  "type": "offer",
  "sdp": "v=0\r\no=- ... [SDP content]"
}

// Server → Client (Answer)
{
  "command": "answer",
  "sdp": "v=0\r\no=- ... [SDP content]"
}

// Bidirectional (ICE Candidates)
{
  "command": "candidate",
  "sdpMid": "0",
  "sdpMLineIndex": 0,
  "candidate": "candidate:... [ICE candidate]"
}
```

**Connection Flow:**
```
1. connect(url)
   │
   ├─> startWebSocketConnection()
   │   └─> WebSocket.onOpen()
   │       └─> createPeerConnection()
   │           └─> sendOffer()
   │
   ├─> Server Response
   │   └─> handleSignalingMessage()
   │       ├─> setRemoteDescription(answer)
   │       └─> addIceCandidate()
   │
   └─> ICE Connection
       └─> onIceConnectionChange(CONNECTED)
           └─> onAddStream(remoteStream)
               └─> videoTrack.addSink(surfaceView)
```

**Auto-Reconnect Logic:**
```kotlin
private fun attemptReconnect() {
    if (reconnectAttempts >= maxReconnectAttempts) return

    reconnectAttempts++
    val delayMs = 2000L * reconnectAttempts  // Exponential backoff

    scope.launch {
        delay(delayMs)
        disconnect()
        connect(streamUrl)
    }
}
```

**State Machine:**
```
[Idle] ──connect()──> [Connecting]
                           │
              WebSocket OK │
                           ▼
                      [Signaling]
                           │
              SDP Exchange │
                           ▼
                      [ICE Gathering]
                           │
              ICE Complete │
                           ▼
                      [Connected] ──onAddStream()──> [Playing]
                           │
              Disconnect   │  Error
                           ▼
                      [Disconnected] ──attemptReconnect()──> [Connecting]
```

---

### 3. Media Layer (ExoPlayerManager.kt)

**Responsibilities:**
- Initialize ExoPlayer instance
- Create MediaSource for HLS/DASH
- Handle buffering and errors
- Collect playback statistics
- Manage player lifecycle

**Architecture:**
```
ExoPlayerManager
    │
    ├─> ExoPlayer
    │   ├─> TrackSelector (Adaptive Bitrate)
    │   ├─> LoadControl (Buffering)
    │   └─> RenderersFactory
    │
    ├─> MediaSource Factory
    │   ├─> HlsMediaSource
    │   │   └─> DefaultHttpDataSource
    │   │
    │   └─> DashMediaSource
    │       └─> DefaultHttpDataSource
    │
    └─> Player.Listener
        ├─> onPlaybackStateChanged()
        ├─> onIsPlayingChanged()
        └─> onPlayerError()
```

**HLS Playback Flow:**
```
1. playHLS(url)
   │
   ├─> createHlsMediaSource(url)
   │   └─> HlsMediaSource.Factory
   │       └─> MediaItem.fromUri(url)
   │
   ├─> player.setMediaSource(mediaSource)
   │
   ├─> player.prepare()
   │   └─> Download manifest (m3u8)
   │       └─> Parse playlist
   │           └─> Download segments (.ts)
   │
   └─> onPlayerReady()
       └─> listener.onPlaying()
```

**DASH Playback Flow:**
```
1. playDASH(url)
   │
   ├─> createDashMediaSource(url)
   │   └─> DashMediaSource.Factory
   │       └─> MediaItem.fromUri(url)
   │
   ├─> player.setMediaSource(mediaSource)
   │
   ├─> player.prepare()
   │   └─> Download manifest (mpd)
   │       └─> Parse MPD
   │           └─> Download init segments + media segments
   │
   └─> onPlayerReady()
       └─> listener.onPlaying()
```

**State Diagram:**
```
[IDLE]
  │
  │ prepare()
  ▼
[BUFFERING] ◄────┐
  │              │ Network slow
  │ Ready        │
  ▼              │
[READY] ─────────┤
  │              │
  │ play()       │
  ▼              │
[PLAYING] ───────┘
  │
  │ stop()
  ▼
[ENDED]
```

---

## 🔄 Data Flow Diagrams

### WebRTC Data Flow

```
┌──────────────┐                                    ┌──────────────┐
│   OMEPlayer  │                                    │   OME Edge   │
└──────────────┘                                    └──────────────┘
      │                                                     │
      │ 1. HTTP Request (WebSocket Upgrade)                │
      │────────────────────────────────────────────────────>│
      │                                                     │
      │ 2. 101 Switching Protocols                         │
      │<────────────────────────────────────────────────────│
      │                                                     │
      │───────────── WebSocket Connected ──────────────────│
      │                                                     │
      │ 3. SDP Offer (JSON)                                │
      │   { "command": "request_offer", "sdp": "..." }     │
      │────────────────────────────────────────────────────>│
      │                                                     │
      │                              4. Process Offer      │
      │                                 Create Answer      │
      │                                                     │
      │ 5. SDP Answer (JSON)                               │
      │   { "command": "answer", "sdp": "..." }            │
      │<────────────────────────────────────────────────────│
      │                                                     │
      │ 6. ICE Candidates Exchange                         │
      │<───────────────────────────────────────────────────>│
      │                                                     │
      │ 7. ICE Connection Established                      │
      │                                                     │
      │ 8. RTP Media Stream (UDP)                          │
      │   ┌─────────────────────────────────────────────┐  │
      │<══│ Video: H.264, Audio: Opus                   │══│
      │   │ Direct P2P or via TURN relay                │  │
      │   └─────────────────────────────────────────────┘  │
      │                                                     │
```

### HLS Data Flow

```
┌──────────────┐                                    ┌──────────────┐
│   OMEPlayer  │                                    │   OME Edge   │
└──────────────┘                                    └──────────────┘
      │                                                     │
      │ 1. GET /app/stream/llhls.m3u8                      │
      │────────────────────────────────────────────────────>│
      │                                                     │
      │ 2. Master Playlist (m3u8)                          │
      │   #EXTM3U                                          │
      │   #EXT-X-VERSION:7                                 │
      │   #EXT-X-STREAM-INF:BANDWIDTH=2500000              │
      │   playlist_1080p.m3u8                              │
      │<────────────────────────────────────────────────────│
      │                                                     │
      │ 3. GET /app/stream/playlist_1080p.m3u8             │
      │────────────────────────────────────────────────────>│
      │                                                     │
      │ 4. Media Playlist                                  │
      │   #EXTM3U                                          │
      │   #EXT-X-TARGETDURATION:2                          │
      │   #EXTINF:2.0                                      │
      │   segment_001.ts                                   │
      │   #EXTINF:2.0                                      │
      │   segment_002.ts                                   │
      │<────────────────────────────────────────────────────│
      │                                                     │
      │ 5. GET /app/stream/segment_001.ts                  │
      │────────────────────────────────────────────────────>│
      │                                                     │
      │ 6. TS Segment (Video + Audio)                      │
      │   ┌─────────────────────────────────────────────┐  │
      │<──│ MPEG-TS: H.264 video + AAC audio           │──│
      │   └─────────────────────────────────────────────┘  │
      │                                                     │
      │ 7. GET /app/stream/segment_002.ts                  │
      │────────────────────────────────────────────────────>│
      │   ... continuous download ...                      │
```

---

## 🔐 Security Considerations

### 1. Network Security

**Cleartext Traffic:**
```xml
<!-- AndroidManifest.xml -->
<application android:usesCleartextTraffic="true">
```
- Cho phép HTTP connections (dev only)
- **Production:** Nên sử dụng HTTPS/WSS

**TLS/SSL:**
```kotlin
// Production: Enforce TLS
val client = OkHttpClient.Builder()
    .sslSocketFactory(sslContext.socketFactory, trustManager)
    .build()
```

### 2. Permissions

**Runtime Permissions:**
```kotlin
private val requiredPermissions = arrayOf(
    Manifest.permission.INTERNET,
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO
)
```

**Permission Flow:**
```
App Start
  │
  ├─> Check Permissions
  │   │
  │   ├─> All Granted ──> Initialize Players
  │   │
  │   └─> Missing ──> Request Permissions
  │                    │
  │                    ├─> User Grants ──> Initialize Players
  │                    │
  │                    └─> User Denies ──> Show Error
```

### 3. ProGuard Rules

**Preserve WebRTC/ExoPlayer classes:**
```proguard
-keep class org.webrtc.** { *; }
-keep class androidx.media3.** { *; }
-dontwarn org.webrtc.**
-dontwarn androidx.media3.**
```

---

## ⚡ Performance Optimization

### 1. Hardware Acceleration

**EglBase:**
```kotlin
// Use hardware accelerated video rendering
val eglBase = EglBase.create()
surfaceView.init(eglBase.eglBaseContext, null)
```

**Benefits:**
- GPU-accelerated video decoding
- Lower CPU usage
- Smoother playback

### 2. Memory Management

**Resource Cleanup:**
```kotlin
override fun onDestroy() {
    webRTCClient?.release()      // Release PeerConnection
    exoPlayerManager?.release()  // Release ExoPlayer
    eglBase?.release()           // Release EGL context
}
```

**Avoid Memory Leaks:**
- Use weak references for callbacks
- Cancel coroutines in onDestroy()
- Remove listeners before releasing

### 3. Network Optimization

**HTTP Data Source Configuration:**
```kotlin
val dataSourceFactory = DefaultHttpDataSource.Factory()
    .setConnectTimeoutMs(10000)     // 10s connect timeout
    .setReadTimeoutMs(10000)        // 10s read timeout
    .setAllowCrossProtocolRedirects(true)
```

**Buffering Strategy:**
```kotlin
val loadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(
        minBufferMs = 2000,
        maxBufferMs = 10000,
        bufferForPlaybackMs = 1000,
        bufferForPlaybackAfterRebufferMs = 2000
    )
    .build()
```

---

## 🧪 Testing Strategy

### 1. Unit Tests

**Test ExoPlayerManager:**
```kotlin
@Test
fun testHlsPlayback() {
    val manager = ExoPlayerManager(context, mockListener)
    manager.playHLS("http://test.com/stream.m3u8")

    verify(mockListener).onPlayerReady()
}
```

### 2. Integration Tests

**Test MainActivity:**
```kotlin
@Test
fun testStreamTypeSelection() {
    onView(withId(R.id.radioHLS)).perform(click())
    onView(withId(R.id.urlInput)).check(matches(withText(containsString("m3u8"))))
}
```

### 3. End-to-End Tests

**Test Real Streaming:**
1. Setup OME server
2. Start stream source
3. Run app on device
4. Verify video playback
5. Check stats accuracy

---

## 📈 Monitoring & Debugging

### 1. Logcat Tags

```
WebRTCClient: WebRTC operations
ExoPlayerManager: ExoPlayer operations
MainActivity: UI and lifecycle
```

### 2. Stats Collection

**WebRTC Stats:**
```kotlin
peerConnection?.getStats { report ->
    report.statsMap.forEach { (id, stats) ->
        when (stats.type) {
            "inbound-rtp" -> /* Video stats */
            "candidate-pair" -> /* Connection stats */
        }
    }
}
```

**ExoPlayer Stats:**
```kotlin
val format = player.videoFormat
val codec = format?.sampleMimeType
val bitrate = format?.bitrate
val resolution = "${player.videoSize.width}x${player.videoSize.height}"
```

---

## 🔧 Configuration

### Default URLs

**Development (Localhost):**
```kotlin
// Emulator
ws://10.0.2.2:4333/app/stream
http://10.0.2.2:8080/app/stream/llhls.m3u8

// Physical Device (Same Network)
ws://192.168.1.100:4333/app/stream
http://192.168.1.100:8080/app/stream/llhls.m3u8
```

**Production:**
```kotlin
ws://stream.example.com:4333/app/stream
https://stream.example.com/app/stream/llhls.m3u8
```

### Build Variants

**Debug vs Release:**
```gradle
android {
    buildTypes {
        debug {
            applicationIdSuffix ".debug"
            debuggable true
            minifyEnabled false
        }
        release {
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt')
        }
    }
}
```

---

## 📦 Dependencies Breakdown

### Core Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| androidx.core:core-ktx | 1.12.0 | Kotlin extensions |
| androidx.appcompat | 1.6.1 | Backward compatibility |
| material | 1.11.0 | Material Design components |

### Media Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| media3-exoplayer | 1.2.1 | Core ExoPlayer |
| media3-exoplayer-hls | 1.2.1 | HLS support |
| media3-exoplayer-dash | 1.2.1 | DASH support |
| media3-ui | 1.2.1 | Player UI components |

### WebRTC Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| webrtc-sdk:android | 104.5112.09 | WebRTC implementation |

### Networking Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| okhttp | 4.12.0 | WebSocket client |
| gson | 2.10.1 | JSON parsing |

---

## 🎯 Future Enhancements

1. **Multi-stream Support:** Play multiple streams simultaneously
2. **DVR/Timeshift:** Pause live stream and rewind
3. **Stream Recording:** Save streams to local storage
4. **Chromecast Support:** Cast to TV
5. **Subtitles/CC:** Support for closed captions
6. **Audio-only Mode:** Background audio playback
7. **Quality Selector UI:** Manual quality selection
8. **Network Adaptive:** Auto-switch protocols based on network

---

**Document Version:** 1.0
**Last Updated:** 2025-01-XX
**Author:** Your Name
