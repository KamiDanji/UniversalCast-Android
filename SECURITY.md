# Security model

## Trust root
There is no CA. The receiver's identity is an EC P-256 key (Android Keystore, non-exportable). Its identity is
the SHA-256 of the SPKI. The QR code transports that fingerprint, so a sender that scanned the QR connects with a
`PinnedTrustManager` that accepts exactly that key and nothing else. Hostnames/IPs are irrelevant, so IP changes
and mDNS spoofing cannot redirect a paired sender to an impostor.

## Pairing (120 s, single use)
1. TV shows a QR: fingerprint, pairing id, one-time 256-bit secret, expiry, addresses, port.
2. Sender connects over pinned TLS and sends `pair.request`: ephemeral ECDH key, nonce, long-term sender key, signature, and an HMAC (keyed by the QR secret) proving it saw the QR.
3. Both sides derive keys with HKDF over a domain-separated transcript and compute a **6-digit SAS**. The user compares it on both screens; this defeats an attacker who relayed the QR.
4. Sender sends a key-confirmation MAC; the TV user must confirm the SAS **and** explicitly approve, choosing Controller or Observer. Nothing is auto-approved.
5. A wrong proof burns the session. The sender record is persisted only after approval.

## Authentication of every connection
`auth.hello` → receiver issues a random 32-byte challenge (10 s TTL, bound to the connection). The sender signs a
transcript of (receiver fingerprint, sender id, version, challenge id, challenge, client nonce). The challenge is
consumed on the first attempt (success or failure) and consumed ids are remembered, so replay on the same or
another connection fails. The receiver answers with its own signature over the transcript including the client
signature (mutual authentication). Unauthenticated connections are closed after 10 s; at most 16 may be open.

## Authorization
Roles: Controller (all methods), Observer (`ping`, `playback.snapshot`, `capabilities.get`). Permissions are
enforced on the receiver for every request using the *current* role from the registry, so a stolen session id
gives an Observer nothing. Revocation is checked on every request as well.

## Transport and input hardening
TLS 1.2/1.3 only; WebSocket frames/messages ≤ 64 KiB; strict JSON (no duplicate keys, bounded depth, no trailing
data); binary frames refused; 5 malformed messages after auth close the connection (first one before auth);
sequence numbers must strictly increase; duplicate request ids replay the cached response instead of re-executing;
media URIs must be http/https and are validated; the receiver never opens `file:`/`content:` URIs from the network.

## Storage
Keystore keys never leave secure hardware/TEE where available. The sender registry is AES-256-GCM encrypted with a
Keystore key, header authenticated, written atomically; corrupt or tampered files are quarantined and the store
resets (fail closed). No private key, secret, challenge or token is written to logs. Backups are disabled.

## Phone app (sender)
- The phone's identity is an EC P-256 key in the Android Keystore (non-exportable); the TV knows it by its SHA-256.
- Every connection is pinned to the TV key fingerprint learned from the QR code at pairing time. A different key
  (a different TV or an attacker on the Wi-Fi) is refused and reported as "security identity changed"; the
  phone never falls back to trusting it. Cleartext traffic is disabled in the phone app.
- The list of paired TVs (fingerprints are the trust roots) is AES-256-GCM encrypted with a Keystore key, atomically written,
  and quarantined/reset if tampered with. If the phone key is ever regenerated, the list is dropped because no TV knows the phone.
- The user must compare the 6-digit code on both screens; "They are different" aborts and warns about interference.
- mDNS data (names, addresses, ids) are hints only; they can redirect where the phone connects but can never
  make it trust a TV it was not paired with.

## Casting from websites (in-app browser)
- `media.load` may carry `headers` (UCP 1.0, optional, backward compatible). They are accepted **only from
  Controller-role senders** (the permission check runs before any payload validation; Observers get `FORBIDDEN`).
  Allowlist, case-insensitive and normalised: `Referer`, `Origin`, `User-Agent`, `Cookie`, `Accept-Language`. At most
  5 entries, no name twice, each value 1–4096 visible ASCII characters (0x20–0x7E: no CR/LF/control characters, so no
  header injection), names + values ≤ 8192 bytes, Referer/Origin must be http(s) URLs. Anything else is
  `INVALID_PAYLOAD`; `Host`, `Authorization`, `Range` etc. can never be set.
- `subtitles`: at most 8, each `{uri (http/https, ≤ 2048), mimeType ∈ text/vtt | application/x-subrip | text/x-ssa |
  application/ttml+xml, language? (≤ 16, [A-Za-z0-9-]), label? (≤ 64, no control characters)}`, no unknown fields.
- **Cookies are host-scoped on the TV**: the player adds `Cookie` only to requests whose host equals the media URI's
  host. A network interceptor on every hop (OkHttp) strips it from any request to another host, so cookies never
  reach CDN/segment hosts or redirect targets. (`DefaultHttpDataSource` was not used: it re-sends a request's extra
  headers to every redirect hop.) Referer/Origin/Accept-Language go to all hosts, like a browser's player would send them.
- **Header values are never logged**: not in the diagnostics log, not in logcat (Media3's library logger is switched
  off for this reason), not in snapshots or events; `MediaItemInfo.toString()` shows header *names* only. Headers live
  in memory for the current item and are dropped on stop or the next load.
- The phone forwards the page's Referer/Origin/User-Agent/Accept-Language and only the cookies the WebView holds for the
  stream URL, read at cast time. They travel only over the pinned, authenticated connection to a paired TV.
- The phone's stream probe (`StreamProbe`) uses the same rules: Cookie only to the original host, never to a redirect
  target; redirects only to http(s); at most 256 KiB read, 5 s timeouts.
- The browser has no JavaScript bridge into the app (no `addJavascriptInterface`), no file/content access, one window
  only, does not follow non-web schemes (`intent:`, `market:`, `file:`…) and survives a renderer crash. It only
  observes requests and never changes them. The "Scan page" script only reads the DOM.
- Ad blocking: a built-in domain blocklist (`AdBlocker`) answers ad requests with an empty response, blocks main-page
  redirects to ad sites and filters "new window" requests (no tap → dropped; tapped → loaded in the same view unless
  it is an ad site or a non-web scheme). A throw-away WebView used only to learn a pop-up's URL is destroyed at once.
- DRM-protected streams (Widevine/FairPlay/PlayReady, SAMPLE-AES) are detected and not offered for casting.

## Receiver auto-start and auto-open
- The boot receiver is exported (system broadcasts must reach it) but can only start the receiver service, which the
  app also starts whenever it is opened. It honours the "Start when the TV turns on" setting.
- "Open automatically when a phone casts" uses the user-granted "Display over other apps" permission and a 1×1
  transparent, non-focusable, non-touchable overlay window. That window never takes input or focus. The activity is
  only brought to the front after an authenticated Controller started playback.
- No unauthenticated or plaintext endpoint was added; the control channel is unchanged.

## Not covered / by design
See `reports/KNOWN_LIMITATIONS.md` (e.g. cleartext http media fetches by the player, no device attestation, a root
attacker on the TV is out of scope).
