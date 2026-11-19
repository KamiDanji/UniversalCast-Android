# Test report

Verified at version **1.2.2** (`versionCode=16`).

Command (Windows, JDK 21, Gradle 9.7.1, AGP 9.1.1, Kotlin 2.3.21, `--rerun-tasks`):
`.\gradlew.bat :core:test :app:testDebugUnitTest :sender-app:testDebugUnitTest :updater:testDebugUnitTest --rerun-tasks`
→ **BUILD SUCCESSFUL** (all 247 tests re-run after the 1.2.0 toolchain upgrade and again for 1.2.1). Release builds also compiled and packaged cleanly via `.\gradlew.bat assembleRelease`.

| Module | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| `:core` | **222** | 0 | 0 | 0 |
| `:app` (unit) | **11** | 0 | 0 | 0 |
| `:sender-app` (unit) | **8** | 0 | 0 | 0 |
| `:updater` (unit) | **6** | 0 | 0 | 0 |
| **Total** | **247** | **0** | **0** | **0** |

## :core suites (222 tests)
| Suite | Tests | Scope |
|---|---|---|
| HandlerTest | 38 | pipeline, permissions, revocation, header/subtitle validation tables, detached playback grace period, sweep expiry, resume & revocation while detached |
| IntegrationTest | 34 | end-to-end TLS 1.2/1.3 WebSocket pairing, auth, replay, detached playback reconnect, grace-period=0 stop, revocation, malformed abuse |
| SenderTest | 30 | phone side vs. real WSS receiver: reconnect, queued disconnect regression, browser cast end-to-end, invalid casts refused, blackhole TLS handshake timeout, encrypted store |
| PairingTest | 19 | QR bootstrap, HKDF/SAS agreement, sender proofs, expiry, error cases |
| StorageTest | 12 | AES-256-GCM encrypted registry, tamper detection, quarantine, corruption recovery, schema migrations |
| AuthenticatorTest | 11 | challenge freshness, single-use & replay detection, connection binding, expiry, wrong key |
| FilterEngineTest | 11 | Adblock-Plus/uBlock rule parsing (`\|\|domain^`, `@@`, `$third-party`, `$domain=`, resource types, cosmetic CSS, registrable domain) |
| StreamProbeTest | 11 | JDK HttpServer: master/media/live playlists, relative URIs, extensionless, DRM markers (HLS + DASH), HTML page, progressive sniffing + Range, HTTP errors, unreachable host, timeout, 256 KiB cap, redirects (cookie not forwarded cross-host), ISO durations |
| MediaSnifferTest | 10 | URL-shape tables (HLS/DASH/progressive/audio/subtitle/ignored), language guessing, candidate ranking/dedupe/cap/variant hiding, thread safety, `CastHeaders` |
| RoadmapFeaturesTest | 7 | `MediaProxyServer` local range requests & unauthorized token rejection, HLS manifest rewriting & cookie forwarding, Up Next queue + auto-advance, live track switching/offset/style/video scale/sleep timer, remote keys/text & observer denial, history/bookmarks persistence, Dummy TV simulator |
| CryptoTest | 10 | EC P-256 validation, HKDF-SHA256, HMAC, transcripts |
| StrictJsonTest | 6 | strict parser, duplicate keys, depth limit, overflow, trailing data |
| EnvelopeTest | 5 | UCP 1.0 envelope encoding/decoding & validation |
| AdBlockerTest | 4 | listed domains + subdomains blocked; look-alikes, video CDNs, non-web URLs, bare TLDs not; 100k lookups < 2 s |
| ConfigAndDiscoveryTest | 4 | config validation, mDNS TXT records, duplicate-free advertisement |
| SessionManagerTest | 4 | create/validate/terminate, last-controller takeover, uniqueness |
| PermissionsAndRegistryTest | 3 | role permissions, registry operations |
| PlaybackModelTest | 3 | status derivation table, snapshot JSON round-trip, media URI validation |

## :app / :sender-app / :updater unit suites (22 tests)
- `:app` — `PoliciesTest` (10): `shouldAutostart`, `AutoLaunchPolicy` (reasons, API 29 boundary, 2 s debounce), `CastStartDetector`, `PlayerKeyPolicy`, `OverlayPermission.adbCommand`, **`PlayerOsdTracker`** (OSD pills for volume/speed/pause/play/seek/subtitle delay/off). `UiFormattingTest` (1).
- `:sender-app` — `UrlInputTest` (3): `example.org` → `https://example.org`, kept/rejected inputs. `LinksTest` (2).
- `:updater` — `ReleaseTest` (6): `Versions.isNewer`, `ReleaseParser` asset selection, draft/prerelease filtering, digest validation, `TrustedHosts`, **`MarkdownParser`** block & inline parsing.

## Flaky `SenderTest.heartbeatAndCommandsNeverCollideOnSequenceNumbers`
- Fixed in earlier pass via `UcpServer` write watchdog + `SenderController` immediate `connectionLost` handling; 1000 stress iterations passed with 0 failures.

## Not run
`:app:connectedDebugAndroidTest` / `:sender-app:connectedDebugAndroidTest` (need a connected Android device); no physical TV or phone runtime verification in this pass.

## Emulator smoke tests (1.2.0 / 1.2.1)
Run on a Pixel 8 virtual device (API 36) and an Android TV 1080p virtual device; not a substitute for the device checklist in `FEATURE_REPORT.md`.
- Phone debug + signed **release (R8-minified)** build: launch, splash, Home, Appearance sheet (modes, colour themes, Pure black), About, Dummy-TV remote (playback controls, tracks, queue), light/dark mode: no crashes in logcat.
- TV debug + signed release build: launch, splash, Home, pairing QR, Settings: no crashes; D-pad navigation worked.
- Not exercised: camera QR scan, real phone-to-TV casting, Media3 playback of real streams, in-place self-update.
- 1.2.2: TV emulator (Android 16): with the Accessibility service on and UniversalCast in the background, simulated phone D-pad keys moved focus in the TV launcher (debug-only `DebugKeyReceiver` drives `TvRemoteDispatcher`). Phone emulator: browser opened a page, scrolled, app force-stopped and cold-started: reopened on the same page at the same scroll position.
