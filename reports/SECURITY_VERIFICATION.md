# Security verification

Verified by automated tests (`:core` and `:updater`, all passing):
- Plaintext WebSocket upgrade never succeeds; wrong fingerprint refused at TLS; wrong QR secret refused.
- Unauthenticated, unpaired, revoked and bad-signature senders are refused; challenge replay (same/new connection) and expiry refused.
- Observer cannot invoke any control method, including with a stolen session id, `media.load` with headers/subtitles,
  `queue.*`, `playback.selectTracks`, `remote.key`, or `remote.text`; role change/revocation applies on the next request.
- Detached playback sessions preserve playback during the 30-minute grace period and expire on `sweepDetached`, while
  `revokeSender` immediately ends even detached sessions and stops playback (`HandlerTest`, `IntegrationTest`).
- Oversize, malformed, binary frames, bad sequence numbers, duplicate ids, non-http(s) media URIs handled.
- **`media.load.headers`**: allowlist only (Host, Authorization, Range, X-* rejected), case-insensitive duplicates,
  >5 entries, value >4096, total >8192 bytes, CR/LF/TAB/DEL/control/non-ASCII, non-string values, non-http(s)
  Referer/Origin → `INVALID_PAYLOAD`, and nothing invalid reaches the player (`HandlerTest.headerValidationTable`).
- **`media.load.subtitles`**: >8 tracks, non-array, missing fields, unknown fields, non-http(s) or >2048 URIs,
  unsupported MIME, bad language/label → `INVALID_PAYLOAD` (`HandlerTest.subtitleValidationTable`).
- Header values never appear in responses, snapshots, events, `toString()`, or `CastHistoryStore` (`HandlerTest`,
  `SenderTest`, `RoadmapFeaturesTest`).
- **`MediaProxyServer`**: rejects missing/wrong capability tokens with `403`, serves local `Range` byte requests,
  keeps headers/cookies on the phone (`proxied.headers` empty), rewrites HLS manifests, and streams segments/subtitles
  (`RoadmapFeaturesTest`).
- **`SenderController`**: refuses casts the receiver would reject (`SenderTest.invalidBrowserCastsAreRefusedOnThePhone`),
  bounds TLS handshake socket timeout against blackhole TCP peers (`SenderTest.pairingSkipsBlackholeHostThatAcceptsTcpWithoutTlsHandshake`),
  and keeps Dummy TV simulator purely in-process (`RoadmapFeaturesTest.dummyTvSimulatorConnectsWithoutNetworkAndExecutesAllControls`).
- `CastHeaders` drops (never truncates) invalid values (`MediaSnifferTest`).
- `StreamProbe`: the Cookie is not sent after a redirect to another host; non-http redirects and loops end
  (`StreamProbeTest.redirectsAreFollowedAndHeadersForwardedButCookiesStayOnTheOriginalHost`).
- `AdBlocker` & `FilterEngine`: domain/subdomain, `||host^`, `@@` allow, `$third-party`, `$domain=`, resource-type,
  and cosmetic CSS selector rules verified (`AdBlockerTest`, `FilterEngineTest`).
- Pairing: wrong proof burns session, SAS mismatch aborts, rejection stores nothing, expiry enforced.
- Storage: ciphertext has no plaintext, tampering/wrong key/newer schema/identity change are detected and fail closed.
- Config validator refuses insecure configurations in every mode.
- **Auto-updater (`:updater`)**: `TrustedHosts` rejects look-alike domains (`evilgithub.com`, `github.com.evil.io`, null),
  `ReleaseParser` ignores drafts/prereleases and malformed SHA-256 digests, `Versions` enforces monotonic ordering, and
  `MarkdownParser` parses headings, lists, links, code spans/blocks cleanly (`ReleaseTest`).

Reviewed in code, not testable on the JVM (verify on a device):
- TV: Cookie added only for the media host by the `ResolvingDataSource` resolver, and stripped by the OkHttp network
  interceptor on every other hop including redirects (`Media3PlaybackEngine`).
- TV: Media3 library logging off; no diagnostics/log line contains a URL, header or cookie (grep of all log calls).
- Phone WebView: no `addJavascriptInterface`, file/content access off, single window, non-http(s) navigation blocked,
  renderer crash handled; `FilterLists` downloads over plain HTTPS with a 12 MiB per-list size cap.
- TV: boot receiver can only start the receiver service; overlay window is non-focusable and non-touchable; optional
  `TvRemoteAccessibilityService` is not exported, has `canRetrieveWindowContent="false"` and `canPerformGestures="false"`.
- Updater: `UpdateManager` enforces HTTPS and `TrustedHosts` across max 6 manual redirect hops, verifies APK SHA-256
  digest, package name, and strictly higher `versionCode` before passing to `PackageInstaller`.

Not verified here: Keystore hardware behaviour, TLS with real Android Conscrypt, NSD, OTA APK install on physical devices,
physical network attacks.
