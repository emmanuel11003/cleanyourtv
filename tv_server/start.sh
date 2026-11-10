#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="$SCRIPT_DIR/tv-server.jar"

TV_IP="${1:-}"
PORT="${2:-55555}"
TOKEN="${3:-123456}"
BACKGROUND="${4:-}"

if [ ! -f "$JAR_PATH" ]; then
    echo "tv-server.jar not found. Building now..."
    "$SCRIPT_DIR/build.sh"
fi

if [ -n "$TV_IP" ]; then
    echo "Connecting to TV at $TV_IP:5555 via ADB..."
    adb connect "$TV_IP:5555"
fi

adb devices

echo "Pushing tv-server.jar to /data/local/tmp/..."
adb push "$JAR_PATH" /data/local/tmp/tv-server.jar

echo "Starting TV Server on port $PORT with token $TOKEN..."
echo "Target TV: Haier 55P7GT-P (MediaTek MT9676) | Hardware 60 FPS Encoder"

if [ "$BACKGROUND" = "--bg" ] || [ "$BACKGROUND" = "-d" ]; then
    adb shell "nohup sh -c 'CLASSPATH=/data/local/tmp/tv-server.jar app_process / com.example.tvserver.Main $PORT $TOKEN' > /dev/null 2>&1 &"
    echo "TV Server running in background."
else
    echo "Starting in foreground (Press Ctrl+C to stop)..."
    adb shell "CLASSPATH=/data/local/tmp/tv-server.jar app_process / com.example.tvserver.Main $PORT $TOKEN"
fi
