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
- `media.load` may carry `headers`, limited to `Referer`, `Origin`, `User-Agent` and `Cookie`
  (`MediaHeaders`). Names are case-insensitive and may not repeat. Values must be single-line printable text
  within length limits, and Referer/Origin must be http(s) URLs. Anything else is `INVALID_PAYLOAD`, so headers
  can't be injected and nothing like `Host`/`Authorization` can be set.
- Headers apply only to the stream they came with: one Media3 data source per load, kept in memory, never
  logged, never in snapshots or events, and `MediaItemInfo.toString()` shows only header names. Observers see the
  media URI as before, but never the headers.
- The phone forwards the page's Referer/Origin/User-Agent and only the cookies the WebView holds for the stream's
  own host, read at cast time. They travel only over the pinned, authenticated connection to a paired TV.
- The browser has no JavaScript bridge into the app, no file/content access and no mixed content. Popups are
  refused, and non-web schemes (`intent:`, `market:`…) are not followed. It only observes requests and never
  changes them. Detected links are probed from the phone with the same headers before casting.
- DRM-protected streams (Widevine etc.) are out of scope: the license exchange happens inside the page.

## Not covered / by design
See `reports/KNOWN_LIMITATIONS.md` (e.g. cleartext http media fetches by the player, no device attestation, a root
attacker on the TV is out of scope).
