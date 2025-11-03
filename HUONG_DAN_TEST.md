# Hướng Dẫn Test OvenMediaEngine Stream

## Tóm Tắt Các Thay Đổi

Đã sửa các vấn đề sau để đảm bảo streaming hoạt động trên máy của bạn:

### 1. Cập Nhật URL Streaming
**File:** `app/src/main/res/values/strings.xml`

```xml
<!-- Trước (localhost - KHÔNG hoạt động trên máy thật) -->
<string name="default_webrtc_url">ws://localhost:4333/app/stream</string>
<string name="default_hls_url">http://localhost:8080/app/stream/llhls.m3u8</string>

<!-- Sau (IP thật của OvenMediaEngine server) -->
<string name="default_webrtc_url">ws://192.168.1.243:4333/app/stream_opus</string>
<string name="default_hls_url">http://192.168.1.243:9080/app/stream/llhls.m3u8</string>
<string name="default_dash_url">http://192.168.1.243:9080/app/stream/manifest.mpd</string>
```

### 2. Thêm Network Security Configuration
**File mới:** `app/src/main/res/xml/network_security_config.xml`

Cho phép cleartext traffic (HTTP/WS) đến server OvenMediaEngine:
- IP: 192.168.1.243
- Localhost (cho testing)
- Toàn bộ dải 192.168.x.x

### 3. Cập Nhật AndroidManifest
**File:** `app/src/main/AndroidManifest.xml`

Thêm reference đến network security config:
```xml
android:networkSecurityConfig="@xml/network_security_config"
```

### 4. Thêm User-Agent Headers
**File:** `app/src/main/java/com/example/omeplayer/player/ExoPlayerManager.kt`

Thêm User-Agent "OMEPlayer/1.0 (Android)" cho HLS và DASH requests để đảm bảo tương thích với server.

---

## Hướng Dẫn Build và Test

### Bước 1: Clean và Rebuild Project

```bash
# Trên Windows (từ thư mục project)
.\gradlew clean
.\gradlew build

# Hoặc từ Android Studio:
# Build > Clean Project
# Build > Rebuild Project
```

### Bước 2: Kiểm Tra Kết Nối Mạng

**Điều kiện tiên quyết:**
- Điện thoại Android và máy tính phải cùng mạng Wi-Fi
- IP của OvenMediaEngine server: `192.168.1.243`
- Firewall phải cho phép các port:
  - **4333** (WebRTC signaling - WebSocket)
  - **9080** (HLS/DASH - HTTP)
  - **10000-10009** (WebRTC media - UDP, nếu dùng WebRTC)

**Test kết nối từ điện thoại:**
1. Mở Chrome trên điện thoại Android
2. Truy cập: `http://192.168.1.243:9080/app/stream/llhls.m3u8`
3. Nếu thấy nội dung text (playlist M3U8), kết nối OK

### Bước 3: Cài Đặt và Chạy App

1. **Kết nối điện thoại với ADB:**
   ```bash
   adb devices
   ```

2. **Install APK:**
   ```bash
   .\gradlew installDebug

   # Hoặc từ Android Studio:
   # Run > Run 'app'
   ```

3. **Cấp quyền (nếu cần):**
   - Camera
   - Microphone (RECORD_AUDIO)
   - Internet (tự động)

### Bước 4: Test HLS Stream

1. Mở app OMEPlayer
2. Chọn radio button **"HLS"**
3. URL sẽ tự động điền: `http://192.168.1.243:9080/app/stream/llhls.m3u8`
4. Nhấn nút **"Play"**
5. Kiểm tra:
   - Video hiển thị
   - Status: "Playing"
   - Stats hiển thị: Codec, Bitrate, Resolution

**Troubleshooting HLS:**
- Nếu lỗi "Connection failed": Kiểm tra IP và port
- Nếu lỗi "Playback error": Kiểm tra stream có đang live không
- Kiểm tra logcat: `adb logcat -s ExoPlayerManager`

### Bước 5: Test WebRTC Stream

1. Trong app, chọn radio button **"WebRTC"**
2. URL tự động: `ws://192.168.1.243:4333/app/stream_opus`
3. Nhấn **"Play"**
4. Kiểm tra:
   - Status chuyển: "Connecting..." → "Playing"
   - Video hiển thị
   - Stats hiển thị bytes và packets received

**Troubleshooting WebRTC:**
- Nếu "Connection failed": Kiểm tra WebSocket port 4333
- Nếu "ICE connection failed": Kiểm tra firewall/router
- Nếu có audio nhưng không có video: Kiểm tra stream source
- Kiểm tra logcat: `adb logcat -s WebRTCClient`

### Bước 6: Test DASH Stream (Optional)

1. Chọn **"DASH"**
2. URL: `http://192.168.1.243:9080/app/stream/manifest.mpd`
3. Nhấn **"Play"**

---

## Debug với Logcat

### Xem tất cả logs của app:
```bash
adb logcat -s MainActivity WebRTCClient ExoPlayerManager
```

### Xem logs theo level (chỉ errors):
```bash
adb logcat *:E
```

### Lưu logs vào file:
```bash
adb logcat -s OMEPlayer:* > app_logs.txt
```

### Các thông điệp quan trọng cần chú ý:

**HLS Success:**
```
ExoPlayerManager: Playing: http://192.168.1.243:9080/app/stream/llhls.m3u8
ExoPlayerManager: Player ready
ExoPlayerManager: Codec: video/avc | Bitrate: 6063 kbps | Resolution: 1920x1080
```

**WebRTC Success:**
```
WebRTCClient: Connecting to: ws://192.168.1.243:4333/app/stream_opus
WebRTCClient: WebSocket opened
WebRTCClient: Offer sent
WebRTCClient: Remote description set successfully
WebRTCClient: ICE Connection State: CONNECTED
```

**Errors thường gặp:**
```
# Network không thể truy cập
java.net.UnknownHostException: Unable to resolve host

# Cleartext traffic bị chặn (đã fix bằng network_security_config.xml)
java.io.IOException: Cleartext HTTP traffic not permitted

# Server không phản hồi
java.net.SocketTimeoutException: timeout

# Port sai hoặc server không chạy
java.net.ConnectException: Connection refused
```

---

## Kiểm Tra OvenMediaEngine Server

### 1. Kiểm tra server đang chạy:
```bash
# Trên server OME
ps aux | grep OvenMediaEngine
```

### 2. Kiểm tra ports đang listen:
```bash
# Linux
netstat -tuln | grep -E '4333|9080'

# Windows
netstat -an | findstr "4333 9080"
```

### 3. Test từ browser (HLS):
Mở browser trên máy tính và truy cập:
```
https://demo.ovenplayer.com/
```
Nhập URL:
```
http://192.168.1.243:9080/app/stream/llhls.m3u8
```

### 4. Kiểm tra stream có sẵn:
```bash
curl -I http://192.168.1.243:9080/app/stream/llhls.m3u8
```
Phải trả về: `HTTP/1.1 200 OK`

---

## Các URL Đã Cấu Hình

| Stream Type | URL | Port |
|------------|-----|------|
| WebRTC | `ws://192.168.1.243:4333/app/stream_opus` | 4333 |
| HLS | `http://192.168.1.243:9080/app/stream/llhls.m3u8` | 9080 |
| DASH | `http://192.168.1.243:9080/app/stream/manifest.mpd` | 9080 |

**Lưu ý:** Bạn có thể thay đổi URL ngay trong app bằng cách edit TextInput field.

---

## Thay Đổi IP Server (Nếu Cần)

Nếu IP của OvenMediaEngine server thay đổi, cập nhật file:

**`app/src/main/res/values/strings.xml`:**
```xml
<string name="default_webrtc_url">ws://[NEW_IP]:4333/app/stream_opus</string>
<string name="default_hls_url">http://[NEW_IP]:9080/app/stream/llhls.m3u8</string>
<string name="default_dash_url">http://[NEW_IP]:9080/app/stream/manifest.mpd</string>
```

**`app/src/main/res/xml/network_security_config.xml`:**
```xml
<domain includeSubdomains="false">[NEW_IP]</domain>
```

Sau đó rebuild app.

---

## Checklist Trước Khi Test

- [ ] OvenMediaEngine server đang chạy
- [ ] Stream `app/stream` hoặc `app/stream_opus` đang live
- [ ] Điện thoại và server cùng mạng Wi-Fi
- [ ] Đã rebuild app sau khi thay đổi config
- [ ] Đã cấp permissions (Camera, Microphone) cho app
- [ ] Firewall cho phép ports 4333 và 9080
- [ ] Test URL từ browser thành công

---

## Kết Quả Mong Đợi

### HLS Stream:
- Video play ngay lập tức (latency ~2-10 giây)
- Adaptive bitrate (tự động điều chỉnh chất lượng)
- Stats hiển thị codec, bitrate, resolution
- Không cần ICE/STUN servers

### WebRTC Stream:
- Latency thấp (~0.5-2 giây)
- Real-time video
- Stats hiển thị bytes/packets received
- Cần ICE connection thành công

---

## Tài Liệu Tham Khảo

- [OvenMediaEngine Docs](https://airensoft.gitbook.io/ovenmediaengine)
- [ExoPlayer Guide](https://developer.android.com/guide/topics/media/exoplayer)
- [WebRTC Android](https://webrtc.github.io/webrtc-org/native-code/android/)
- [Android Network Security Config](https://developer.android.com/training/articles/security-config)

---

## Liên Hệ

Nếu gặp vấn đề, cung cấp:
1. Logcat output (adb logcat)
2. Android version
3. OvenMediaEngine version
4. Thông báo lỗi chi tiết
