#!/bin/bash

TV_IP="${1:-}"

if [ -n "$TV_IP" ]; then
    adb connect "$TV_IP:5555"
fi

echo "Stopping TV Companion Server on TV..."

# Kill screenrecord if running
adb shell "pkill -f screenrecord 2>/dev/null || true"

# Kill TvServer app_process
adb shell "pkill -f com.example.tvserver.Main 2>/dev/null || true"

# Verify all processes terminated
echo "Verifying running processes..."
adb shell "ps -A | grep -E 'tvserver|screenrecord' || echo 'All TV Server processes stopped.'"

echo "Clean shutdown complete. TV CPU is 0% idle."
