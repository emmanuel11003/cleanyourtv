# Wallpaper Sync

Change your TV launcher's **Home** and **Screensaver** wallpapers over Wi-Fi from any phone or laptop browser — no ADB required.

On the TV, **Wallpaper Sync** opens a small local HTTP server on port `8765`, displays a QR code and a 4-digit pairing PIN, and lets you upload JPG, PNG, or WebP images straight to the launcher (`com.gathin.tvhome`) via its local `WallpaperBridgeProvider`.

## Features

- **No ADB needed** once both APKs are installed on the TV.
- **Built-in Web UI** — scan the QR code on your TV screen or open `http://<tv-ip>:8765` in any browser on the same Wi-Fi.
- **Separate Home & Screensaver wallpapers** — set Home only, Screensaver only, or both at once, or reset either back to the theme default.
- **4-digit Pairing PIN** — prevents other devices on the network from changing your TV's wallpaper without looking at the TV.
- **Client-side resizing** — large phone photos (> 10 MB) are automatically scaled to 4K JPEG in your browser before upload.

## Permissions

- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`: runs the local Wi-Fi HTTP server and finds the TV's LAN IP. Nothing leaves your local network.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`: keeps the Wi-Fi server alive while you switch back to Home to preview your wallpaper.
- `com.gathin.tvhome.permission.WALLPAPER_SYNC`: local IPC permission to write wallpapers to `com.gathin.tvhome`'s `WallpaperBridgeProvider`.

## Build

Requires JDK 17 and Android SDK 34:

```powershell
cd addons\wallpaper-sync
.\gradlew.bat testReleaseUnitTest lintRelease assembleRelease
```

The release APK is written to `addons/wallpaper-sync/app/build/outputs/apk/release/app-release.apk`.
