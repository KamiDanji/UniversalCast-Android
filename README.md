# UniversalCast Receiver (Android TV) — v1.1.0

Secure Android TV receiver and companion phone sender for the **UniversalCast Protocol (UCP) 1.0**. A paired sender (phone, tablet, PC…)
discovers the TV via mDNS, connects over pinned TLS WebSocket, authenticates with a signed challenge and then
controls Media3/ExoPlayer playback. Package `com.universalcast.receiver`, Kotlin, Gradle Kotlin DSL.

## Apps in this project
| Module | What it is |
|---|---|
| `:app` | **UniversalCast Receiver**: the Android TV app that plays videos, audio, and photo slideshows |
| `:sender-app` | **UniversalCast**: the Android phone app that pairs with a TV, browses/sniffs web streams, serves local files, and acts as a full TV remote |
| `:core` | Shared pure-Kotlin library: protocol, crypto, pairing, server (receiver), client logic (sender), HTTP media proxy, filter engine, and desktop CLI |
| `:updater` | Shared Android auto-updater library: checks GitHub Releases over HTTPS, verifies SHA-256 digests, parses Markdown release notes, and invokes Android's package installer |

Typical use: install the receiver on the TV, the sender on your phone, choose "Pair a new device" on the TV, scan its
QR code with the phone, compare the 6-digit code, approve on the TV, then cast a link, a website stream, or local phone media.

## Status — read this first
| Part | State |
|---|---|
| `:core` (protocol, crypto, pairing, auth, detached sessions, queue, tracks, remote input, server, persistence, sender controller, `MediaProxyServer`, `CastHistoryStore`/`BookmarkStore`, browser-cast + `FilterEngine`, CLI) | Compiled and tested: see `reports/TEST_REPORT.md` |
| `:app` (Android TV UI, Ambient clock, Keystore, NSD, Media3 + subtitle sync, OSD pill, remote key/text dispatcher, service, boot/auto-open, auto-updater) | Compiles (`assembleDebug` & `assembleRelease`); policy/OSD/formatting unit tests pass; behaviour on a real TV is **unverified** |
| `:sender-app` (phone UI, About & Dummy TV Simulator, Keystore, NSD, QR, browser + EasyList/uBlock filters, `ConnectionService` `MediaSession`, local media source, auto-updater) | Compiles (`assembleDebug` & `assembleRelease`); unit tests pass; behaviour on a real phone is **unverified** |
| `:updater` (GitHub release parser, version ordering, trusted hosts, Markdown parser, installer manager) | Compiles; unit tests pass; in-place install behaviour on physical devices is **unverified** |

What still needs a device is listed in `reports/FEATURE_REPORT.md` with a manual test checklist.

## Layout
- `core/` – pure JVM Kotlin library (no Android or Media3 types) plus `cli.UniversalCastCli` desktop sender.
- `app/` – Android TV application (Jetpack Compose UI on **TV Material 3** (`androidx.tv:tv-material`), Media3, Android Keystore, NSD, optional Accessibility remote service).
- `sender-app/` – Android phone application (Jetpack Compose UI on **Material 3** with dynamic colour, QR scanner, hardened WebView browser, lock-screen `MediaSession`, local `content://` streaming).
- `updater/` – Android library module for GitHub Releases OTA updates and Markdown release-note rendering.
- `reports/` – `FEATURE_REPORT.md`, `TEST_REPORT.md`, `SECURITY_VERIFICATION.md`, `KNOWN_LIMITATIONS.md`.
- Root docs – `README.md`, `BUILD.md`, `SECURITY.md`, `ARCHITECTURE.md`.

## Phone app features
- Pair by scanning the TV's QR code (or paste the code), compare the 6-digit code, wait for the TV user's approval.
- Paired TVs are remembered (encrypted); each connection is pinned to the TV's key, whatever IP it has.
- Finds TVs on the Wi-Fi via mDNS and uses their current address; falls back to the remembered addresses.
- Remote: play/pause, ±10 s, seek bar, stop, speed, volume, **Aspect Ratio / Zoom** (`Fit`, `Zoom (Crop)`, `Stretch 16:9`), **Sleep Timer** (`15m`–`90m` or `Off`), "Cast a link" (direct http/https media URL), **Cast Local Videos / Music / Photos** (multi-file selection from phone storage via the capability-token-authenticated HTTP range proxy), share links from other apps into UniversalCast.
- **Google TV-style Remote, Touchpad & Keyboard**: switch to the **TV Remote & Touchpad** tab for swipe gesture navigation (swipe for D-pad, tap for OK, long-press for Back, horizontal slide to scrub video), classic D-pad mode, **Phone Keyboard (`⌨`)** to type text directly onto the TV, plus Back, Home, Mute, Play/Pause and TV Volume +/- keys.
- **Live Audio & Subtitle Track Switching**: switch audio dubs and subtitle tracks mid-playback, shift real-time subtitle timing offset (±0.5 s), and customize subtitle size, background opacity and bottom margin live from the phone.
- **Up Next Queue & Auto-Play**: enqueue multiple videos, music tracks, photos, or website streams (`+ Queue`), reorder/jump/skip/remove items, with automatic advancement when an item ends.
- **Media Notification & Lock Screen Controls**: Android `MediaSession` notification with lock-screen transport controls, live seek bar, and hardware volume button control while casting (`VolumeProvider`).
- **Resume History & Bookmarks**: automatically remembers playback positions for 1-tap resume, plus bookmarks and recent video pages in the Cast Browser.
- **Browser** (Home → Browser, or share a web page into the app): for sites without a Cast button. Play the video,
  tap "▶ N videos found – Cast", pick the stream and subtitles, cast or add to queue. Includes **Phone Proxy Mode** to tunnel IP-locked / HTTP 403 HLS & MP4 streams through the phone's WebView session (plus a 1-tap **"Retry via Phone Proxy"** action on 401/403 errors).
  Ad blocker combines the built-in domain list (`AdBlocker`) with cached uBlock/EasyList filter lists + cosmetic CSS hiding (`FilterEngine` / `FilterLists`), manages bookmarks, keeps pop-ups and "new tab" links inside the browser, ← only goes back, Back twice closes.
- **About & Developer Dummy TV Simulator** (`⋮` → *About UniversalCast*): shows version/build info, developer credit, and live runtime/security stats; spam-tap the **Version** card 5 times to unlock and connect to an in-process **Dummy TV Simulator** for testing all remote, touchpad, track, queue, and browser controls without a physical TV.
- **GitHub Releases Auto-Updater**: checks GitHub's latest release, renders **Markdown** release notes cleanly in the update dialog, verifies the asset SHA-256 digest, and launches Android's installer.
- Observer-role phones see what is playing but cannot control it; the UI says so.
- Automatic reconnect with backoff, heartbeat, **detached playback** (the TV keeps playing during a 30-minute grace period if the phone sleeps or disconnects, and re-attaches seamlessly), clear messages for revoked / unknown / changed-identity TVs.

## TV app features
- Clean player: video, customizable subtitles, **Real-Time Subtitle Sync** (Media3 `TextRenderer` timestamp-shift proxy for ±0.5 s adjustments), **Full-Screen Photo Slideshows** (8-second timer for queued photos), **On-TV Audio Visualizer** for audio-only streams, **Aspect Ratio / Zoom Modes**, **Sleep Timer**, a buffering spinner, optional 2-second floating OSD status pills (for volume, speed, seek, aspect ratio, sleep timer, keyboard input, and track switches), and error messages; no on-screen controls (the phone is the remote). Back stops playback; the remote's play/pause key toggles playback.
- **Ambient Clock Screen**: sleek idle screen with burn-in-shifted live digital clock, date, receiver status badge and connected phones (auto-enters after 3 minutes of inactivity on Home when enabled).
- **Subtitle Styling**: customize subtitle size, background opacity and bottom margin in TV Settings or live from the phone.
- **Phone Remote Support**: responds to D-pad, OK, Back, Home, Media, Volume, and Keyboard text (`remote.text`) sent from the phone (works in-app, via clipboard/virtual keys, and for system volume out of the box; works system-wide across all TV apps when the optional `TvRemoteAccessibilityService` is enabled).
- **GitHub Releases Auto-Updater**: checks for new receiver releases on start / in Settings with formatted Markdown release notes.
- Starts when the TV turns on (setting, default on) and comes to the front when a phone casts (needs "Display over other apps"; see BUILD.md), then goes back to what the TV showed when playback ends.

## Features
- UCP 1.0: versioned envelopes, request ids, per-direction sequence numbers, sessions, error codes, capabilities, state sync events, duplicate-request protection, size limits (64 KiB frames/messages).
- Pairing: QR bootstrap (120 s window, one-time secret), sender proof, 6-digit SAS shown on TV, explicit approval with **Controller** or **Observer** role.
- WSS only: TLS 1.2/1.3, receiver certificate **fingerprint pinning** (no CA, no hostname trust), single-use challenge-response with transcript binding, replay protection, 10 s auth timeout.
- Revocation: persistent, closes live connections, ends active or detached sessions, stops playback, blocks reconnection.
- mDNS `_universalcast._tcp.local.` with TXT `ucp`, `id`, `path`, `tls`, `mode`.
- Dark TV UI: Home, Pairing, Paired devices (revoke), Player, Ambient clock screen, Settings/Diagnostics; D-pad focus rings and animated transitions/backdrop.
- Encrypted persistence: Keystore EC identity key (non-exportable) + AES-256-GCM encrypted, atomically written sender store with corruption recovery and migrations.
- `media.load` and UCP 1.0 extensions (backward compatible): allowlisted request `headers` and side-loaded `subtitles`; live track selection, subtitle offset/style, video scale, sleep timer, queue management, and remote key/text input.
  `capabilities.get` advertises `features: ["session", "snapshot", "events", "seek", "speed", "volume", "media-headers", "subtitles", "tracks", "subtitle-style", "queue", "remote-input", "detached-playback"]`, `playbackMethods: ["direct-url"]`.
- **Desktop CLI Sender (`com.universalcast.cli.UniversalCastCli`)**: pure-JVM CLI in `:core` for pairing, casting links, streaming local computer files via `MediaProxyServer`, sending remote keys/text, checking status, and stopping playback.

## Quick start
```
./gradlew test                        # all JVM + Android unit tests (:core, :app, :sender-app, :updater)
./gradlew :core:test                  # JVM protocol/security/proxy tests (counts in reports/TEST_REPORT.md)
./gradlew :app:assembleDebug          # TV receiver  -> app/build/outputs/apk/debug
./gradlew :sender-app:assembleDebug   # phone app    -> sender-app/build/outputs/apk/debug
```
