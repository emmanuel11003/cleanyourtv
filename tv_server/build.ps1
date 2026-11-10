# Build script for tv-server.jar on Windows (PowerShell)
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Locate Android SDK
$androidSdk = $env:ANDROID_HOME
if (-not $androidSdk) { $androidSdk = $env:ANDROID_SDK_ROOT }
if (-not $androidSdk) { $androidSdk = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path $androidSdk)) {
    Write-Error "Android SDK not found. Set ANDROID_HOME environment variable."
}

$androidJar = "$androidSdk\platforms\android-34\android.jar"
if (-not (Test-Path $androidJar)) {
    # Fallback to any installed platform android.jar
    $androidJar = (Get-ChildItem "$androidSdk\platforms\*\android.jar" | Select-Object -First 1).FullName
}

$d8 = "$androidSdk\build-tools\34.0.0\d8.bat"
if (-not (Test-Path $d8)) {
    $d8 = (Get-ChildItem "$androidSdk\build-tools\*\d8.bat" | Select-Object -First 1).FullName
}

Write-Host "Compiling TV Server..." -ForegroundColor Cyan
Write-Host "Android SDK: $androidSdk"
Write-Host "Target JAR:  $androidJar"
Write-Host "D8 tool:     $d8"

$buildDir = "$scriptDir\build\classes"
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

$srcFiles = Get-ChildItem "$scriptDir\src\com\example\tvserver\*.java" | ForEach-Object { $_.FullName }
& javac -cp "$androidJar" -d "$buildDir" $srcFiles

Write-Host "Dexing classes into tv-server.jar..." -ForegroundColor Cyan
$classFiles = Get-ChildItem "$buildDir\com\example\tvserver\*.class" | ForEach-Object { $_.FullName }
& "$d8" --lib "$androidJar" --output "$scriptDir\tv-server.jar" $classFiles

$jarSize = (Get-Item "$scriptDir\tv-server.jar").Length
Write-Host "SUCCESS: tv-server.jar created ($jarSize bytes)" -ForegroundColor Green
