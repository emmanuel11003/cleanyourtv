# TV-Side Companion Mirror Server

Lightweight, offline LAN screen mirroring and input injection server designed specifically for the **Haier 55P7GT-P (MediaTek MT9676, Android 14 API 34)** Google TV running **CleanYourTV**.

---

## Technical Overview

- **Host Device:** Haier 55P7GT-P (Codename `omonia`, Board `HR9676EUF`)
- **Memory Footprint:** < 25 MB RAM (Safe for TV's 2 GB RAM and aggressive low-memory killer)
- **CPU Footprint:** 0% CPU when disconnected; < 5% CPU when streaming (uses MediaTek hardware AVC encoder)
- **Privileges:** Pure unprivileged Android shell UID (2000) via ADB; **NO ROOT** required.
- **Safety:** Zero system modifications, zero package uninstalls, zero persistence after reboot.

---

## Prerequisites

1. **Enable Developer Options on TV:**
   - Go to `Settings > System > About > Android TV OS build`.
   - Click `Build` 7 times until you see *"You are now a developer!"*.
2. **Enable USB / Network Debugging:**
   - Go to `Settings > System > Developer options`.
   - Set **USB debugging** to **ON**.
   - If using Wireless Debugging (Android 11+): Enable **Wireless debugging**.
3. **Connect TV and Phone to Same LAN:**
   - Connect both to your home Wi-Fi router or Phone Wi-Fi Hotspot.
   - **Internet is NOT required.**

---

## Quick Start

### Option A: Using Helper Scripts (Recommended)

#### On Windows (PowerShell):
```powershell
# In tv_server directory:
.\start.ps1 -TvIp 192.168.1.50 -Port 55555 -Token 123456
```

To run in the background on the TV:
```powershell
.\start.ps1 -TvIp 192.168.1.50 -Port 55555 -Token 123456 -Background
```

#### On Linux / macOS (Bash):
```bash
# In tv_server directory:
chmod +x start.sh stop.sh build.sh
./start.sh 192.168.1.50 55555 123456
```

---

### Option B: Direct ADB Commands

You can execute the server directly using standard ADB commands from any terminal:

```bash
# 1. Connect to TV over ADB (port 5555)
adb connect 192.168.1.50:5555

# If using Android Wireless Debugging pairing:
# adb pair 192.168.1.50:PAIRING_PORT
# adb connect 192.168.1.50:CONNECT_PORT

# 2. Push compiled server JAR to TV temporary storage
adb push tv-server.jar /data/local/tmp/tv-server.jar

# 3. Launch server process under Android runtime
adb shell CLASSPATH=/data/local/tmp/tv-server.jar app_process / com.example.tvserver.Main 55555 123456
```

---

## Stopping the Server

When you are done, stop the server and release all hardware resources:

#### PowerShell:
```powershell
.\stop.ps1 -TvIp 192.168.1.50
```

#### Bash:
```bash
./stop.sh 192.168.1.50
```

#### Manual ADB:
```bash
adb shell "pkill -f com.example.tvserver.Main; pkill -f screenrecord"
```

---

## Security & Token Configuration

- **Default Token:** `123456`
- **Custom Token:** Pass any custom alphanumeric token as argument 2:
  ```bash
  adb shell CLASSPATH=/data/local/tmp/tv-server.jar app_process / com.example.tvserver.Main 55555 MySecretPass
  ```
- **Network Validation:** The server automatically inspects incoming connections and rejects any request from outside private RFC 1918 LAN subnets (`192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`, `169.254.x.x`).

---

## Rebuilding `tv-server.jar`

If you modify the Java source code in `src/`:

```powershell
# Windows
.\build.ps1

# Linux / macOS
./build.sh
```
The script compiles using Java 17 and `d8` from Android SDK build-tools to produce a compact ~7.6 KB DEX jar.
