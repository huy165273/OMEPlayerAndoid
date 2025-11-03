# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OMEPlayer is an Android streaming player for OvenMediaEngine (OME) with support for:
- **WebRTC** - Low latency real-time streaming via WebSocket signaling
- **HLS** - HTTP Live Streaming with ExoPlayer
- **DASH** - Dynamic Adaptive Streaming over HTTP with ExoPlayer

**Tech Stack:** Kotlin, Android SDK 24-34, ExoPlayer (Media3), WebRTC SDK, OkHttp, Coroutines

## Build Commands

```bash
# Windows
gradlew assembleDebug          # Build debug APK
gradlew assembleRelease        # Build release APK
gradlew clean                  # Clean build artifacts

# Install to connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Build artifacts:** `app/build/outputs/apk/`

## Testing Commands

```bash
# Run on emulator/device
gradlew installDebug           # Install debug build
adb devices                    # List connected devices

# View logs
adb logcat | grep OMEPlayer             # All app logs
adb logcat | grep WebRTCClient          # WebRTC logs
adb logcat | grep ExoPlayerManager      # ExoPlayer logs
adb logcat -c                           # Clear logs
```

**Test URLs for Emulator:**
- WebRTC: `ws://10.0.2.2:4333/app/stream` (10.0.2.2 = host localhost)
- HLS: `http://10.0.2.2:8080/app/stream/llhls.m3u8`
- DASH: `http://10.0.2.2:8080/app/stream/manifest.mpd`

## Architecture

### Three-Layer Design

```
MainActivity (UI Layer)
    ├── Manages UI, permissions, lifecycle
    ├── Coordinates between WebRTC and ExoPlayer
    └── Displays real-time stats

    ↓ delegates to ↓

WebRTCClient                    ExoPlayerManager
(WebRTC Layer)                  (Media Layer)
├── PeerConnection              ├── ExoPlayer instance
├── WebSocket signaling         ├── HLS/DASH MediaSource
├── ICE candidate handling      ├── Adaptive bitrate
├── Auto-reconnect (5 attempts) └── Stats collection
└── Stats collection
```

### Key Files

- **`MainActivity.kt`** (app/src/main/java/com/example/omeplayer/MainActivity.kt:1)
  - Entry point, manages UI and player lifecycle
  - Handles permissions: CAMERA, RECORD_AUDIO, INTERNET
  - Switches between WebRTC/HLS/DASH modes
  - Coordinates between `WebRTCClient` and `ExoPlayerManager`

- **`WebRTCClient.kt`** (app/src/main/java/com/example/omeplayer/webrtc/WebRTCClient.kt:1)
  - WebRTC PeerConnection management with OME-specific signaling
  - **Important:** OME uses server-offer pattern (client requests offer)
  - Signaling protocol: `{"command": "request_offer"}` → server sends offer → client answers
  - Auto-reconnect with exponential backoff (2s, 4s, 6s, 8s, 10s)
  - ICE candidates bundled in offer message for OME

- **`ExoPlayerManager.kt`** (app/src/main/java/com/example/omeplayer/player/ExoPlayerManager.kt:1)
  - ExoPlayer wrapper for HLS/DASH playback
  - Custom HTTP data source with 10s timeouts
  - Stats collection every 2 seconds (codec, bitrate, resolution)

### Critical WebRTC Signaling Flow (OME-Specific)

OME uses a **server-offer** pattern, not standard client-offer:

```kotlin
// 1. Client requests offer
{"command": "request_offer"}

// 2. Server sends offer with bundled ICE candidates
{
  "command": "offer",
  "sdp": {"sdp": "v=0...", "type": "offer"},
  "candidates": [{"candidate": "...", "sdpMid": "0", "sdpMLineIndex": 0}]
}

// 3. Client sets remote description + adds bundled ICE candidates
peerConnection.setRemoteDescription(offer)
// Add candidates from offer.candidates array

// 4. Client creates and sends answer
{"command": "answer", "sdp": {"sdp": "...", "type": "answer"}}

// 5. Additional ICE candidates exchanged
{"command": "candidate", "sdpMid": "0", "sdpMLineIndex": 0, "candidate": "..."}
```

**Key implementation:** WebRTCClient.kt:216-258 handles OME's bundled ICE candidates in offer message.

### Network Security

- **Cleartext traffic enabled** (`android:usesCleartextTraffic="true"`) for development
- Network security config: `app/src/main/res/xml/network_security_config.xml`
- For production: Use WSS/HTTPS and configure proper SSL/TLS

### Resource Management

Always release in `onDestroy()`:
```kotlin
webRTCClient?.release()      // Closes PeerConnection, WebSocket
exoPlayerManager?.release()  // Releases ExoPlayer
webrtcSurfaceView.release()  // Releases SurfaceViewRenderer
eglBase?.release()           // Releases EGL context
```

## Common Development Tasks

### Adding New Stream Protocol
1. Create new manager class in `app/src/main/java/com/example/omeplayer/player/`
2. Add new `StreamType` enum value in MainActivity.kt:48
3. Add radio button in `activity_main.xml`
4. Add handling in `onPlayClicked()` method (MainActivity.kt:159)

### Modifying WebRTC Signaling
- Edit `handleSignalingMessage()` in WebRTCClient.kt:209
- Update offer/answer format in `sendOffer()` (WebRTCClient.kt:200) and `createAnswer()` (WebRTCClient.kt:278)
- Adjust ICE server configuration in `createPeerConnection()` (WebRTCClient.kt:130)

### Changing Reconnect Behavior
- Modify `attemptReconnect()` in WebRTCClient.kt:360
- Adjust `maxReconnectAttempts` (default: 5) at WebRTCClient.kt:33
- Change exponential backoff: `delay(2000L * reconnectAttempts)`

### Adjusting ExoPlayer Buffering
Edit `ExoPlayerManager.kt`, add LoadControl:
```kotlin
val loadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(
        minBufferMs = 2000,
        maxBufferMs = 10000,
        bufferForPlaybackMs = 1000,
        bufferForPlaybackAfterRebufferMs = 2000
    )
    .build()

player = ExoPlayer.Builder(context)
    .setLoadControl(loadControl)
    .build()
```

## Important Considerations

### WebRTC with OME
- OME uses **server-offer pattern** - client must request offer, not create one
- ICE candidates are bundled in the offer message (`candidates` array)
- Must handle both bundled candidates and subsequent separate candidate messages
- STUN servers configured: `stun.l.google.com:19302`, `stun1.l.google.com:19302`

### Permissions
Runtime permissions required (Android 6+):
- `CAMERA`, `RECORD_AUDIO` for WebRTC
- `INTERNET` for all streaming
- `MODIFY_AUDIO_SETTINGS` for audio routing

Check implementation in MainActivity.kt:53-69

### Hardware Acceleration
- **EglBase** (WebRTCClient.kt:18) provides GPU-accelerated video decoding
- Must be initialized before WebRTC components
- Must be released in reverse order: WebRTC → ExoPlayer → EglBase

### Threading
- WebRTC callbacks run on signaling/network threads
- ExoPlayer callbacks run on main thread
- Use `runOnUiThread {}` for UI updates from WebRTC listeners (MainActivity.kt:232-235)

## Dependencies

Key libraries in `app/build.gradle`:
```gradle
// ExoPlayer (Media3)
androidx.media3:media3-exoplayer:1.2.1
androidx.media3:media3-exoplayer-hls:1.2.1
androidx.media3:media3-exoplayer-dash:1.2.1

// WebRTC
io.github.webrtc-sdk:android:104.5112.09

// Networking
com.squareup.okhttp3:okhttp:4.12.0  // WebSocket

// Coroutines
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3
```

## Troubleshooting Development Issues

### "ICE connection failed"
- Check STUN server accessibility
- Verify OME server is running: `docker ps | grep ome`
- Check WebRTC signaling logs: `adb logcat | grep WebRTCClient`
- Test WebSocket connection: `telnet <server-ip> 4333`

### "Player error" with ExoPlayer
- Verify URL format and accessibility
- Check network security config allows cleartext for dev
- Test URL in browser or VLC first
- Check logs: `adb logcat | grep ExoPlayerManager`

### Build failures
- Run `gradlew clean` first
- Invalidate caches: File → Invalidate Caches / Restart in Android Studio
- Check JDK version: Should be JDK 17
- Verify Kotlin version: 1.9.20

### EglBase initialization errors
- Ensure EglBase created on main thread
- Check OpenGL ES support on device/emulator
- Use hardware-accelerated emulator (GLES 2.0+)

## Project Structure

```
app/src/main/
├── java/com/example/omeplayer/
│   ├── MainActivity.kt              # UI controller
│   ├── player/
│   │   └── ExoPlayerManager.kt      # HLS/DASH player
│   └── webrtc/
│       └── WebRTCClient.kt          # WebRTC signaling & peer connection
├── res/
│   ├── layout/
│   │   └── activity_main.xml        # UI layout
│   ├── values/
│   │   └── strings.xml              # Default URLs, strings
│   └── xml/
│       └── network_security_config.xml  # Network security settings
└── AndroidManifest.xml              # Permissions, app config
```

## Additional Documentation

- **README.md** - Complete Vietnamese documentation with setup guides
- **ARCHITECTURE.md** - Detailed architecture, data flows, state machines
- **QUICKSTART.md** - 5-minute quick start guide with test streams
