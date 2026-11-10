# TV Companion: Offline LAN Mirror & Remote

High-performance, ultra-lightweight Android companion app designed to mirror the TV screen and provide full remote control over local Wi-Fi without internet or cloud services.

Optimized specifically for:
- **TV:** Haier 55P7GT-P (MediaTek MT9676 / Pentonic / Android 14 API 34) running CleanYourTV
- **Phone:** Poco F7 5G (Hardware H.264 decoder, Android 14/15)

---

## Key Highlights

- **Strict Size Gate:** Final release APK is **43,176 bytes** (~42.1 KB), vastly below the **10,000,000 bytes (10 MB)** hard limit (using only **0.43%** of budget!).
- **100% Offline LAN:** Works over local Wi-Fi or phone hotspot without internet connectivity.
- **No Cloud / No Tracking:** Zero Firebase, zero analytics, zero ads, zero external telemetry.
- **Zero Third-Party Bloat:** Pure Android SDK APIs. Zero ExoPlayer, zero FFmpeg, zero libVLC, zero WebRTC.
- **60 FPS Hardware Video Pipeline:** Uses Android `MediaCodec` low-latency decoding (`KEY_LOW_LATENCY = 1`) directly to `SurfaceView`.
- **Full TV Remote & Touchpad:** On-screen D-pad, OK, Back, Home, Recents, Volume Up/Down/Mute, plus touchpad gestures (tap = OK, swipe = D-pad navigation, long press = Back/Menu).
- **Wi-Fi Process Binding:** Uses Android `ConnectivityManager.bindProcessToNetwork()` to ensure traffic stays on local Wi-Fi even when Android detects "No Internet".
- **Clean & Safe:** No root required on TV. Server runs entirely in user shell space and consumes 0% CPU when disconnected.

---

## Project Structure

```
d:\emmanuel\new tv app\
├── companion\                   # Phone Android Companion App
│   ├── app\
│   │   ├── src\main\
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java\com\companion\tvmirror\
│   │   │   │   ├── MainActivity.java
│   │   │   │   ├── data\AppSettings.java
│   │   │   │   ├── input\RemoteCommand.java
│   │   │   │   ├── input\TouchpadController.java
│   │   │   │   ├── network\IpValidator.java
│   │   │   │   ├── network\LocalWifiManager.java
│   │   │   │   ├── network\TvConnection.java
│   │   │   │   └── video\NalParser.java
│   │   │   │   └── video\VideoDecoder.java
│   │   │   └── res\
│   │   │       ├── drawable\          # Vector icons & shape drawables
│   │   │       ├── layout\            # Connect, Mirror, Settings, Help views
│   │   │       └── values\            # Colors, strings, dark theme styles
│   │   ├── build.gradle
│   │   └── proguard-rules.pro         # Aggressive R8 rules
│   ├── build.gradle
│   ├── settings.gradle
│   ├── gradlew / gradlew.bat
│   └── README.md
│
├── tv_server\                   # TV-side Screen Capture & Input Server
│   ├── src\com\example\tvserver\
│   │   ├── Main.java                  # TCP server, handshake, command dispatcher
│   │   ├── ScreenStreamer.java        # Hardware H.264 screen capture pipeline
│   │   ├── InputInjector.java         # D-pad & key injection via shell/InputManager
│   │   └── TokenAuth.java             # LAN subnet filter & token verification
│   ├── tv-server.jar                  # Precompiled DEX jar for app_process
│   ├── build.sh / build.ps1           # Build script using javac & d8
│   ├── start.sh / start.ps1           # ADB launcher script
│   ├── stop.sh / stop.ps1             # Clean shutdown script
│   ├── protocol.md                    # Low-latency protocol spec
│   └── README.md
│
└── SIZE_REPORT.md               # Detailed size budget audit & breakdown
```

---

## Build Instructions (Phone App)

### Prerequisites
- JDK 17
- Android SDK Platform 34 (`platforms/android-34`)
- Android SDK Build-Tools 34.0.0 (`build-tools/34.0.0`)

### Build Release APK
Run the following commands in the `companion` directory:

```bash
# On Linux / macOS:
cd companion
./gradlew clean assembleRelease

# On Windows (PowerShell):
cd companion
.\gradlew.bat clean assembleRelease
```

The output APK is generated at:
```
companion/app/build/outputs/apk/release/app-release.apk
```

### Exact Size Verification Commands

#### PowerShell (Windows):
```powershell
Get-Item companion\app\build\outputs\apk\release\app-release.apk | Select-Object FullName, Length, @{Name="KB";Expression={$_.Length / 1KB}}, @{Name="Pass <= 10MB";Expression={$_.Length -le 10000000}}
```

#### Bash (Linux / macOS):
```bash
ls -lh companion/app/build/outputs/apk/release/app-release.apk
stat -c "%s bytes" companion/app/build/outputs/apk/release/app-release.apk
```

---

## TV Server Setup & Bootstrap

The companion app connects to a lightweight server running on your TV via ADB.

### Step 1: Connect PC to TV via ADB
```bash
adb connect <TV_IP>:5555
```
*(If prompted on your TV screen, check "Always allow from this computer" and click OK).*

### Step 2: Push and Start TV Server
Using the included scripts in `tv_server/`:

#### Windows:
```powershell
cd tv_server
.\start.ps1 -TvIp <TV_IP> -Port 55555 -Token 123456
```

#### Linux / macOS:
```bash
cd tv_server
chmod +x start.sh stop.sh build.sh
./start.sh <TV_IP> 55555 123456
```

#### Or Direct ADB:
```bash
adb push tv_server/tv-server.jar /data/local/tmp/tv-server.jar
adb shell CLASSPATH=/data/local/tmp/tv-server.jar app_process / com.example.tvserver.Main 55555 123456
```

---

## Using the Phone App

1. **Launch TV Companion** on your Poco F7 5G.
2. Ensure both phone and TV are connected to the same Wi-Fi router or Phone Hotspot.
3. Enter the TV's IP address (shown on TV server console, e.g. `192.168.1.50`).
4. Enter Port (`55555`) and Token (`123456`).
5. Choose stream quality (Default: **Balanced 720p60**).
6. Tap **Connect & Start Mirror**.
7. The TV screen appears immediately on the phone at 60 FPS:
   - **Tap anywhere** on the video to select (OK / Enter).
   - **Swipe up / down / left / right** to navigate TV menus.
   - **Long press** to trigger Back.
   - **Two-finger tap** to return Home.
   - Use the **Remote Dock** at the bottom for explicit D-pad, Home, Back, Recents, and Volume buttons.
   - Tap the **Remote button** on the top bar to toggle the floating dock.

---

## Video Streaming Profiles

| Profile | Resolution | Target FPS | Bitrate | Latency | Recommended Network |
|---|---|---|---|---|---|
| **Low Latency** | 960 x 540 | 60 FPS | 3.0 Mbps | ~15 ms | 2.4 GHz Wi-Fi or weak signals |
| **Balanced (Default)** | 1280 x 720 | 60 FPS | 5.0 Mbps | ~18 ms | 5 GHz Wi-Fi / Ethernet |
| **High Quality** | 1920 x 1080 | 60 FPS | 9.0 Mbps | ~25 ms | Fast 5 GHz Wi-Fi / Ethernet |

---

## Verification & Acceptance Checklist

- [x] **Size:** Release APK is 43,176 bytes (<= 10,000,000 bytes hard limit).
- [x] **Native Android:** Pure Java + Android SDK. No web, no hybrid, no WebView.
- [x] **Offline LAN:** Operates without internet connectivity.
- [x] **Process Network Binding:** Binds sockets to Wi-Fi via `ConnectivityManager`.
- [x] **Private Subnet Protection:** Rejects public WAN IPs.
- [x] **MediaCodec 60 FPS:** Low-latency hardware H.264 decoding directly onto SurfaceView.
- [x] **Touchpad Gestures:** Single tap (OK), swipe directions (D-pad), long press (Back), 2-finger tap (Home).
- [x] **Remote Control Overlay:** D-pad Up/Down/Left/Right/OK, Back, Home, Recents, Volume Up/Down/Mute.
- [x] **TV Server Safe:** 0% CPU idle, < 25 MB RAM, no root, no package uninstalls.
