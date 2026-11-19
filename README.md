# UniversalCast Receiver (Android TV)

Secure Android TV receiver for the **UniversalCast Protocol (UCP) 1.0**. A paired sender (phone, tablet, PC…)
discovers the TV via mDNS, connects over pinned TLS WebSocket, authenticates with a signed challenge and then
controls Media3/ExoPlayer playback. Package `com.universalcast.receiver`, Kotlin, Gradle Kotlin DSL.

## Apps in this project
| Module | What it is |
|---|---|
| `:app` | **UniversalCast Receiver**: the Android TV app that plays media |
| `:sender-app` | **UniversalCast**: the Android phone app that pairs with a TV and controls it |
| `:core` | Shared pure-Kotlin library: protocol, crypto, pairing, server (receiver) and client logic (sender) |

Typical use: install the receiver on the TV, the sender on your phone, choose "Pair a new device" on the TV, scan its
QR code with the phone, compare the 6-digit code, approve on the TV, then cast a link.

## Status — read this first
| Part | State |
|---|---|
| `:core` (protocol, crypto, pairing, auth, sessions, server, persistence, sender controller, browser-cast logic) | Compiled and tested: see `reports/TEST_REPORT.md` |
| `:app` (Android TV UI, Keystore, NSD, Media3, service, boot/auto-open) | Compiles (`assembleDebug`); policy unit tests pass; behaviour on a real TV is **unverified** |
| `:sender-app` (phone UI, Keystore, NSD, QR, browser) | Compiles (`assembleDebug`); unit tests pass; behaviour on a real phone is **unverified** |

What still needs a device is listed in `reports/FEATURE_REPORT.md` with a manual test checklist.

## Layout
- `core/` – pure JVM Kotlin library. No Android or Media3 types, so everything security-relevant is unit/integration tested.
- `app/` – Android TV application (Jetpack Compose UI, Media3, Android Keystore, NSD).
- `docs/`, `reports/`, `README.md`, `BUILD.md`, `SECURITY.md`, `ARCHITECTURE.md`.

## Phone app features
- Pair by scanning the TV's QR code (or paste the code), compare the 6-digit code, wait for the TV user's approval.
- Paired TVs are remembered (encrypted); each connection is pinned to the TV's key, whatever IP it has.
- Finds TVs on the Wi-Fi via mDNS and uses their current address; falls back to the remembered addresses.
- Remote: play/pause, ±10 s, seek bar, stop, speed, volume, **Aspect Ratio / Zoom** (`Fit`, `Zoom / Crop`, `Stretch 16:9`), **Sleep Timer** (`15m`–`90m`), "Cast a link" (direct http/https media URL), **Cast Local Videos / Music / Photos** (multi-file selection from phone storage via the authenticated phone-side HTTP range proxy), share links from other apps into UniversalCast.
- **Google TV-style Remote, Touchpad & Keyboard**: switch to the **TV Remote & Touchpad** tab for swipe gesture navigation (swipe for D-pad, tap for OK, long-press for Back, horizontal slide to scrub video), classic D-pad mode, **Phone Keyboard (`⌨`)** to type text directly onto the TV, plus Back, Home, Mute, Play/Pause and TV Volume +/- keys.
- **Live Audio & Subtitle Track Switching**: switch audio dubs and subtitle tracks mid-playback, shift real-time subtitle timing offset (±0.5 s), and customize subtitle size, background opacity and bottom margin live from the phone.
- **Up Next Queue & Auto-Play**: enqueue multiple videos or website streams (`+ Queue`), reorder/skip/remove items, with automatic advancement when an episode ends.
- **Media Notification & Lock Screen Controls**: Android `MediaSession` notification with lock-screen transport controls, live seek bar, and hardware volume button control while casting.
- **Resume History & Bookmarks**: automatically remembers playback positions for 1-tap resume, plus bookmarks and recent video pages in the Cast Browser.
- **Browser** (Home → Browser, or share a web page into the app): for sites without a Cast button. Play the video,
  tap "▶ N videos found – Cast", pick the stream and subtitles, cast or add to queue. Includes **Phone Proxy Mode** to tunnel IP-locked / HTTP 403 HLS & MP4 streams through the phone's WebView session.
  Built-in ad blocker (⋮ menu), bookmarks, pop-ups and "new tab" links stay in the browser, ← only goes back, Back twice closes.
- Observer-role phones see what is playing but cannot control it; the UI says so.
- Automatic reconnect with backoff, heartbeat, **detached playback** (the TV keeps playing if the phone sleeps or disconnects, and re-attaches seamlessly), clear messages for revoked / unknown / changed-identity TVs.

## TV app features
- Clean player: video, customizable subtitles, a buffering spinner, optional 2-second floating OSD status pills (for volume, speed, seek, and track switches), and error messages; no on-screen controls (the phone is the remote). Back stops playback; the remote's play/pause key toggles playback.
- **Ambient Clock Screen**: sleek idle screen with live digital clock, date, receiver status badge and connected phones (with optional auto-ambient after 3 minutes of idle).
- **Subtitle Styling**: customize subtitle size, background opacity and bottom margin in TV Settings or from the phone.
- **Phone Remote Support**: responds to D-pad, OK, Back, Home, Media and Volume keys sent from the phone (works in-app and for system volume out of the box, and system-wide across all TV apps when the optional Accessibility service is enabled).
- Starts when the TV turns on (setting, default on) and comes to the front when a phone casts (needs "Display over other apps"; see BUILD.md), then goes back to what the TV showed when playback ends.

## Features
- UCP 1.0: versioned envelopes, request ids, per-direction sequence numbers, sessions, error codes, capabilities, state sync events, duplicate-request protection, size limits (64 KiB frames/messages).
- Pairing: QR bootstrap (120 s window, one-time secret), sender proof, 6-digit SAS shown on TV, explicit approval with **Controller** or **Observer** role.
- WSS only: TLS 1.2/1.3, receiver certificate **fingerprint pinning** (no CA, no hostname trust), single-use challenge-response with transcript binding, replay protection, 10 s auth timeout.
- Revocation: persistent, closes live connections, stops playback, blocks reconnection.
- mDNS `_universalcast._tcp.local.` with TXT `ucp`, `id`, `path`, `tls`, `mode`.
- Dark TV UI: Home, Pairing, Paired devices (revoke), Player, Settings/Diagnostics; D-pad focus rings.
- Encrypted persistence: Keystore EC identity key (non-exportable) + AES-256-GCM encrypted, atomically written sender store with corruption recovery and migrations.
- `media.load` extensions (UCP 1.0, backward compatible): allowlisted request `headers` and side-loaded `subtitles`;
  `capabilities.get` advertises `features: [..., "media-headers", "subtitles"]`, `playbackMethods: ["direct-url"]`.

## Quick start
```
./gradlew :core:test            # JVM tests (counts in reports/TEST_REPORT.md)
./gradlew :app:assembleDebug          # TV receiver  -> app/build/outputs/apk/debug
./gradlew :sender-app:assembleDebug   # phone app    -> sender-app/build/outputs/apk/debug
```
