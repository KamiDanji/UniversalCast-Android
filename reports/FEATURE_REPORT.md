# Feature implementation report

Legend: ✅ implemented and verified by automated tests · 🟢 implemented, compiles (`assembleDebug` / `assembleRelease`),
logic unit-tested, **device behaviour unverified** · 🟡 implemented, compiles, **unverified** (needs a TV/phone)

| Area | State |
|---|---|
| UCP 1.0 envelopes, ids, seq, errors, capabilities, state sync, duplicates, size limits | ✅ |
| Pairing: QR, 120 s window, sender proof, SAS, approval, roles | ✅ (logic) · 🟡 (UI) |
| WSS, TLS 1.2/1.3, fingerprint pinning, challenge-response, replay, auth timeout | ✅ |
| Revocation (persist, disconnect, end active/detached session, stop playback, block) | ✅ |
| Phone: connection, commands, events, reconnect, blackhole TLS handshake timeout (logic in `:core`) | ✅ |
| Phone: disconnect while commands are queued (`heartbeatAndCommandsNeverCollideOnSequenceNumbers`) | ✅ regression test + stress run |
| **Detached playback** (30 min session grace period, seamless Controller resume on reconnect, revocation override) | ✅ |
| Receiver: stalled responses (Java-WebSocket write race) re-armed by `UcpServer` watchdog | ✅ stress run (see TEST_REPORT) |
| **Phone-side HTTP Media Proxy** (`MediaProxyServer`: capability tokens, byte-range `206` local files, HLS `.m3u8` rewriting, 403/IP-locked proxy mode) | ✅ (`MediaProxyServer` tests) · 🟡 (device playback & `ContentUriMediaSource`) |
| **Live audio & subtitle track switching + real-time subtitle sync + styling** (`playback.selectTracks`, `playback.setSubtitleOffset`, `playback.setSubtitleStyle`, `TextRenderer` proxy) | ✅ (protocol/state) · 🟡 (ExoPlayer `TextRenderer` on TV) |
| **Up Next queue & auto-play** (`PlaybackQueue`, `queue.add/remove/reorder/clear/next/previous/jump`) | ✅ (protocol/queue) · 🟡 (TV playback auto-advance) |
| **TV player without on-screen controls** (video, photo slideshows, audio visualizer, zoom/scale, sleep timer, optional 2s OSD pill, error only; immersive; Back stops; media keys toggle) | 🟢 key/OSD policies tested · 🟡 on a TV |
| **Ambient clock screen** (`AmbientScreen`: burn-in shift, clock/date, connected badges, 3 min auto-idle) | 🟡 (on a TV) |
| **Google TV-style phone remote, touchpad, D-Pad & keyboard** (`remote.key`, `remote.text`, `TvRemoteDispatcher`, optional `TvRemoteAccessibilityService`) | ✅ (protocol/auth) · 🟡 (TV input & Accessibility) |
| **Phone lock-screen `MediaSession` + hardware volume** (`ConnectionService` notification actions, seek bar, `VolumeProvider`) | 🟡 (on a phone) |
| **Resume history & browser bookmarks** (`CastHistoryStore`, `BookmarkStore`) | ✅ (persistence/resume) · 🟡 (UI) |
| **Start when the TV turns on** (`BootReceiver`, `connectedDevice` FGS, setting, `shouldAutostart`) | 🟢 `shouldAutostart` tested · 🟡 on a TV |
| **Open automatically when a phone casts** (`AutoLaunchPolicy`, `CastStartDetector`, overlay anchor, settings row, adb fallback, wake + move-to-back) | 🟢 policy/detector tested · 🟡 on a TV |
| **`media.load.headers` / `subtitles`**, validation, `features: [...]` | ✅ |
| Receiver per-request headers, Cookie host-scoped (incl. redirects), subtitles through the same HTTP stack, English preferred | 🟡 (Media3/OkHttp on device) |
| **Sender browser-cast logic in `:core`**: `MediaSniffer`, `CandidateList`, `StreamProbe` (+ DRM), `CastHeaders`, `BrowserCast`, `PageScan`, `SenderController.load(MediaCast)` | ✅ |
| **Phone browser** (`BrowserScreen`: hardened WebView, detection, Scan page, candidates sheet, subtitles, proxy switch, `+ Queue`, TV choice, pause page, remote overlay) | 🟢 URL normalisation tested · 🟡 on a phone |
| **Browser ad blocker + cosmetic CSS + pop-ups + Back** (`AdBlocker` + `FilterEngine` EasyList/uBlock lists in `:core`, `FilterLists`, menu toggle, Back-twice to close) | ✅ (`AdBlocker` + `FilterEngine`) · 🟡 on a phone |
| **About screen & Developer Dummy TV Simulator** (`AboutScreen`, `SenderController.startDummyMode`) | ✅ (`SenderController` dummy tests) · 🟡 (UI) |
| **GitHub Releases auto-updater & Markdown release notes** (`:updater`: `ReleaseParser`, `TrustedHosts`, `MarkdownParser`, `UpdateManager`) | ✅ (parser/Markdown/hosts) · 🟡 (OTA APK install on device) |
| **Desktop / CLI sender** (`com.universalcast.cli.UniversalCastCli` in `:core`) | 🟢 compiles (`:core:jar`) · manual run unverified |
| "Cast a link" field, share intent, local file picker (`video/*`, `audio/*`, `image/*`) | 🟡 (compiles) |

## UNVERIFIED — manual test checklist (TV + phone)

Use debug builds (`com.universalcast.receiver.debug`, `com.universalcast.sender.debug`), phone and TV on the same Wi-Fi (or release builds for OTA auto-updater checks).

**TV player & OSD / Ambient (Phase 1)**
1. Cast an MP4 from the phone. On the TV you must see only the video (plus optional 2-second top-right OSD status pills when toggling play/pause, volume, speed, tracks, aspect ratio, or seeking).
2. While it starts, a small spinner is centred; it disappears once the video plays. System bars are hidden.
3. Cast a broken link (e.g. `https://example.org/nope.mp4`): a centred "Playback problem" message appears.
4. D-pad left/right/up/down/OK on the TV physical remote: nothing happens during playback, no focus highlight appears.
5. Remote play/pause key: pauses/resumes silently; the phone's remote shows the new state.
6. Back on the remote: playback stops, Home screen returns, system bars come back.
7. Cast an HLS stream with multiple audio/subtitle tracks: switch audio dubs, toggle subtitles on/off, shift subtitle delay (`±0.5 s`), and adjust subtitle size/opacity/bottom margin live from the phone.
8. Test **Aspect ratio / zoom** (`Fit`, `Zoom (Crop)`, `Stretch 16:9`) and **Sleep timer** (set timer and verify badge/auto-stop).
9. Leave the TV Home screen idle for 3 minutes (or select *Ambient clock screen* on Home): verify clock/date screen appears and dismisses on any key.
10. Leave a video playing 15+ minutes: the TV must not dim or start the screensaver.

**Detached playback & Reconnect (Phase 2)**
11. Start casting a video, lock the phone or toggle airplane mode on the phone for 15 seconds, then reconnect: TV playback must continue uninterrupted and the phone remote must seamlessly regain control (`hasSession = true`).
12. While playing, revoke the phone in TV → *Paired devices*: TV playback stops immediately and the phone cannot reconnect.

**Start on boot & Auto-open on cast (Phase 3)**
13. Install, open the app once, close it (Home). Reboot the TV. Without opening the app, pair/connect from the phone: it connects. (Diagnostics shows "Auto-start after BOOT_COMPLETED".)
14. Settings → "Start when the TV turns on" → Off. Reboot. The phone can't connect until the app is opened.
15. Turn it back On. `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.universalcast.receiver.debug` starts the receiver.
16. Android 15 TVs only: `adb shell am compat enable FGS_BOOT_COMPLETED_RESTRICTIONS com.universalcast.receiver.debug`, reboot, repeat 13. No crash; diagnostics log has no foreground service restriction error.
17. Without overlay permission: TV on another app, receiver running in background, cast from phone → Diagnostics logs `Auto-open blocked: grant "Display over other apps"`. Grant it via Settings or `adb shell appops set com.universalcast.receiver.debug SYSTEM_ALERT_WINDOW allow`; repeat → player comes to front and returns to previous app when playback ends.

**Phone remote, Touchpad, Local media, Queue & Lock-screen controls (Phase 4)**
18. On Remote → **TV Remote & Touchpad**: swipe to navigate TV menus, tap for OK, long-press for Back, use the horizontal scrub strip, and send text via the **`⌨`** keyboard input. Verify TV Settings → *Open Accessibility settings* enables system-wide D-pad outside UniversalCast.
19. Pick a local video, an audio track (verify on-TV equalizer visualizer), and multiple photos (verify 8-second slideshow) via **Cast Videos / Music / Photos**; test seeking in local video (`206 Partial Content`).
20. Enqueue multiple links/files with **`+ Queue`**, reorder items, skip next/previous, and verify auto-advance when an item ends.
21. Lock the phone while casting: verify lock-screen media notification (`-10s`, `Play/Pause`, `+10s`, `Stop`, seek bar) and physical volume buttons adjust TV volume.
22. Stop playback mid-movie and verify **Continue Watching & History** shows a 1-tap **Resume** button at the saved timestamp.

**Browser casting, Proxy mode & Bookmarks (Phase 5)**
23. Phone: Home → Browser → type a streaming URL, play the video, tap "▶ N videos found – Cast", check probe details (quality/duration/live/DRM), pre-selected English subtitle, and cast (or `+ Queue`).
24. If a CDN refuses the TV directly (`HTTP 403`), toggle **"Proxy stream through this phone"** in the Browser cast sheet (or tap **"Retry via Phone Proxy"** on the Remote) and confirm the stream plays via `MediaProxyServer`.
25. Test bookmarking (`⋮ → ☆ Bookmark this page` and opening from blank browser home), ad blocking (`⋮ → Ad blocker: on (N blocked)` with cosmetic CSS + pop-up blocking), and Back-twice to close.
26. Share a web page URL from Chrome to UniversalCast (opens browser); share a direct `.mp4` link (lands in "Cast a link").

**About, Dummy TV Simulator & Auto-updater (Phase 6)**
27. Home `⋮ → About UniversalCast`: verify stats; tap the **Version** card 5 times to unlock and open **Dummy TV Simulator**; verify touchpad, D-pad, keyboard, tracks, queue, and scale controls respond in the simulator banner.
28. On a signed release build older than the latest GitHub release: trigger *Check for updates*, verify release notes render formatted Markdown (headings, bullets, bold, inline code), and complete the OTA APK install.

**Material 3 Expressive / TV Material redesign (1.1.0 → 1.2.1) — phone confirmed by the owner on a real phone; TV checked on an emulator only**
29. Phone, Android 12+: Home → palette icon → *Appearance*: *Wallpaper* follows the wallpaper (change wallpaper, reopen: colours change); Ocean/Violet/Lagoon/Forest/Rose/Sunset recolour the whole app; Light/Dark/System switch immediately and system-bar icons stay readable. On Android 8–11 *Wallpaper* is not offered.
30. Phone cold start: system splash (logo on neutral background) hands over to the animated splash in the active theme colours; tapping the splash skips it. Themed launcher icon appears on Android 13+ when "Themed icons" is on.
31. Phone *Pair a TV*: the QR scanner opens immediately; backing out shows Scan / Enter code manually; after pairing, *Connect & open remote* lands on the remote. Tapping a TV card (or a "Found nearby" card) connects / opens the scanner in one tap.
32. Phone Remote: tab row (Media & Cast / TV Remote); with nothing playing the transport card is hidden and the cast options are first; with media playing the icon transport (back 10 s, play/pause, forward 10 s, previous/next) works.
33. TV: D-pad focus on every button shows the TV Material focus (scale + inverted colours); settings toggles are switches (one OK press toggles); dialogs (revoke, rename, update) focus their first button; pairing focuses *Codes match* / *Approve* automatically; splash is skipped by any key.
34. Phone *Appearance*: the *Expressive* theme and the Light/Dark/System modes apply immediately; **Pure black (OLED)** turns the dark-mode background and base surfaces to true black (cards stay slightly lighter) with any colour theme, including Wallpaper.
35. TV not showing the phone remote outside UniversalCast: Home shows **Turn on phone remote for all apps**; press it (Accessibility screen opens, or it enables itself if `WRITE_SECURE_SETTINGS` was granted), then from the phone remote D-pad / OK / Back / Home must work on the TV launcher and in other apps.
36. Phone browser: open a site, scroll, close the app completely (swipe it away), reopen: the browser is back on the same page and scroll position and Back still walks through the earlier pages. Choose **Home** (arrow) first and reopen: the app starts on Home but the browser resumes the same page when opened.

## Known limits (stated in the UI/docs)
- DRM (Widevine/FairPlay/PlayReady, SAMPLE-AES) can't be cast by any method here.
- By default the TV fetches streams directly; for IP-bound/one-time tokens that fail on the TV, toggle **Phone Proxy Mode** so the phone fetches and serves the stream locally.
