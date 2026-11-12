# Known limitations

1. The Android layers (`:app`, `:sender-app`) compile and their pure logic is unit-tested, but nothing was run on an
   emulator, TV or phone in this pass. See the checklist in `FEATURE_REPORT.md`.
2. Release APKs are unsigned; add your signing config.
3. minSdk 23 (TV) / 26 (phone). TLS 1.3 only on API 29+; older devices use TLS 1.2.
4. The TV player may fetch `http://` media (cleartext allowed for media only); the control channel is always TLS.
5. Senders pin by QR fingerprint; if the TV's Keystore key is lost, every device must re-pair.
6. One active session ("last controller wins"). Losing the controlling phone's connection ends the session and stops
   TV playback (by design of the receiver); a heartbeat-triggered reconnect therefore also stops playback.
7. Position events are throttled (≈5 s); senders extrapolate from `capturedAtMs`.
8. No remote device attestation; a rooted TV is out of scope.

## Start on boot / auto-open
9. After installing, Android keeps the app "stopped" until it is opened once; no boot broadcast reaches it before that.
10. A TV in deep standby suspends apps (network off); no app can stay reachable through that. The receiver comes back
    when the TV fully wakes or reboots.
11. `QUICKBOOT_POWERON` is a vendor broadcast; it is not one of Android's documented exemptions for starting a
    foreground service from the background, so on Android 12+ the start may be refused (logged, not a crash).
12. Auto-open needs "Display over other apps". Many Android TV builds have no settings screen for it; use the adb
    command. Some TV builds may forbid overlay windows or background activity starts entirely; then the diagnostics log
    shows `Auto-open blocked…` and the user must open the app.
13. The overlay anchor is a 1×1 transparent window. Android 15 requires a *visible* overlay window for related
    exemptions; whether every TV vendor counts a transparent pixel as visible is unverified.
14. `ALLOW_REMOTE_MEDIA_KEYS = true` is an assumption (the remote's play/pause key toggles playback). Set it to false in
    `PlayerScreen.kt` to ignore those keys.

## Browser casting
15. The TV fetches the stream itself. Streams whose token is bound to the phone's IP, time-limited, or single-use
    (the phone's probe consumes one request) may fail on the TV.
16. DRM (Widevine/FairPlay/PlayReady, SAMPLE-AES) can't be cast by any method here; such candidates are disabled.
17. "Scan page" can only read the main document; players in cross-origin iframes are found through their network
    requests, and the after-cast pause can't reach them either (pause the page's video yourself if it keeps playing).
18. Players that build the stream in JavaScript from `blob:` URLs are only castable if their underlying playlist/segment
    requests are visible; otherwise nothing is found.
19. The phone app keeps cleartext disabled, so `http://` pages don't load in the browser (https pages with http media
    still list the media; the TV may play http media).
20. onepace.co could not be inspected (it blocks automated fetching); detection is generic and needs a real-device check.
21. Subtitle renditions inside an HLS master are played by the TV from the master; they are shown as
    "built-in subtitles" but are not separately selectable on the phone.
22. Media3's own logcat output is switched off on the TV (to keep URLs/headers out of logcat), which also hides its
    debugging output. Re-enable temporarily in `Media3PlaybackEngine` if needed.

23a. The ad blocker is a compact built-in domain list (no EasyList, no cosmetic filtering, no updates): ads served
     from the site's own domain, or from networks not on the list, still show. Some sites detect blockers; switch it off
     in the ⋮ menu for those.
23b. There are no tabs: a tapped "open in new tab" link replaces the current page (Back returns); pop-ups opened by
     scripts without a tap are dropped.

## Phone app
23. Files stored on the phone can't be cast (the TV fetches media itself; no phone-side HTTP server).
24. NSD `resolveService` is deprecated on Android 14+ but still works; no replacement path was implemented.
