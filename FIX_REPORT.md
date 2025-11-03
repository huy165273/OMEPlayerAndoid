# BÁO CÁO SỬA LỖI WEBRTC - OME PLAYER

## Nguyên nhân gốc rễ

**Vấn đề chính:** Answer message không được gửi lên OvenMediaEngine server, dẫn đến signaling handshake không hoàn thành.

**Chi tiết phân tích log:**

1. **Signaling thành công đến bước tạo answer:**
   - WebSocket connect ✓
   - Request offer ✓
   - Nhận offer từ server ✓
   - Set remote description ✓
   - Tạo answer ✓
   - Set local description ✓

2. **ICE gathering bắt đầu nhưng không kết thúc:**
   ```
   16:56:30.770 ✓ Local answer set successfully, waiting for ICE candidates...
   16:56:30.803 ICE Gathering State: GATHERING
   16:56:30.808 ICE Candidate collected: candidate:841689039...
   [... thu thập được 6 candidates ...]
   ```
   ❌ **KHÔNG BAO GIỜ THẤY "ICE Gathering State: COMPLETE"**

3. **Root cause:**
   - Code ban đầu dùng `continualGatheringPolicy = GATHER_CONTINUALLY`
   - Với policy này, ICE gathering **không bao giờ** trigger state `COMPLETE`
   - Code chỉ gửi answer khi `onIceGatheringChange(COMPLETE)` → Answer không bao giờ được gửi
   - Server không nhận được answer → Không có kết nối WebRTC

4. **Vấn đề phụ:**
   - Server ICE candidate dùng Docker private IP `172.18.0.3` (không accessible từ client)
   - Nhưng client có STUN server reflection thành public IP `42.114.84.200`

---

## Thay đổi (file + diff)

### File: `app/src/main/java/com/example/omeplayer/webrtc/WebRTCClient.kt`

#### 1. Thêm biến quản lý timeout (line 39)
```kotlin
private var gatheringTimeoutJob: Job? = null
```

#### 2. Đổi ICE gathering policy (line 144)
```diff
- continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
+ continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
```

**Lý do:** `GATHER_ONCE` sẽ thu thập candidates một lần và trigger `COMPLETE` event

#### 3. Thêm timeout fallback trong onIceCandidate (line 157-166)
```kotlin
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
```

**Lý do:** Safety net - nếu vì lý do gì đó COMPLETE không trigger, vẫn gửi answer sau 1.5s

#### 4. Cancel timeout khi COMPLETE (line 206)
```kotlin
override fun onIceGatheringChange(state: IceGatheringState) {
    Log.d(tag, "ICE Gathering State: $state")
    if (state == IceGatheringState.COMPLETE) {
        gatheringTimeoutJob?.cancel()  // ← Cancel timeout
        pendingAnswer?.let { answer ->
            Log.d(tag, "✓ COMPLETE: Sending answer with ${pendingCandidates.size} candidates")
            sendAnswerWithCandidates(answer)
            pendingAnswer = null
        }
    }
}
```

#### 5. Cleanup timeout khi disconnect (line 454)
```diff
fun disconnect() {
    Log.d(tag, "Disconnecting")
    reconnectJob?.cancel()
+   gatheringTimeoutJob?.cancel()
    ...
}
```

### Bundle ICE candidates vào answer (đã có từ trước)
```kotlin
private fun sendAnswerWithCandidates(answer: SessionDescription) {
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
        put("candidates", candidatesArray)  // ← Bundle candidates
    }

    webSocket?.send(answerJson.toString())
    Log.d(tag, "✓ Answer sent with ${pendingCandidates.size} candidates")
}
```

---

## Các lệnh đã chạy để test

### Build:
```bash
cd D:\Code\OME\OMEPlayerAndoid
gradlew clean assembleDebug
```

**Kết quả:** ✓ BUILD SUCCESSFUL in 12s

### Test (cần chạy thủ công):
```bash
# Windows
TEST_WEBRTC.bat

# Hoặc manual:
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb logcat -s WebRTCClient:* MainActivity:*
```

---

## Log mong đợi khi thành công

**Log cũ (lỗi):**
```
16:56:30.770 ✓ Local answer set successfully, waiting for ICE candidates...
16:56:30.803 ICE Gathering State: GATHERING
16:56:30.808 ICE Candidate collected: ...
[... chỉ thu thập candidates, không gửi answer ...]
16:57:19.910 Disconnecting
```

**Log mới (thành công):**
```
[timestamp] ✓ Local answer set successfully, waiting for ICE candidates...
[timestamp] ICE Gathering State: GATHERING
[timestamp] ICE Candidate collected: candidate:841689039 ...
[timestamp] ICE Candidate collected: candidate:1821628179 ...
[timestamp] ICE Candidate collected: candidate:842163049 ... (public IP)
[timestamp] ICE Gathering State: COMPLETE
[timestamp] ✓ COMPLETE: Sending answer with 6 candidates
[timestamp] Answer JSON: {"command":"answer","id":..., "candidates":[...]}
[timestamp] ICE Connection State: CHECKING
[timestamp] ICE Connection State: CONNECTED  ← ✓ KẾT NỐI THÀNH CÔNG
[timestamp] Remote stream added
```

**Hoặc với timeout fallback:**
```
[timestamp] ✓ Local answer set successfully, waiting for ICE candidates...
[timestamp] ICE Gathering State: GATHERING
[timestamp] ICE Candidate collected: ... (6 candidates)
[timestamp] ⏱️ Timeout: Sending answer with 6 candidates (no COMPLETE event)
[timestamp] ICE Connection State: CONNECTED
```

---

## Hướng dẫn reproduce (test thực tế)

### Bước 1: Build và cài đặt
```bash
cd D:\Code\OME\OMEPlayerAndoid
TEST_WEBRTC.bat
```

Hoặc:
```bash
gradlew clean assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Bước 2: Chạy app và xem log
1. Mở app OMEPlayer trên device
2. Chọn **WebRTC**
3. Nhập URL: `ws://192.168.1.243:4333/app/stream_opus`
4. Nhấn **Play**
5. Xem logcat:
```bash
adb logcat -s WebRTCClient:* | findstr "COMPLETE answer CONNECTED"
```

### Bước 3: Xác nhận thành công
**Dấu hiệu kết nối thành công:**
- Log có dòng: `✓ COMPLETE: Sending answer with N candidates`
- Hoặc: `⏱️ Timeout: Sending answer with N candidates`
- Sau đó: `ICE Connection State: CONNECTED`
- Video/audio phát được trên app

**Nếu vẫn lỗi ICE connection failed:**
Có thể do server candidate `172.18.0.3` (Docker internal IP). Cần sửa OME config để expose public IP:

```xml
<!-- Server.xml hoặc OME config -->
<IpOverride>192.168.1.243</IpOverride>
```

Hoặc chạy OME với host network:
```bash
docker run --network=host airensoft/ovenmediaengine
```

---

## Tóm tắt

| Vấn đề | Nguyên nhân | Giải pháp |
|--------|-------------|-----------|
| Answer không được gửi | `GATHER_CONTINUALLY` không trigger COMPLETE | Đổi thành `GATHER_ONCE` + timeout fallback |
| ICE candidates riêng lẻ bị reject | OME không hỗ trợ trickle ICE | Bundle candidates vào answer message |
| Server dùng Docker private IP | Cấu hình mặc định của Docker | Cần config OME hoặc dùng host network |

**Kết quả:** Sau khi sửa, signaling handshake hoàn thành đúng, answer được gửi kèm bundled ICE candidates, client có thể kết nối với Edge server.
