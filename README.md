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
- Remote: play/pause, ±10 s, seek bar, stop, speed, volume, "Cast a link" (direct http/https media URL), share links from other apps into UniversalCast.
- **Browser** (Home → Browser, or share a web page into the app): for sites without a Cast button. Play the video,
  tap "▶ N videos found – Cast", pick the stream and subtitles, cast. The TV plays HLS/DASH/MP4 directly from the CDN
  with the page's Referer/Origin/User-Agent/cookies. DRM-protected streams are detected and can't be cast.
  Built-in ad blocker (⋮ menu), pop-ups and "new tab" links stay in the browser, ← only goes back, Back twice closes.
- Observer-role phones see what is playing but cannot control it; the UI says so.
- Automatic reconnect with backoff, heartbeat, clear messages for revoked / unknown / changed-identity TVs.

## TV app features
- Clean player: only video, subtitles, a buffering spinner and error messages; no on-screen controls (the phone is
  the remote). Back stops playback; the remote's play/pause key toggles playback.
- Starts when the TV turns on (setting, default on) and comes to the front when a phone casts (needs "Display over
  other apps"; see BUILD.md), then goes back to what the TV showed when playback ends.

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
