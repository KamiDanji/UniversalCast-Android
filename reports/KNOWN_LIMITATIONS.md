# Known limitations

1. The Android layers (`:app`, `:sender-app`, `:updater`) compile and their pure logic is unit-tested. Since 1.2.0 they were also smoke-tested on emulators, and the phone UI has been used on a real phone, but real TV hardware, real casting and the self-update path are still unverified. See the checklist in `FEATURE_REPORT.md`.
2. Release APKs are signed when `keystore.properties` is configured; without a signing config they stay unsigned.
3. minSdk 24 (TV, raised from 23 in 1.2.0) / 26 (phone). TLS 1.3 only on API 29+; older devices use TLS 1.2.
4. The TV player may fetch `http://` media (cleartext allowed for media only); the control channel is always TLS.
5. Senders pin by QR fingerprint; if the TV's Keystore key is lost, every device must re-pair.
6. One active session ("last controller wins"). When a controlling phone disconnects, the session enters a **detached
   playback grace period** (default 30 minutes) so TV playback continues uninterrupted and seamlessly re-attaches when
   the phone reconnects. Revoking a sender immediately ends any active or detached session and stops playback.
7. Position events are throttled (≈5 s); senders extrapolate from `capturedAtMs`.
8. No remote device attestation; a rooted TV is out of scope.

## Start on boot / auto-open / remote accessibility
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
15. In-app navigation (`KeyEvent`s), keyboard typing (`KeyCharacterMap` + clipboard), and TV system volume (`AudioManager`)
    work out of the box when connected. System-wide D-pad/Back/Home across *other* Android TV apps requires manually
    enabling **UniversalCast Phone Remote** (Home → *Turn on phone remote for all apps*, or Android TV Accessibility Settings). API 33+ uses the system D-pad actions; older TVs use accessibility focus navigation (less exact than a real D-pad in apps that draw their own focus). Verified only on an Android 16 TV emulator. On TVs that hide the Accessibility screen, run once `adb shell pm grant com.universalcast.receiver android.permission.WRITE_SECURE_SETTINGS` and press *Turn on*.

## Browser casting
16. By default the TV fetches streams directly. For streams whose token is bound to the phone's IP or that fail with
    HTTP 403 on the TV, enable **"Proxy stream through this phone"** in the Browser cast sheet (or tap **"Retry via
    Phone Proxy"** on the remote) to route HLS manifests, segments and progressive streams through the phone's
    `MediaProxyServer`.
17. DRM (Widevine/FairPlay/PlayReady, SAMPLE-AES) can't be cast by any method here; such candidates are disabled.
18. "Scan page" can only read the main document; players in cross-origin iframes are found through their network
    requests, and the after-cast pause can't reach them either (pause the page's video yourself if it keeps playing).
19. Players that build the stream in JavaScript from `blob:` URLs are only castable if their underlying playlist/segment
    requests are visible; otherwise nothing is found.
20. The phone app keeps cleartext disabled (`usesCleartextTraffic="false"`), so `http://` pages don't load in the browser
    (https pages with http media still list the media; the TV may play http media).
21. onepace.co could not be inspected (it blocks automated fetching); detection is generic and needs a real-device check.
22. Subtitle and audio tracks inside an HLS master or container are exposed in `PlaybackSnapshot` (`audioTracks`,
    `subtitleTracks`) once loaded and can be switched mid-playback from the phone remote (`playback.selectTracks`), along
    with real-time subtitle timing offset (via a dynamic `Renderer` proxy around Media3 `TextRenderer`, kept in R8 via
    `proguard-rules.pro` — unverified on a physical TV) and visual styling.
23. Media3's own logcat output is switched off on the TV (to keep URLs/headers out of logcat), which also hides its
    debugging output. Re-enable temporarily in `Media3PlaybackEngine` if needed.
24. Ad blocking uses the compact built-in `AdBlocker` domain list plus uBlock/EasyList filter lists (`FilterLists`:
    EasyList, uBlock Origin filters/badware/unbreak, Peter Lowe's list, refreshed via HTTPS every 3 days) and cosmetic
    CSS element hiding (`cosmeticCss`). Scriptlets and procedural `:has()` filters are skipped (WebView limitation).
    Some sites detect blockers; switch it off in the ⋮ menu for those.
25. There are no tabs: a tapped "open in new tab" link replaces the current page (Back returns); pop-ups opened by
    scripts without a tap are dropped.

## Phone app & updates
26. Local videos, audio tracks, and photos stored on the phone can be cast via **"Cast Videos / Music / Photos"** on the
    remote, served over the phone's `MediaProxyServer` (plain HTTP on the local Wi-Fi, protected by a 144-bit random
    capability token per registered item, with HTTP `206 Partial Content` byte-range support for video/audio seeking).
27. NSD `resolveService` is deprecated on Android 14+ but still works; no replacement path was implemented.
28. Debug builds disable the OTA auto-updater (`UpdateConfig.enabled = false`) because their `.debug` `applicationId`
    differs from release builds; OTA update verification applies to signed release builds.

## UI (1.1.0+)
- The phone UI has been looked at on a real phone by the owner (1.2.x); the **TV UI has only been checked on an Android TV emulator**, not yet on real TV hardware (D-pad focus order and spacing at 3 m still need a visual pass; see checklist items 29-33).
- Wallpaper (dynamic) colour needs Android 12+; older phones get the Expressive baseline and the seeded palettes only. The TV app has a single fixed colour scheme.
- The seeded palettes are generated in CIE L*C*h, not Google's HCT, so they follow Material 3's tone rules (and are contrast-tested) but are not identical to Material You colours for the same seed.
- TV Material (`androidx.tv:tv-material` 1.1.0) still marks parts of its API experimental; the build opts in to `ExperimentalTvMaterial3Api`.
- *Pure black (OLED)* only applies in dark mode; the system splash background (a fixed dark grey in `values-night`) is not pure black.

## 1.2.0 (Material 3 Expressive + toolchain upgrade)
- Tested on **emulators only**: a Pixel 8 virtual device (API 36) for the phone app (splash, Home, Appearance sheet, About, Dummy-TV remote, dark mode) and an Android TV 1080p virtual device for the receiver (splash, Home, pairing QR, Settings). **Not** exercised: QR scanning with the camera, real TV-to-phone casting, Media3 playback of real streams, or the in-place self-update.
- The TV app's minimum Android version is now **7.0 (API 24)** because current Compose requires it (was 6.0).
- Media3 stays at 1.3.1 on purpose (the subtitle-offset renderer proxy depends on its internals); upgrading it is a separate task.
- Material 3 Expressive on the phone covers the theme, motion, header, shape badges, loading/progress indicators, buttons and the splash; the rest of the screens use standard Material 3 components that pick up the expressive theme automatically.

## 1.2.2
- The browser restores its page, history and scroll position, but not page state held by JavaScript, playing video position, form input or open pop-ups; if a WebView update makes the saved state unreadable, it just reloads the last URL.
- Gallery / Google Photos casting was tested on an emulator with the system Photo Picker and shared `content://` media items; the Google Photos and Gallery apps themselves, and cloud-only Google Photos items, were not available there. A shared item is readable only while UniversalCast stays open in the background (Android ties the access to the app session); photos picked with the Photo Picker are readable until the app process ends.
