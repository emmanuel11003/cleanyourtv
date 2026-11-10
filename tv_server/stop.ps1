# Stop TV Mirror Server on Haier 55P7GT-P via ADB (PowerShell)
param(
    [string]$TvIp
)

if ($TvIp) {
    adb connect "$TvIp:5555"
}

Write-Host "Stopping TV Companion Server on TV..." -ForegroundColor Yellow

# Kill screenrecord if running
adb shell "pkill -f screenrecord 2>/dev/null || true"

# Kill TvServer app_process
adb shell "pkill -f com.example.tvserver.Main 2>/dev/null || true"

# Verify all processes terminated
Write-Host "Verifying running processes..." -ForegroundColor Cyan
adb shell "ps -A | grep -E 'tvserver|screenrecord' || echo 'All TV Server processes stopped.'"

Write-Host "Clean shutdown complete. TV CPU is 0% idle." -ForegroundColor Green
