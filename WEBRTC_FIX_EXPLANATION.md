# WebRTC Fix - Chi Tiết Các Thay Đổi

## 🎯 Tóm Tắt

Đã sửa 3 vấn đề quan trọng trong WebRTCClient.kt để đảm bảo kết nối WebRTC với OvenMediaEngine Edge thành công.

---

## 📋 Các Vấn Đề Đã Phát Hiện

### Vấn Đề #1: MediaConstraints Lỗi Thời với UNIFIED_PLAN ❌

**Triệu chứng:**
- App gửi answer nhưng OME Edge không nhận được media tracks
- Video/audio không phát dù ICE connection thành công

**Nguyên nhân:**
```kotlin
// CŨ - Line 279-282
val constraints = MediaConstraints().apply {
    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
}
```

`MediaConstraints` với `"OfferToReceiveVideo/Audio"` là cách cũ (PLAN_B era). Với `SdpSemantics.UNIFIED_PLAN` (line 137), cách này **không còn hiệu quả**.

**Giải pháp:**
```kotlin
// MỚI - Line 293-295
// With UNIFIED_PLAN and transceivers, we don't need MediaConstraints
// Transceivers already define what we want to receive
val constraints = MediaConstraints()
```

---

### Vấn Đề #2: Thiếu Transceiver Setup ❌

**Triệu chứng:**
- Peer connection được tạo nhưng không có media tracks
- OME Edge không biết client muốn receive gì

**Nguyên nhân:**
Với UNIFIED_PLAN, **PHẢI** add transceivers để chỉ định:
- Client muốn receive audio và video
- Direction: RECV_ONLY (chỉ receive, không send)

Code cũ không có transceivers → OME không biết client muốn gì.

**Giải pháp:**
```kotlin
// MỚI - Line 199-211
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
```

**Vị trí:** Ngay sau khi create PeerConnection, TRƯỚC khi setRemoteDescription.

---

### Vấn Đề #3: Logging Không Đầy Đủ ⚠️

**Triệu chứng:**
- Khó debug khi có lỗi
- Không biết SDP có được parse đúng không

**Giải pháp:**
Thêm detailed logging:

```kotlin
// Line 242
Log.d(tag, "Parsed SDP offer (${sdpString.length} chars)")

// Line 246
Log.d(tag, "✓ Remote offer set successfully")

// Line 300
Log.d(tag, "✓ Answer created (${sdp.description.length} chars)")

// Line 304
Log.d(tag, "✓ Local answer set successfully")

// Line 316
Log.d(tag, "✓ Answer sent to server (${answerStr.length} bytes)")
```

---

## 🔄 Luồng WebRTC Signaling Đúng

### Bước 1: WebSocket Connection
```
Client → Server: WebSocket Upgrade (ws://192.168.1.243:4333/app/stream_opus)
Server → Client: 101 Switching Protocols
```

**Log:**
```
D/WebRTCClient: Connecting to: ws://192.168.1.243:4333/app/stream_opus
D/WebRTCClient: WebSocket opened
```

### Bước 2: Create PeerConnection + Add Transceivers
```kotlin
createPeerConnection()
  ├─> Configure ICE servers (STUN)
  ├─> Set UNIFIED_PLAN semantics
  ├─> Add audio transceiver (RECV_ONLY)
  └─> Add video transceiver (RECV_ONLY)
```

**Log:**
```
D/WebRTCClient: Added audio and video transceivers (recv-only)
```

### Bước 3: Request Offer từ Server
```json
Client → Server:
{
  "command": "request_offer"
}
```

**Log:**
```
D/WebRTCClient: Requested offer from server
```

### Bước 4: Receive Offer từ OME Edge
```json
Server → Client:
{
  "command": "offer",
  "sdp": {
    "sdp": "v=0\r\no=OvenMediaEngine...",
    "type": "offer"
  },
  "candidates": [
    {"candidate": "...", "sdpMLineIndex": 0}
  ]
}
```

**Log:**
```
D/WebRTCClient: Received message: {"command":"offer",...}
D/WebRTCClient: Parsed SDP offer (1234 chars)
D/WebRTCClient: ✓ Remote offer set successfully
D/WebRTCClient: Added ICE candidate from offer
```

### Bước 5: Create và Send Answer
```json
Client → Server:
{
  "command": "answer",
  "sdp": {
    "sdp": "v=0\r\no=...",
    "type": "answer"
  }
}
```

**Log:**
```
D/WebRTCClient: ✓ Answer created (987 chars)
D/WebRTCClient: ✓ Local answer set successfully
D/WebRTCClient: ✓ Answer sent to server (1024 bytes)
```

### Bước 6: ICE Candidate Exchange
```json
Client ↔ Server:
{
  "command": "candidate",
  "sdpMid": "0",
  "sdpMLineIndex": 0,
  "candidate": "candidate:..."
}
```

**Log:**
```
D/WebRTCClient: ICE Candidate: candidate:...
D/WebRTCClient: ICE Gathering State: GATHERING
D/WebRTCClient: ICE Gathering State: COMPLETE
```

### Bước 7: ICE Connection Established
```
ICE Negotiation → CONNECTED
Media Stream → onAddStream() → Video Display
```

**Log:**
```
D/WebRTCClient: ICE Connection State: CHECKING
D/WebRTCClient: ICE Connection State: CONNECTED
D/WebRTCClient: Remote stream added
```

---

## 🧪 Hướng Dẫn Test

### Bước 1: Build và Install

```bash
# Windows
cd D:\Code\OME\OMEPlayerAndoid
.\gradlew clean build installDebug

# Hoặc dùng script
.\TEST_WEBRTC.bat
```

### Bước 2: Kiểm Tra Kết Nối Mạng

```bash
# Ping OME server
ping 192.168.1.243

# Test WebSocket endpoint
curl -I --http1.1 ^
  --header "Connection: Upgrade" ^
  --header "Upgrade: websocket" ^
  http://192.168.1.243:4333/app/stream_opus

# Expect: HTTP/1.1 101 Switching Protocols
```

### Bước 3: Chạy App và Monitor Logs

**Terminal 1 - Full Logs:**
```bash
adb logcat -s WebRTCClient:D MainActivity:D
```

**Terminal 2 - Filtered Logs:**
```bash
adb logcat -s WebRTCClient | findstr /C:"✓" /C:"ICE" /C:"Remote stream"
```

### Bước 4: Thao Tác trong App

1. Mở app **OMEPlayer**
2. Chọn radio button **"WebRTC"**
3. URL auto-fill: `ws://192.168.1.243:4333/app/stream_opus`
4. Nhấn **"Play"**
5. Quan sát logs và video

### Bước 5: Verify Success

**Logs thành công:**
```
✅ WebSocket opened
✅ Added audio and video transceivers (recv-only)
✅ Requested offer from server
✅ Received message: {"command":"offer"...}
✅ Parsed SDP offer (1234 chars)
✅ ✓ Remote offer set successfully
✅ Added ICE candidate from offer
✅ ✓ Answer created (987 chars)
✅ ✓ Local answer set successfully
✅ ✓ Answer sent to server (1024 bytes)
✅ ICE Connection State: CONNECTED
✅ Remote stream added
```

**UI Success:**
- Status: "Playing"
- Video hiển thị trong SurfaceView
- Stats update mỗi 2s

---

## 🐛 Troubleshooting

### Lỗi: "Added transceivers" không xuất hiện

**Nguyên nhân:** Code không được cập nhật hoặc build cache

**Giải pháp:**
```bash
.\gradlew clean
.\gradlew build
.\gradlew installDebug
```

---

### Lỗi: "ICE Connection State: FAILED"

**Nguyên nhân:**
- Firewall chặn UDP ports
- NAT traversal không thành công
- STUN servers không accessible

**Giải pháp:**
1. Kiểm tra firewall cho phép UDP 10000-10009
2. Test từ cùng mạng LAN
3. Verify STUN servers:
   ```bash
   # Test STUN với stunclient
   stunclient stun.l.google.com 19302
   ```

---

### Lỗi: "Remote stream added" nhưng không có video

**Nguyên nhân:**
- Stream source không có video track
- Codec không support
- SurfaceView visibility issue

**Giải pháp:**
1. Verify stream có video:
   ```bash
   # Test trên OvenPlayer demo
   https://demo.ovenplayer.com/
   # URL: ws://192.168.1.243:4333/app/stream_opus
   ```

2. Check logcat cho codec info:
   ```bash
   adb logcat | grep -i "codec\|video\|track"
   ```

3. Verify SurfaceView visible:
   - Check MainActivity.kt:216-219
   - webrtcSurfaceView.visibility = View.VISIBLE

---

### Lỗi: "sessionDescription is NULL"

**Nguyên nhân:** OME trả về SDP format khác

**Giải pháp:** Check raw message:
```bash
adb logcat -s WebRTCClient | grep "Received message"
```

Copy message và verify format:
```json
{
  "command": "offer",
  "sdp": {             ← MUST be object
    "sdp": "v=0...",   ← MUST have this key
    "type": "offer"
  }
}
```

---

## 📊 So Sánh Code Cũ vs Mới

| Aspect | Code Cũ | Code Mới |
|--------|---------|----------|
| **Transceivers** | ❌ Không có | ✅ RECV_ONLY audio + video |
| **MediaConstraints** | ❌ "OfferToReceiveVideo/Audio" | ✅ Empty (dùng transceivers) |
| **Logging** | ⚠️ Cơ bản | ✅ Chi tiết với ✓ marks |
| **UNIFIED_PLAN** | ⚠️ Khai báo nhưng không dùng đúng | ✅ Implement đúng spec |
| **OME Compatibility** | ⚠️ Một phần | ✅ Hoàn toàn tương thích |

---

## 📚 Tài Liệu Tham Khảo

### WebRTC UNIFIED_PLAN
- [WebRTC 1.0 Spec - Transceivers](https://www.w3.org/TR/webrtc/#rtcrtptransceiver-interface)
- [Migrating to Unified Plan](https://webrtc.org/getting-started/unified-plan-transition-guide)

### OvenMediaEngine
- [OME WebRTC Docs](https://airensoft.gitbook.io/ovenmediaengine/streaming/webrtc-publishing)
- [OME Signaling Protocol](https://github.com/AirenSoft/OvenMediaEngine/blob/master/docs/signaling.md)

### Android WebRTC
- [WebRTC Android Guide](https://webrtc.github.io/webrtc-org/native-code/android/)
- [Google Samples](https://github.com/googlecodelabs/webrtc-android)

---

## ✅ Checklist Verification

- [x] Build thành công (gradlew build)
- [x] Transceivers được add đúng
- [x] MediaConstraints đã loại bỏ "OfferToReceiveX"
- [x] Logging chi tiết với ✓ marks
- [x] Code format đúng Kotlin style
- [ ] **Manual test trên thiết bị** ← BẠN CẦN LÀM

---

## 🎓 Key Takeaways

1. **UNIFIED_PLAN yêu cầu Transceivers** - Không thể dùng MediaConstraints cũ
2. **Transceivers phải add TRƯỚC setRemoteDescription** - Timing quan trọng
3. **Direction phải đúng** - RECV_ONLY cho receive-only client
4. **Logging chi tiết** - Giúp debug nhanh hơn

---

**Date:** 2025-11-03
**Version:** 2.0
**Status:** ✅ Ready for Testing
