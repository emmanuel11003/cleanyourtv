# CleanYourTV launcher — Version 6.1

`com.gathin.tvhome`, versionCode 18, versionName 6.1. Built from `D:\emmanuel\new tv app\launcher` as of
2026-11-12, with every v6.0 optimization and the glass design kept.

| | v6.0 | v6.1 |
|---|---|---|
| Release APK | 587,363 B | **566,863 B** (−20,500 B, −3.5%) |
| 576 KB size gate (589,824 B) | under by 2,461 B | **under by 22,961 B** |

Checks: 349 unit tests pass, lint reports 0 errors, zipalign check passes, v2-only signature verifies.
**Not yet tried on a TV or the emulator.**

## Maximum packing (lossless)

Same 98 files, same code (classes.dex is still 463,668 B unpacked), same resources and translations:
only the packing is tighter.

- **Zopfli recompression** (`zipalign -z`): every compressed file is re-deflated with Zopfli. It's still
  standard deflate, so Android reads it as before. classes.dex packs to 227,159 B (was 238,396 B).
- **Shared resource values** (`aapt2 optimize --deduplicate-entry-values`): identical values (the same
  colour, bool or translated string) are stored once. resources.arsc is 226,556 B (was 232,460 B) and
  stays uncompressed and aligned, as Android 11+ requires.
- Tried and left out: sparse resource encoding (saved nothing here, and makes lookups slower).

## Design alignment pass

- **Popup lists** (Inputs, Module cards, App Library menu): an even 4dp gap between the glass rows, so
  their outlines no longer touch. Row corners are 14dp, which follows the panel's 20dp corners and 6dp padding.
- **Quick Settings tiles:** 12dp corners, which follow the panel's 26dp corners and 14dp padding.
- **Settings:** the last choice in the Columns, Rows, Dock and Clock rows has no right margin, so these
  rows line up with the full-width rows above them. Gaps between rows are an even 12dp, and section
  headers sit 10dp above their row.
- **Centred pills:** the first Recents action, the empty-Recents Home button and the App Library button
  no longer sit 5dp right of centre (the 10dp margin that separates pills in a row is dropped).
- **Module-card prompt:** Keep hidden / Show have even padding, so their text is centred.
- **Post-build:** the VCS-info and AGP-metadata files are removed (the TV never reads them). The v2 signing
  block pads the APK to a 4 KB boundary, so those 450 B kept the APK from growing by 4 KB.

## New in 6.1 (your changes from the repo)

- **Remote keys (MediaTek TV):** scan code 240 and keys 82, 83, 176, 206, 312, `KEYCODE_SETTINGS` and
  `KEYCODE_NOTIFICATION` open Quick Settings. Scan code 225 opens the TV app or the DVB tuner input. An
  app key whose target is missing on `com.mediatek.wwtv.tvcenter` falls back to the tuner input. New
  `panel` / `quick_settings` app-key action.
- **Live clock:** the Home and Quick Settings clocks keep ticking (they start in `onResume` and stop in
  `onPause`), and the Quick Settings date updates on every open.
- **Key services:** `InputKeyService` is exported (it can only be bound by the system) and starts at
  boot. Service repair always records both key services and is no longer cleared when a service
  disconnects.

## Kept from 6.0

R8 obfuscation and repackaging, the trimmed Outfit font, WebP images, de-duplicated translations,
collapsed resource names, v2-only signing, the size gate, the throttled usage-stats sync, and one glass
style on every element. See `V6_RELEASE_NOTES.md`.

## Files

- `tvhome-v6.1-release.apk`: install with `adb install -r tvhome-v6.1-release.apk`. It uses the same debug
  key, so it installs over 6.0 and 5.x.
- `tvhome-v6.1-mapping.txt`: for retracing crash traces.
