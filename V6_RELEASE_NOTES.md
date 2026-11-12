# CleanYourTV launcher — Version 6.0

`com.gathin.tvhome`, versionCode 17, versionName 6.0. Built from `D:\emmanuel\new tv app\launcher`
(v5.1 plus its uncommitted "last used" work), with the same features.

| | v5.1 | v6.0 |
|---|---|---|
| Release APK | 638,797 B | **587,363 B** (−51,434 B, −8.1%) |
| 576 KB size gate (589,824 B) | over by 48,973 B | **under by 2,461 B** |
| classes.dex (compressed) | 252,098 B | 238,213 B |
| resources.arsc | 251,596 B | ~231 KB |
| Outfit font (compressed) | 56,074 B | 41,479 B |

Checks: 349 unit tests pass, lint reports 0 errors, v2 signature verifies. Smoke-tested on the
Android TV 14 emulator: Home, D-pad moves, Quick Settings, Recents and Settings open with no crash.
Home uses about 30 MB PSS. **Not yet tried on a real TV.**

## What changed

- **R8 obfuscation + repackaging** (`app/proguard-rules.pro`). The repo used `-dontobfuscate`; v6
  renames classes and keeps line numbers. To read a crash trace, use `tvhome-v6.0-mapping.txt`:
  `retrace tvhome-v6.0-mapping.txt trace.txt`. Debug and info `Log` calls are stripped.
- **Font trimmed to the weights the launcher uses.** The `wght` axis goes from 100–900 to 100–500,
  and glyph names are dropped. Glyphs at 200/300/400/500 are unchanged except 3 glyphs off by
  ≤0.5 font units out of 1000, far below a pixel.
- **Launcher icon and default banner: JPEG → WebP q90** (4.5 KB→1.3 KB, 3.4 KB→1.0 KB); they look
  the same.
- **314 translated strings removed** where the text was identical to their fallback (parent locale or
  `values/`), e.g. 93 in `values-b+es+419`. Every lookup returns the same text.
- **Post-build pass** (`optimizeReleaseApk`, runs after `assembleRelease`). aapt2 collapses
  resource names, except the `banner_*` drawables that the code looks them up by name. Then
  zipalign, and v2-only signing (minSdk 26 never reads the v1 JAR signature). The build fails if
  the APK reaches the size gate.
- **No dependency report, BuildConfig, AIDL or RenderScript** in the build.
- **`LastOpened` usage-stats sync:** at most once a minute, never two runs at once, background
  priority, and it uses the application context. Before, every return to Home started a thread
  that read 14 days of usage stats and held the Activity.

## Build

```powershell
cd launcher
.\gradlew.bat --no-daemon testReleaseUnitTest lintRelease assembleRelease
# -> app\build\outputs\apk\release\tvhome-v6.0-release.apk
```

Install: `adb install -r tvhome-v6.0-release.apk` (same debug key as earlier versions, so it
installs over v5.x).
