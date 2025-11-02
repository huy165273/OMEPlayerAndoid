# OMEPlayer - Android Streaming Player for OvenMediaEngine

OMEPlayer là ứng dụng Android được thiết kế để phát stream từ OvenMediaEngine (OME) với hỗ trợ đầy đủ các giao thức:
- **WebRTC** - Low latency real-time streaming
- **HLS (HTTP Live Streaming)** - Apple's adaptive streaming protocol
- **DASH (Dynamic Adaptive Streaming over HTTP)** - Industry standard adaptive streaming

---

## 📋 Mục Lục

1. [Tính Năng](#-tính-năng)
2. [Yêu Cầu Hệ Thống](#-yêu-cầu-hệ-thống)
3. [Kiến Trúc Ứng Dụng](#-kiến-trúc-ứng-dụng)
4. [Hướng Dẫn Build](#-hướng-dẫn-build)
5. [Hướng Dẫn Test](#-hướng-dẫn-test)
6. [Cách Sử Dụng](#-cách-sử-dụng)
7. [Cơ Chế Hoạt Động](#-cơ-chế-hoạt-động)
8. [Troubleshooting](#-troubleshooting)
9. [Mở Rộng](#-mở-rộng)

---

## ✨ Tính Năng

### Chức Năng Chính
- ✅ Phát stream WebRTC từ OME Edge với độ trễ thấp
- ✅ Phát stream HLS/LL-HLS (Low Latency HLS)
- ✅ Phát stream DASH/CMAF
- ✅ Tự động reconnect khi mất kết nối
- ✅ Hiển thị thông tin real-time: codec, bitrate, resolution
- ✅ Giao diện đơn giản, dễ sử dụng

### Tính Năng Kỹ Thuật
- 🔧 WebRTC signaling qua WebSocket
- 🔧 ExoPlayer cho adaptive streaming (HLS/DASH)
- 🔧 Auto-reconnect với exponential backoff
- 🔧 Hardware acceleration cho video decoding
- 🔧 Real-time stats monitoring

---

## 📱 Yêu Cầu Hệ Thống

### Android Device
- **Min SDK:** 24 (Android 7.0 Nougat)
- **Target SDK:** 34 (Android 14)
- **Permissions:**
  - INTERNET
  - ACCESS_NETWORK_STATE
  - RECORD_AUDIO (cho WebRTC)
  - CAMERA (cho WebRTC)
  - MODIFY_AUDIO_SETTINGS

### Development Environment
- **Android Studio:** Hedgehog (2023.1.1) hoặc mới hơn
- **JDK:** 17 (OpenJDK hoặc Oracle JDK)
- **Gradle:** 8.2 (tự động tải qua wrapper)
- **Kotlin:** 1.9.20

### OME Server Requirements
- **OvenMediaEngine:** 0.14.0 hoặc mới hơn
- **WebRTC Signaling:** WebSocket endpoint tại port 4333
- **HLS/DASH:** HTTP endpoint tại port 8080

---

## 🏗️ Kiến Trúc Ứng Dụng

```
omeplayer/
│
├── app/
│   ├── src/main/
│   │   ├── java/com/example/omeplayer/
│   │   │   │
│   │   │   ├── MainActivity.kt
│   │   │   │   └─> Activity chính, quản lý UI và lifecycle
│   │   │   │
│   │   │   ├── player/
│   │   │   │   └── ExoPlayerManager.kt
│   │   │   │       └─> Quản lý HLS/DASH playback với ExoPlayer
│   │   │   │
│   │   │   └── webrtc/
│   │   │       └── WebRTCClient.kt
│   │   │           └─> Quản lý WebRTC signaling & peer connection
│   │   │
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   └── activity_main.xml  (UI layout)
│   │   │   ├── values/
│   │   │   │   ├── strings.xml
│   │   │   │   ├── colors.xml
│   │   │   │   └── themes.xml
│   │   │   └── xml/
│   │   │       ├── backup_rules.xml
│   │   │       └── data_extraction_rules.xml
│   │   │
│   │   └── AndroidManifest.xml
│   │
│   ├── build.gradle  (App module config)
│   └── proguard-rules.pro
│
├── build.gradle      (Project-level config)
├── settings.gradle   (Gradle settings)
└── gradle.properties (Gradle properties)
```

### Component Details

#### 1. MainActivity.kt
- Quản lý UI components (EditText, Buttons, VideoViews)
- Xử lý permissions
- Chuyển đổi giữa WebRTC/HLS/DASH modes
- Lifecycle management

#### 2. WebRTCClient.kt
- WebRTC PeerConnection management
- WebSocket signaling với OME
- ICE candidate handling
- Auto-reconnect logic
- Stats collection

#### 3. ExoPlayerManager.kt
- ExoPlayer initialization
- HLS MediaSource creation
- DASH MediaSource creation
- Buffering & error handling
- Stats monitoring

---

## 🔨 Hướng Dẫn Build

### Bước 1: Clone hoặc Copy Project

```bash
# Nếu có Git repo
git clone <repo-url>
cd omeplayer

# Hoặc copy thư mục omeplayer vào workspace
```

### Bước 2: Mở Project trong Android Studio

1. Mở Android Studio
2. File → Open → Chọn thư mục `omeplayer`
3. Chờ Gradle sync hoàn tất (3-5 phút lần đầu)

### Bước 3: Cấu Hình SDK

1. Android Studio sẽ tự động tải Android SDK 34
2. Nếu thiếu, vào: Tools → SDK Manager → Chọn Android 14 (API 34)

### Bước 4: Build APK

#### Option 1: Build từ Android Studio
```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```
APK sẽ nằm tại: `app/build/outputs/apk/debug/app-debug.apk`

#### Option 2: Build từ Command Line
```bash
# Windows
gradlew assembleDebug

# Mac/Linux
./gradlew assembleDebug
```

### Bước 5: Build Release APK (Production)

```bash
# Windows
gradlew assembleRelease

# Mac/Linux
./gradlew assembleRelease
```

**Lưu ý:** Release APK cần signing configuration. Thêm vào `app/build.gradle`:

```gradle
android {
    signingConfigs {
        release {
            storeFile file("keystore.jks")
            storePassword "your-password"
            keyAlias "your-alias"
            keyPassword "your-password"
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
}
```

---

## 🧪 Hướng Dẫn Test

### Test trên Emulator

#### Bước 1: Tạo Android Virtual Device (AVD)
```
Tools → Device Manager → Create Device
- Chọn: Pixel 6 Pro
- System Image: Android 14 (API 34)
- Graphics: Hardware - GLES 2.0
```

#### Bước 2: Chạy App
```
Run → Run 'app' (hoặc Shift+F10)
```

#### Bước 3: Test Connectivity

**Test HLS trên Emulator:**
```
URL: http://10.0.2.2:8080/app/stream/llhls.m3u8
```
- `10.0.2.2` là localhost của máy host từ emulator

**Test WebRTC trên Emulator:**
```
URL: ws://10.0.2.2:4333/app/stream
```

### Test trên Thiết Bị Thật

#### Bước 1: Enable Developer Mode
1. Settings → About Phone
2. Tap "Build Number" 7 lần
3. Back → Developer Options → Enable USB Debugging

#### Bước 2: Connect Device
```bash
# Kiểm tra device
adb devices

# Nếu không thấy, install ADB driver
```

#### Bước 3: Install APK
```bash
# Từ Android Studio
Run → Run 'app'

# Hoặc từ command line
adb install app/build/outputs/apk/debug/app-debug.apk
```

#### Bước 4: Test với Real Server

**Lưu ý:** Thiết bị và OME server phải cùng network hoặc server có public IP.

```
# HLS
http://<server-ip>:8080/app/stream/llhls.m3u8

# WebRTC
ws://<server-ip>:4333/app/stream
```

### Test Logcat

Xem logs real-time:
```bash
# Filter by app
adb logcat | grep OMEPlayer

# Filter by tag
adb logcat | grep WebRTCClient
adb logcat | grep ExoPlayerManager

# Clear logs
adb logcat -c
```

**Các Log Quan Trọng:**
- `WebRTCClient: Connecting to: ws://...` - Đang kết nối WebRTC
- `WebRTCClient: ICE Connection State: CONNECTED` - WebRTC connected
- `ExoPlayerManager: Playing: http://...` - Đang phát HLS/DASH
- `MainActivity: Players initialized` - Khởi tạo thành công

---

## 📖 Cách Sử Dụng

### Giao Diện Người Dùng

```
┌─────────────────────────────┐
│                             │
│     [Video Display Area]    │
│                             │
│    Status: Playing          │
├─────────────────────────────┤
│ Stream Type:                │
│ ◉ WebRTC  ○ HLS  ○ DASH     │
│                             │
│ Stream URL:                 │
│ [ws://host:4333/app/stream] │
│                             │
│  [Play]       [Stop]        │
│                             │
│ Stats: Codec: H264 |        │
│ Bitrate: 2500 kbps |        │
│ Resolution: 1920x1080       │
└─────────────────────────────┘
```

### Workflow

1. **Chọn Stream Type:**
   - WebRTC: Real-time, low latency (<1s)
   - HLS: Adaptive streaming, higher latency (3-10s)
   - DASH: Industry standard adaptive streaming

2. **Nhập URL:**
   - Mặc định load từ localhost
   - Thay đổi IP/port theo server của bạn

3. **Nhấn Play:**
   - App sẽ connect tới server
   - Video sẽ hiển thị khi stream ready

4. **Xem Stats:**
   - Real-time codec information
   - Bitrate monitoring
   - Resolution tracking

5. **Nhấn Stop:**
   - Ngắt kết nối stream
   - Giải phóng resources

---

## ⚙️ Cơ Chế Hoạt Động

### WebRTC Flow

```
┌─────────────┐           ┌──────────────┐           ┌─────────────┐
│             │  1. WS    │              │  4. ICE   │             │
│  OMEPlayer  │◄─────────►│  OME Edge    │◄─────────►│ STUN Server │
│   (Client)  │  Connect  │  (Signaling) │ Candidate │             │
│             │           │              │           └─────────────┘
└─────────────┘           └──────────────┘
      │                          │
      │ 2. Offer SDP             │
      │─────────────────────────►│
      │                          │
      │ 3. Answer SDP            │
      │◄─────────────────────────│
      │                          │
      │ 5. Media Stream (RTP)    │
      │◄═════════════════════════│
      │   Direct P2P Connection  │
```

**WebRTC Steps:**
1. **WebSocket Connection:** Client kết nối tới `ws://edge:4333/app/stream`
2. **Offer/Answer Exchange:** SDP negotiation qua WebSocket
3. **ICE Candidates:** NAT traversal với STUN servers
4. **Media Stream:** Direct RTP stream từ server tới client

**Code Flow (WebRTCClient.kt):**
```kotlin
connect(url)
  → startWebSocketConnection()
  → createPeerConnection()
  → sendOffer()
  → handleSignalingMessage()
  → onRemoteStream()
  → Display Video
```

### HLS/DASH Flow

```
┌─────────────┐           ┌──────────────┐
│             │  1. HTTP  │              │
│  OMEPlayer  │──────────►│  OME Edge    │
│   (Client)  │  Request  │  (HTTP)      │
│             │           │              │
└─────────────┘           └──────────────┘
      │                          │
      │ 2. Manifest (m3u8/mpd)   │
      │◄─────────────────────────│
      │                          │
      │ 3. Segment Request       │
      │─────────────────────────►│
      │                          │
      │ 4. Media Segments (.ts)  │
      │◄═════════════════════════│
      │   Continuous Download    │
```

**HLS/DASH Steps:**
1. **Request Manifest:** GET `http://edge:8080/app/stream/llhls.m3u8`
2. **Parse Playlist:** ExoPlayer phân tích manifest
3. **Download Segments:** Tải các segment video/audio
4. **Adaptive Bitrate:** Tự động chuyển quality dựa trên bandwidth

**Code Flow (ExoPlayerManager.kt):**
```kotlin
playHLS(url)
  → createHlsMediaSource()
  → player.prepare()
  → onPlayerReady()
  → Display Video
```

### Auto-Reconnect Mechanism

```kotlin
// WebRTCClient.kt
private fun attemptReconnect() {
    if (reconnectAttempts >= maxReconnectAttempts) return

    reconnectAttempts++
    delay(2000L * reconnectAttempts)  // Exponential backoff
    disconnect()
    connect(streamUrl)
}
```

**Reconnect Strategy:**
- Attempt 1: Wait 2s
- Attempt 2: Wait 4s
- Attempt 3: Wait 6s
- Attempt 4: Wait 8s
- Attempt 5: Wait 10s → Stop

---

## 🔍 Troubleshooting

### 1. "Connection Failed" Error

**Nguyên nhân:**
- OME server không chạy
- Network không kết nối
- Firewall block ports

**Giải pháp:**
```bash
# Kiểm tra OME server
docker ps | grep ovenmediaengine

# Kiểm tra ports
telnet <server-ip> 4333  # WebRTC
telnet <server-ip> 8080  # HLS

# Test trên browser
http://<server-ip>:8080/app/stream/llhls.m3u8
```

### 2. "Permission Denied" Error

**Nguyên nhân:** User chưa cấp quyền CAMERA/MICROPHONE

**Giải pháp:**
```
Settings → Apps → OMEPlayer → Permissions → Enable Camera & Microphone
```

### 3. Black Screen (Video không hiển thị)

**WebRTC:**
- Check logcat: `adb logcat | grep WebRTC`
- Verify ICE connection state: Should be `CONNECTED` or `COMPLETED`
- Check STUN server accessibility

**HLS/DASH:**
- Check logcat: `adb logcat | grep ExoPlayer`
- Verify HTTP response: Should be 200 OK
- Test URL trên VLC/browser

### 4. High Latency (Độ trễ cao)

**WebRTC:**
- Expected: <1 second
- If >2s: Check network quality, server load

**HLS:**
- Expected: 3-10 seconds (standard HLS)
- LL-HLS: 1-3 seconds
- DASH: 2-6 seconds

### 5. Gradle Build Failed

```bash
# Clear Gradle cache
gradlew clean

# Invalidate Android Studio cache
File → Invalidate Caches / Restart

# Delete .gradle folder
rm -rf .gradle
gradlew build
```

### 6. App Crashes on Start

**Check logcat:**
```bash
adb logcat | grep AndroidRuntime
```

**Common causes:**
- Missing permissions in AndroidManifest
- EglBase initialization failure
- Library version conflicts

---

## 🚀 Mở Rộng

### 1. Thêm Auto Quality Selection (Adaptive Bitrate)

**ExoPlayerManager.kt:**
```kotlin
// Thêm adaptive track selection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

val trackSelector = DefaultTrackSelector(context).apply {
    parameters = buildUponParameters()
        .setMaxVideoSizeSd() // Start with SD, auto-upgrade
        .build()
}

player = ExoPlayer.Builder(context)
    .setTrackSelector(trackSelector)
    .build()
```

### 2. Thêm Fullscreen Toggle

**MainActivity.kt:**
```kotlin
private fun toggleFullscreen() {
    if (isFullscreen) {
        supportActionBar?.show()
        controlPanel.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    } else {
        supportActionBar?.hide()
        controlPanel.visibility = View.GONE
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }
    isFullscreen = !isFullscreen
}

// Thêm button trong activity_main.xml
<ImageButton
    android:id="@+id/fullscreenButton"
    android:src="@drawable/ic_fullscreen"
    android:onClick="toggleFullscreen" />
```

### 3. Thêm Stream Recording

**Sử dụng MediaRecorder:**
```kotlin
import android.media.MediaRecorder

class StreamRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null

    fun startRecording(outputFile: String) {
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(outputFile)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
            start()
        }
    }

    fun stopRecording() {
        mediaRecorder?.apply {
            stop()
            release()
        }
        mediaRecorder = null
    }
}
```

### 4. Thêm Multiple Stream Sources

**ViewModel + LiveData:**
```kotlin
// PlayerViewModel.kt
class PlayerViewModel : ViewModel() {
    private val _streams = MutableLiveData<List<StreamSource>>()
    val streams: LiveData<List<StreamSource>> = _streams

    data class StreamSource(
        val name: String,
        val url: String,
        val type: StreamType
    )

    fun loadStreams() {
        _streams.value = listOf(
            StreamSource("Camera 1", "ws://host:4333/cam1/stream", StreamType.WEBRTC),
            StreamSource("Camera 2", "http://host:8080/cam2/llhls.m3u8", StreamType.HLS)
        )
    }
}

// MainActivity.kt - Thêm RecyclerView để chọn stream
```

### 5. Thêm Network Quality Monitoring

**NetworkMonitor.kt:**
```kotlin
class NetworkMonitor(context: Context) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
        as ConnectivityManager

    fun getCurrentBandwidth(): Int {
        val networkCapabilities = connectivityManager.getNetworkCapabilities(
            connectivityManager.activeNetwork
        )
        return networkCapabilities?.linkDownstreamBandwidthKbps ?: 0
    }

    fun getNetworkType(): String {
        val activeNetwork = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        return when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
            else -> "Unknown"
        }
    }
}
```

### 6. Thêm Screenshot/Snapshot

```kotlin
fun takeSnapshot(view: View): Bitmap {
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    view.draw(canvas)

    // Save to file
    val file = File(context.getExternalFilesDir(null), "snapshot_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    }

    return bitmap
}
```

### 7. Thêm Picture-in-Picture (PiP) Mode

**MainActivity.kt:**
```kotlin
import android.app.PictureInPictureParams
import android.util.Rational

override fun onUserLeaveHint() {
    if (isPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        enterPictureInPictureMode(params)
    }
}
```

**AndroidManifest.xml:**
```xml
<activity
    android:name=".MainActivity"
    android:supportsPictureInPicture="true"
    android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation" />
```

---

## 📚 Tài Liệu Tham Khảo

### OME Documentation
- [OvenMediaEngine Docs](https://docs.ovenmediaengine.com/)
- [WebRTC Play Protocol](https://docs.ovenmediaengine.com/en/stable/reference/webrtc-play/)
- [HLS Streaming](https://docs.ovenmediaengine.com/en/stable/streaming/hls/)

### Android Libraries
- [ExoPlayer Guide](https://developer.android.com/guide/topics/media/exoplayer)
- [WebRTC Android](https://webrtc.github.io/webrtc-org/native-code/android/)
- [Material Components](https://material.io/develop/android)

### Tutorials
- [Building a Video Player with ExoPlayer](https://developer.android.com/codelabs/exoplayer-intro)
- [WebRTC on Android](https://webrtc.org/getting-started/android)

---

## 📄 License

This project is licensed under the MIT License.

---

## 👥 Contributors

- Your Name <your.email@example.com>

---

## 🙏 Acknowledgments

- OvenMediaEngine team for the excellent streaming server
- Google WebRTC team
- ExoPlayer maintainers

---

**Nếu gặp vấn đề, hãy:**
1. Check logcat logs
2. Verify OME server status
3. Test URLs trên browser/VLC
4. Open GitHub issue với logs đầy đủ

**Happy Streaming! 🎉**
