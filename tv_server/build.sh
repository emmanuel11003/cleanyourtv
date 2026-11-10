#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Detect Android SDK
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [ ! -d "$ANDROID_SDK" ]; then
    echo "Error: Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT." >&2
    exit 1
fi

ANDROID_JAR=$(find "$ANDROID_SDK/platforms" -name "android.jar" | sort -V | tail -n 1)
D8_BIN=$(find "$ANDROID_SDK/build-tools" -name "d8" | sort -V | tail -n 1)

if [ -z "$ANDROID_JAR" ] || [ -z "$D8_BIN" ]; then
    echo "Error: android.jar or d8 tool not found in SDK." >&2
    exit 1
fi

echo "Compiling TV Server..."
echo "Android SDK: $ANDROID_SDK"
echo "Target JAR:  $ANDROID_JAR"
echo "D8 Tool:     $D8_BIN"

BUILD_DIR="$SCRIPT_DIR/build/classes"
mkdir -p "$BUILD_DIR"

javac -cp "$ANDROID_JAR" -d "$BUILD_DIR" "$SCRIPT_DIR"/src/com/example/tvserver/*.java

echo "Dexing classes into tv-server.jar..."
"$D8_BIN" --lib "$ANDROID_JAR" --output "$SCRIPT_DIR/tv-server.jar" "$BUILD_DIR"/com/example/tvserver/*.class

JAR_SIZE=$(wc -c < "$SCRIPT_DIR/tv-server.jar")
echo "SUCCESS: tv-server.jar created ($JAR_SIZE bytes)"
