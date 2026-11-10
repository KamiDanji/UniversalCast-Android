# Security verification

Verified by automated tests (`:core`, all passing):
- Plaintext WebSocket upgrade never succeeds; wrong fingerprint refused at TLS; wrong QR secret refused.
- Unauthenticated, unpaired, revoked and bad-signature senders are refused; challenge replay (same/new connection) and expiry refused.
- Observer cannot invoke any control method, including with a stolen session id; role change/revocation applies on the next request.
- Oversize, malformed, binary frames, bad sequence numbers, duplicate ids, non-http(s) media URIs handled.
- Pairing: wrong proof burns session, SAS mismatch aborts, rejection stores nothing, expiry enforced.
- Storage: ciphertext has no plaintext, tampering/wrong key/newer schema/identity change are detected and fail closed.
- Config validator refuses insecure configurations in every mode.

Not verified here: Keystore hardware behavior, TLS handshake with real Android Conscrypt (P-256 Keystore key with
SHA-256/384/512 digests), NSD behavior, physical network attacks. These need on-device testing.
