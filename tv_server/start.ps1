# Start TV Mirror Server on Haier 55P7GT-P via ADB (PowerShell)
param(
    [string]$TvIp,
    [int]$Port = 55555,
    [string]$Token = "123456",
    [switch]$Background
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$jarPath = "$scriptDir\tv-server.jar"

if (-not (Test-Path $jarPath)) {
    Write-Host "tv-server.jar not found. Building now..." -ForegroundColor Yellow
    & "$scriptDir\build.ps1"
}

# Connect if TV IP provided
if ($TvIp) {
    Write-Host "Connecting to TV at $TvIp:5555 via ADB..." -ForegroundColor Cyan
    adb connect "$TvIp:5555"
}

# Verify ADB connection
$adbDevices = adb devices
Write-Host "$adbDevices"

Write-Host "Pushing tv-server.jar to /data/local/tmp/..." -ForegroundColor Cyan
adb push "$jarPath" /data/local/tmp/tv-server.jar

Write-Host "Starting TV Server (Port: $Port, Token: $Token)..." -ForegroundColor Green
Write-Host "Target TV: Haier 55P7GT-P (MediaTek MT9676) | Hardware 60 FPS Encoder" -ForegroundColor Gray

if ($Background) {
    adb shell "nohup sh -c 'CLASSPATH=/data/local/tmp/tv-server.jar app_process / com.example.tvserver.Main $Port $Token' > /dev/null 2>&1 &"
    Write-Host "TV Server running in background." -ForegroundColor Green
} else {
    Write-Host "Starting in foreground (Press Ctrl+C to stop)..." -ForegroundColor Cyan
    adb shell "CLASSPATH=/data/local/tmp/tv-server.jar app_process / com.example.tvserver.Main $Port $Token"
}
