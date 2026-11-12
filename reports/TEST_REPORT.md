# Test report

Command (Windows, JDK 17, Gradle 8.7, `--rerun-tasks`):
`.\gradlew.bat :core:test :app:assembleDebug :sender-app:assembleDebug :app:testDebugUnitTest :sender-app:testDebugUnitTest`
→ **BUILD SUCCESSFUL**, both debug APKs built.

| Module | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| `:core` | **200** | 0 | 0 | 0 |
| `:app` (unit) | **10** | 0 | 0 | 0 |
| `:sender-app` (unit) | **5** | 0 | 0 | 0 |

Baseline before this work: `:core` 171 tests, 0 failures.

## :core suites
| Suite | Tests | Scope |
|---|---|---|
| HandlerTest | 37 | pipeline, permissions, revocation…; **new**: header/subtitle validation tables, headers+subtitles reach the engine, observer forbidden, capabilities features, oversize |
| SenderTest | 29 | phone side vs. real WSS receiver; **new**: disconnect while commands are queued (regression), browser cast end-to-end (exact headers/subtitles at the fake engine), invalid casts refused on the phone |
| MediaSnifferTest | 10 | URL-shape tables (HLS/DASH/progressive/audio/subtitle/ignored), language guessing, candidate ranking/dedupe/cap/variant hiding, thread safety, `CastHeaders` |
| AdBlockerTest | 4 | listed domains + subdomains blocked; look-alikes, video CDNs, non-web URLs, bare TLDs not; 100k lookups < 2 s |
| StreamProbeTest | 11 | JDK HttpServer: master/media/live playlists, relative URIs, extensionless, DRM markers (HLS + DASH), HTML page, progressive sniffing + Range, HTTP errors, unreachable host, timeout, 256 KiB cap, redirects (cookie not forwarded cross-host), ISO durations |
| IntegrationTest 33 · PairingTest 18 · StorageTest 12 · AuthenticatorTest 11 · CryptoTest 10 · StrictJsonTest 6 · EnvelopeTest 5 · ConfigAndDiscoveryTest 4 · SessionManagerTest 4 · PermissionsAndRegistryTest 3 · PlaybackModelTest 3 | | unchanged |

## :app / :sender-app
- `PoliciesTest` (9): `shouldAutostart`, `AutoLaunchPolicy` (reasons, API 29 boundary, 2 s debounce), `CastStartDetector`, media-key policy, adb command. `UiFormattingTest` (1).
- `UrlInputTest` (3): `example.org` → `https://example.org`, kept/rejected inputs. `LinksTest` (2).

## Flaky `SenderTest.heartbeatAndCommandsNeverCollideOnSequenceNumbers`
- 20 isolated runs of `SenderTest`: 20/20 green, no reproduction.
- Stress (same test body in a loop, CPU hog threads): **1 failure in 150 and 1 in 600 iterations** before the fix:
  a ping (or `pair.confirm`) response never reached the phone; the heartbeat closed the socket after 4 s, then the
  commands already queued ran against it and surfaced "Unexpected error: WebsocketNotConnectedException".
- Cause 1 (receiver, library): lost-wakeup race in Java-WebSocket 1.6.0 `WebSocketServer.doWrite()`; fixed by a write
  watchdog in `UcpServer`. Cause 2 (phone): `SenderController` let queued commands hit a dead socket; fixed in
  `UcpClient`/`SenderController`, with a deterministic regression test that fails on the old code.
- After the fix: **1000 stress iterations, 0 failures** (523 s).

## Not run
`:app:connectedDebugAndroidTest` / `:sender-app:connectedDebugAndroidTest` (need a device); no emulator/TV/phone runs.
