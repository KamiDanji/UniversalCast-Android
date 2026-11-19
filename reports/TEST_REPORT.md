# Test report

Verified at version **1.1.1** (`versionCode=13`).

Command (Windows, JDK 17, Gradle 8.7, `--rerun-tasks`):
`.\gradlew.bat :core:test :app:testDebugUnitTest :sender-app:testDebugUnitTest :updater:testDebugUnitTest --rerun-tasks`
→ **BUILD SUCCESSFUL** (75 executed tasks, as of 1.0.10; for 1.1.0 the `:sender-app` and `:app` unit tests were re-run, including the new `ThemeTest`). Release builds also compiled and packaged cleanly via `.\gradlew.bat assembleRelease`.

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
