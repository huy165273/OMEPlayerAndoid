#!/bin/bash
# Test WebRTC Script

echo "========================================"
echo "TEST WEBRTC - OvenMediaEngine"
echo "========================================"
echo ""

echo "[1] Building project..."
./gradlew clean build
if [ $? -ne 0 ]; then
    echo "ERROR: Build failed!"
    exit 1
fi
echo "Build OK!"
echo ""

echo "[2] Installing to device..."
./gradlew installDebug
if [ $? -ne 0 ]; then
    echo "ERROR: Install failed! Is device connected?"
    exit 1
fi
echo "Install OK!"
echo ""

echo "[3] Starting logcat..."
echo ""
echo "Instructions:"
echo "1. Open OMEPlayer app on your device"
echo "2. Select 'WebRTC'"
echo "3. Press 'Play'"
echo "4. Watch logs below for connection status"
echo ""
echo "Press Ctrl+C to stop logcat"
echo "========================================"
echo ""

adb logcat -s WebRTCClient MainActivity | grep -E "Requested|Received|offer|answer|ICE|CONNECTED|Error"
