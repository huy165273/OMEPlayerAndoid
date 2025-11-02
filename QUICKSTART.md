# OMEPlayer - Quick Start Guide

Hướng dẫn nhanh để build và chạy OMEPlayer trong 5 phút.

---

## 🚀 Build & Run trong 5 phút

### Bước 1: Mở Project (30 giây)

```bash
# Mở Android Studio
File → Open → Chọn thư mục omeplayer/
```

Chờ Gradle sync (1-2 phút lần đầu).

### Bước 2: Connect Device (30 giây)

**Option A: Emulator**
```
Tools → Device Manager → Create Device
→ Pixel 6 Pro → Android 14 → Finish
→ Run
```

**Option B: Physical Device**
```
1. Enable Developer Options
2. Enable USB Debugging
3. Connect USB cable
4. Trust computer
```

### Bước 3: Run App (30 giây)

```
Click ▶️ "Run" button
hoặc Shift+F10
```

### Bước 4: Test Streaming (3 phút)

#### Test HLS (Dễ nhất)

1. Chọn **HLS** radio button
2. URL mặc định: `http://localhost:8080/app/stream/llhls.m3u8`
3. Nếu test trên emulator, URL sẽ tự động là `10.0.2.2` (localhost của host machine)
4. Click **Play**

**Test với public stream:**
```
https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8
```

#### Test WebRTC

1. Chọn **WebRTC** radio button
2. Đảm bảo OME server đang chạy
3. URL: `ws://<server-ip>:4333/app/stream`
4. Click **Play**

---

## 🎯 Cấu Hình Nhanh OME Server

### Option 1: Docker (Khuyên dùng)

```bash
# Pull OME image
docker pull airensoft/ovenmediaengine:latest

# Run OME
docker run -d \
  --name ome \
  -p 1935:1935 \
  -p 4333:4333 \
  -p 8080:8080 \
  airensoft/ovenmediaengine:latest

# Check status
docker ps | grep ome
```

### Option 2: Binary

```bash
# Ubuntu/Debian
wget https://github.com/AirenSoft/OvenMediaEngine/releases/download/v0.15.0/ovenmediaengine_0.15.0_amd64.deb
sudo dpkg -i ovenmediaengine_0.15.0_amd64.deb
sudo systemctl start ovenmediaengine
```

---

## 🔄 Test Stream với OBS

### Bước 1: Setup OBS

1. Download OBS Studio: https://obsproject.com/
2. Settings → Stream
   - Service: Custom
   - Server: `rtmp://localhost:1935/app`
   - Stream Key: `stream`

### Bước 2: Start Streaming

1. Add Source (Display Capture / Video Capture)
2. Click "Start Streaming"
3. Verify trong OME: `http://localhost:8080/`

### Bước 3: Play trong OMEPlayer

**WebRTC:**
```
ws://10.0.2.2:4333/app/stream
```

**HLS:**
```
http://10.0.2.2:8080/app/stream/llhls.m3u8
```

---

## 📱 URLs cho Testing

### Emulator (Android Virtual Device)

| Protocol | URL |
|----------|-----|
| WebRTC | `ws://10.0.2.2:4333/app/stream` |
| HLS | `http://10.0.2.2:8080/app/stream/llhls.m3u8` |
| DASH | `http://10.0.2.2:8080/app/stream/manifest.mpd` |

**Lưu ý:** `10.0.2.2` = localhost của máy host

### Physical Device (Same Network)

| Protocol | URL |
|----------|-----|
| WebRTC | `ws://192.168.1.100:4333/app/stream` |
| HLS | `http://192.168.1.100:8080/app/stream/llhls.m3u8` |
| DASH | `http://192.168.1.100:8080/app/stream/manifest.mpd` |

**Lưu ý:** Thay `192.168.1.100` bằng IP thực của server

### Public Test Streams

**HLS Test Streams:**
```
https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8
https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8
```

**DASH Test Streams:**
```
https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd
https://livesim.dashif.org/livesim/chunkdur_1/ato_7/testpic4_8s/Manifest.mpd
```

---

## 🐛 Troubleshooting Nhanh

### 1. "Connection Failed"

**Check OME đang chạy:**
```bash
# Docker
docker ps | grep ome

# Service
systemctl status ovenmediaengine
```

**Test ports:**
```bash
telnet localhost 4333  # WebRTC
telnet localhost 8080  # HLS
```

### 2. "Permission Denied"

```
Settings → Apps → OMEPlayer → Permissions
→ Enable Camera & Microphone
```

### 3. Black Screen

**Check logcat:**
```bash
adb logcat | grep -E "WebRTC|ExoPlayer|OMEPlayer"
```

**Common fixes:**
- Restart app
- Check URL format
- Verify stream is live
- Try public test stream

### 4. Build Failed

```bash
# Clean build
./gradlew clean

# Rebuild
./gradlew build

# Sync Gradle
File → Sync Project with Gradle Files
```

---

## 📊 Verify Success

### WebRTC Success Indicators

✅ Logcat shows:
```
WebRTCClient: WebSocket opened
WebRTCClient: ICE Connection State: CONNECTED
MainActivity: Status: Playing
```

✅ UI shows:
```
Status: Playing
Stats: Codec: H264 | Bitrate: 2500 kbps | Resolution: 1920x1080
```

### HLS Success Indicators

✅ Logcat shows:
```
ExoPlayerManager: Playing: http://...
ExoPlayerManager: Player ready
```

✅ UI shows:
```
Status: Playing
[Video playing smoothly]
```

---

## 🎓 Next Steps

### 1. Customize UI

Edit `app/src/main/res/layout/activity_main.xml`

### 2. Add Features

- [ ] Full screen mode
- [ ] Stream recording
- [ ] Quality selector
- [ ] Multiple streams

### 3. Deploy

Build release APK:
```bash
./gradlew assembleRelease
```

### 4. Read Documentation

- 📖 [README.md](README.md) - Hướng dẫn đầy đủ
- 🏗️ [ARCHITECTURE.md](ARCHITECTURE.md) - Chi tiết kiến trúc
- 📚 [OME Docs](https://docs.ovenmediaengine.com/)

---

## 💡 Tips

**Tip 1:** Test với public stream trước
```
https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8
```

**Tip 2:** Dùng Chrome DevTools để debug signaling
```
Open: chrome://webrtc-internals
```

**Tip 3:** Monitor OME logs
```bash
docker logs -f ome
```

**Tip 4:** Test connectivity
```bash
# Ping server
ping 192.168.1.100

# Test HTTP endpoint
curl http://192.168.1.100:8080/app/stream/llhls.m3u8
```

---

## 🆘 Need Help?

1. Check [README.md](README.md) Troubleshooting section
2. Review [ARCHITECTURE.md](ARCHITECTURE.md) for details
3. Check OME logs: `docker logs ome`
4. Check app logs: `adb logcat | grep OMEPlayer`
5. Open GitHub issue với logs đầy đủ

---

**Happy Streaming! 🎉**

**Estimated Time:** 5-10 minutes from zero to streaming
**Difficulty:** Beginner
**Success Rate:** 95%+ nếu OME server đang chạy
