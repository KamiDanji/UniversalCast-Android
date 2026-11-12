# Feature implementation report

Legend: ✅ implemented and verified by automated tests · 🟢 implemented, compiles (`assembleDebug`), logic unit-tested,
**device behaviour unverified** · 🟡 implemented, compiles, **unverified** (needs a TV/phone)

| Area | State |
|---|---|
| UCP 1.0 envelopes, ids, seq, errors, capabilities, state sync, duplicates, size limits | ✅ |
| Pairing: QR, 120 s window, sender proof, SAS, approval, roles | ✅ (logic) · 🟡 (UI) |
| WSS, TLS 1.2/1.3, fingerprint pinning, challenge-response, replay, auth timeout | ✅ |
| Revocation (persist, disconnect, stop playback, block) | ✅ |
| Phone: connection, commands, events, reconnect (logic in :core) | ✅ |
| Phone: disconnect while commands are queued (formerly flaky `heartbeatAndCommandsNeverCollideOnSequenceNumbers`) | ✅ regression test + stress run |
| Receiver: stalled responses (Java-WebSocket write race) re-armed by `UcpServer` watchdog | ✅ stress run (see TEST_REPORT) |
| **TV player without on-screen controls** (video, subtitles, spinner, error only; immersive; Back stops; media keys toggle) | 🟢 key policy tested · 🟡 on a TV |
| **Start when the TV turns on** (`BootReceiver`, `connectedDevice` FGS, setting, `shouldAutostart`) | 🟢 `shouldAutostart` tested · 🟡 on a TV |
| **Open automatically when a phone casts** (`AutoLaunchPolicy`, `CastStartDetector`, overlay anchor, settings row, adb fallback, wake + move-to-back) | 🟢 policy/detector tested · 🟡 on a TV |
| **`media.load.headers` / `subtitles`**, validation, `features: media-headers, subtitles` | ✅ |
| Receiver per-request headers, Cookie host-scoped (incl. redirects), subtitles through the same HTTP stack, English preferred | 🟡 (Media3/OkHttp on device) |
| **Sender browser-cast logic in :core**: `MediaSniffer`, `CandidateList`, `StreamProbe` (+ DRM), `CastHeaders`, `BrowserCast`, `PageScan`, `SenderController.load(MediaCast)` | ✅ |
| **Phone browser** (`BrowserScreen`: hardened WebView, detection, Scan page, candidates sheet, subtitles, TV choice, pause page, remote overlay) | 🟢 URL normalisation tested · 🟡 on a phone |
| **Browser ad blocker + pop-up/"new tab" handling + Back** (`AdBlocker` in :core, menu toggle, Back-twice to close) | 🟢 `AdBlocker` tested · 🟡 on a phone |
| "Cast a link" field, share intent | 🟡 (unchanged logic, compiles) |

## UNVERIFIED — manual test checklist (TV + phone)

Use debug builds (`com.universalcast.receiver.debug`, `com.universalcast.sender.debug`), phone and TV on the same Wi-Fi.

**TV player (Phase 1)**
1. Cast an MP4 from the phone. On the TV you must see only the video: no title, no seek bar, no buttons, no status text.
2. While it starts, a small spinner is centred; it disappears once the video plays. System bars are hidden.
3. Cast a broken link (e.g. `https://example.org/nope.mp4`): a centred "Playback problem" message appears.
4. D-pad left/right/up/down/OK: nothing happens, no focus highlight appears.
5. Remote play/pause key: pauses/resumes silently; the phone's remote shows the new state.
6. Back on the remote: playback stops, Home screen returns, system bars come back.
7. Cast an HLS stream with subtitles (see 20): subtitles are visible at the bottom, not covered by anything.
8. Leave a video playing 15+ minutes: the TV must not dim or start the screensaver.

**Start on boot (Phase 2)**
9. Install, open the app once, close it (Home). Reboot the TV. Without opening the app, pair/connect from the phone: it connects. (Diagnostics shows "Auto-start after BOOT_COMPLETED".)
10. Settings → "Start when the TV turns on" → Off. Reboot. The phone can't connect until the app is opened.
11. Turn it back On. `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.universalcast.receiver.debug` starts the receiver.
12. Android 15 TVs only: `adb shell am compat enable FGS_BOOT_COMPLETED_RESTRICTIONS com.universalcast.receiver.debug`, reboot, repeat 9. No crash; the diagnostics log has no "could not run as a foreground service" error.

**Auto-open on cast (Phase 3)**
13. Without the overlay permission: TV on another app (e.g. YouTube), receiver running in background. Cast from the phone. Expected: the player does **not** appear; Settings → Event log shows `Auto-open blocked: grant "Display over other apps"`.
14. Grant it (Settings → "Open permission screen", or `adb shell appops set com.universalcast.receiver.debug SYSTEM_ALERT_WINDOW allow`). The Settings row shows "granted".
15. Repeat 13: the player comes to the front within ~1 s and plays.
16. Stop from the phone (or let it end): the TV returns to the app it showed before (YouTube).
17. Let the TV dim/screensaver start, then cast: the screen wakes and shows the player.
18. Reboot the TV, don't open the app, cast from the phone: the player appears (combines 9 + 15).
19. Settings → "Open automatically…" Off → cast while in background: nothing comes to the front; audio may play in the background (by design of the existing engine).

**Browser casting (Phases 4–6)**
20. Phone: Home → Open browser → type `onepace.co` (becomes `https://onepace.co`). Open an episode and press play.
    Observe: the bottom bar shows "▶ N videos found – Cast". Tap it: the list shows an HLS entry (ideally "HLS (all qualities)"
    with "up to 1080p" and a duration of ~20+ minutes); short ones are ads. Note what you see for me (kinds, hosts, problems).
21. If subtitles are offered, the English one is pre-ticked. Tap "Cast to <TV>". The phone's page pauses; the remote
    appears above the page; the TV plays with subtitles. Close the remote: the page is still there (not reloaded).
22. If the TV shows "The video server refused the TV (HTTP 403)", note it: the CDN rejects the TV's request even with the
    page headers (IP/time-bound token). Try "Scan page", another quality, or another server option on the site.
23. With no TV connected and exactly one paired TV: casting connects to it automatically, then casts.
24. Open a DRM site (e.g. a paid streaming service): candidates are marked "DRM" and Cast is disabled.
25. Open a page and tap a link with `target=_blank`: it opens in the same browser view. Tap an `intent://` / app link: nothing happens.
26a. Ad blocker: open a streaming site with ads. Menu (⋮) shows "Ad blocker: on (N blocked)" with N rising; ad banners/pop-unders don't appear. Tap a spot that used to open an ad tab: you stay on the page (or, for a non-ad link, it opens here and the ← arrow / Back returns to the video page).
26b. Back at the first page shows "Press Back again to close the browser"; only a second Back closes it. ⋮ → Close browser closes directly.
26c. If a site breaks, ⋮ → switch the ad blocker off (the page reloads) and note the site for me.
26. Share a web page URL from Chrome to UniversalCast: the browser opens with it. Share a direct `.mp4` link: it lands in "Cast a link".

## Known limits (stated in the UI/docs)
- One-time, IP-bound or time-bound signed URLs may fail on the TV (and the phone's probe uses one request).
- DRM (Widevine/FairPlay/PlayReady) can't be cast by any method here.
- The TV fetches the stream itself; the phone only sends the URL and headers.
