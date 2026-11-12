# Security verification

Verified by automated tests (`:core`, all passing):
- Plaintext WebSocket upgrade never succeeds; wrong fingerprint refused at TLS; wrong QR secret refused.
- Unauthenticated, unpaired, revoked and bad-signature senders are refused; challenge replay (same/new connection) and expiry refused.
- Observer cannot invoke any control method, including with a stolen session id and including `media.load` with
  headers/subtitles; role change/revocation applies on the next request.
- Oversize, malformed, binary frames, bad sequence numbers, duplicate ids, non-http(s) media URIs handled.
- **`media.load.headers`**: allowlist only (Host, Authorization, Range, X-* rejected), case-insensitive duplicates,
  >5 entries, value >4096, total >8192 bytes, CR/LF/TAB/DEL/control/non-ASCII, non-string values, non-http(s)
  Referer/Origin → `INVALID_PAYLOAD`, and nothing invalid reaches the player (`HandlerTest.headerValidationTable`).
- **`media.load.subtitles`**: >8 tracks, non-array, missing fields, unknown fields, non-http(s) or >2048 URIs,
  unsupported MIME, bad language/label → `INVALID_PAYLOAD` (`HandlerTest.subtitleValidationTable`).
- Header values never appear in responses, snapshots, events or `toString()` (`HandlerTest`, `SenderTest`).
- The phone refuses casts the receiver would reject (`SenderTest.invalidBrowserCastsAreRefusedOnThePhone`);
  `CastHeaders` drops (never truncates) invalid values (`MediaSnifferTest`).
- `StreamProbe`: the Cookie is not sent after a redirect to another host; non-http redirects and loops end
  (`StreamProbeTest.redirectsAreFollowedAndHeadersForwardedButCookiesStayOnTheOriginalHost`).
- Pairing: wrong proof burns session, SAS mismatch aborts, rejection stores nothing, expiry enforced.
- Storage: ciphertext has no plaintext, tampering/wrong key/newer schema/identity change are detected and fail closed.
- Config validator refuses insecure configurations in every mode.

Reviewed in code, not testable on the JVM (verify on a device):
- TV: Cookie added only for the media host by the `ResolvingDataSource` resolver, and stripped by the OkHttp network
  interceptor on every other hop including redirects (`Media3PlaybackEngine`).
- TV: Media3 library logging off; no diagnostics/log line contains a URL, header or cookie (grep of all log calls).
- Phone WebView: no `addJavascriptInterface`, file/content access off, single window, non-http(s) navigation blocked,
  renderer crash handled.
- TV: boot receiver can only start the receiver service; overlay window is non-focusable and non-touchable.

Not verified here: Keystore hardware behaviour, TLS with real Android Conscrypt, NSD, physical network attacks.
