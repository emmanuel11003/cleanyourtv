# TV Companion Mirror Protocol v1.0

This document defines the offline LAN communication protocol between the Phone Companion App (Poco F7 5G) and the TV Server running on the Haier 55P7GT-P (MediaTek MT9676).

---

## 1. Transport & Architecture

- **Transport:** Single full-duplex TCP connection.
- **Port:** Default `55555` (configurable).
- **Socket Options:** `TCP_NODELAY = true` (disables Nagle's algorithm for sub-30ms latency), `SO_KEEPALIVE = true`.
- **Directionality:**
  - **Downstream (TV -> Phone):** Handshake response JSON line, followed immediately by continuous H.264 / AVC Annex-B video stream.
  - **Upstream (Phone -> TV):** Handshake initiation JSON line, followed by asynchronous JSON control commands (remote keys, touch gestures, text).

---

## 2. Handshake Phase

### Client Request (Phone -> TV)
Sent immediately upon socket connection as a single newline-terminated UTF-8 JSON line:

```json
{"t":"hello","token":"123456","profile":"720p60","fps":60}
```

| Field | Type | Description |
|---|---|---|
| `t` | string | Constant `"hello"` |
| `token` | string | 6-character local authentication token |
| `profile` | string | Desired stream quality: `"540p60"`, `"720p60"`, or `"1080p60"` |
| `fps` | integer | Target framerate (default `60`) |

### Server Response (TV -> Phone)

#### Success:
```json
{"ok":true,"width":1280,"height":720,"fps":60,"refresh_hz":60,"token_ok":true}
```

| Field | Type | Description |
|---|---|---|
| `ok` | boolean | `true` if server initialized hardware video encoder |
| `width` | integer | Actual stream width (e.g., `1280` or `960`) |
| `height` | integer | Actual stream height (e.g., `720` or `540`) |
| `fps` | integer | Target framerate (`60`) |
| `refresh_hz` | integer | Native panel refresh rate (`60`) |
| `token_ok` | boolean | `true` confirming token authentication succeeded |

#### Failure (Invalid Token):
```json
{"ok":false,"error":"Invalid security token","token_ok":false}
```
*Note: Socket is immediately closed by server upon authentication failure.*

---

## 3. Downstream Video Pipeline (TV -> Phone)

Immediately following the handshake response newline `\n`, the TV writes the raw **H.264 / AVC Annex B** stream directly to the TCP socket.

### Framing & NAL Units
The stream uses standard Annex-B delimiters:
- 4-byte start code: `0x00 0x00 0x00 0x01`
- 3-byte start code: `0x00 0x00 0x01`

```
+----------------+---------------+----------------+---------------+
| 00 00 00 01 67 | SPS Config    | 00 00 00 01 68 | PPS Config    |
+----------------+---------------+----------------+---------------+
| 00 00 00 01 65 | IDR Keyframe  | 00 00 00 01 41 | P-Frame Slice |
+----------------+---------------+----------------+---------------+
```

The phone app parses NAL units using `NalParser` and directly queues them into Android `MediaCodec` hardware decoder in low-latency mode (`KEY_LOW_LATENCY = 1`).

---

## 4. Upstream Remote Control Commands (Phone -> TV)

All control messages sent from phone to TV are compact, newline-terminated JSON strings.

### 4.1. Key Event Command
Sent when a remote button or D-pad direction is pressed:
```json
{"t":"key","code":19}
```

#### Supported Android Keycodes:
| Keycode | Value | Action |
|---|---|---|
| `KEYCODE_HOME` | 3 | Return to CleanYourTV home screen |
| `KEYCODE_BACK` | 4 | Back navigation |
| `KEYCODE_DPAD_UP` | 19 | Navigate Up |
| `KEYCODE_DPAD_DOWN` | 20 | Navigate Down |
| `KEYCODE_DPAD_LEFT` | 21 | Navigate Left |
| `KEYCODE_DPAD_RIGHT` | 22 | Navigate Right |
| `KEYCODE_ENTER` | 66 | Select / OK |
| `KEYCODE_VOLUME_UP` | 24 | Increase Volume |
| `KEYCODE_VOLUME_DOWN` | 25 | Decrease Volume |
| `KEYCODE_VOLUME_MUTE` | 164 | Toggle Audio Mute |
| `KEYCODE_APP_SWITCH` | 187 | Recents / App Switcher |
| `KEYCODE_MENU` | 82 | Context Menu |

### 4.2. Touchpad Tap Command
Sent when user single-taps the video surface:
```json
{"t":"tap"}
```
*Server injects `KEYCODE_ENTER` (66) to select the currently focused item on TV.*

### 4.3. Touchpad Swipe Command
Sent when user swipes on the phone screen:
```json
{"t":"swipe","dir":"up"}
```
*Values for `dir`: `"up"`, `"down"`, `"left"`, `"right"`.*

### 4.4. Text Injection Command
Sent for remote keyboard input:
```json
{"t":"text","value":"YouTube"}
```

---

## 5. Security & Isolation

1. **Subnet Filtering:** The server inspects `socket.getInetAddress()` upon connection and immediately drops any non-private IPv4 address (enforcing RFC 1918 subnets: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, and 169.254.0.0/16).
2. **Token Authentication:** The 6-character token prevents unauthorized peers on the local LAN from connecting or controlling the TV.
3. **No Root / Sandboxed:** All injection and streaming run under Android shell UID (2000). Zero root, zero system modifications.
