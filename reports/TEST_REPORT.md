# Test report

Executed in the build sandbox with kotlinc + JUnit 4.13.2 on JDK 17 (Gradle/Maven Central unavailable; jars from distro).

**`:core`: 165 tests, 0 failures** (`OK (165 tests)`).

| Suite | Tests | Scope |
|---|---|---|
| ProtocolTest | 11 | strict JSON, envelope validation, limits |
| CryptoTest | 13 | HKDF RFC 5869 vector, ECDSA/ECDH, key validation, transcripts, fingerprints, TLS pin manager |
| PairingTest | 18 | pairing state machine, bad proofs, expiry, SAS, role approval |
| AuthSessionPlaybackTest | 22 | authenticator (replay/expiry/binding), sessions, playback model, config validator, discovery records |
| HandlerTest | 30 | pipeline, sequence, duplicate ids, permissions, revocation, malformed counters, timeouts |
| IntegrationTest | 33 | real WSS: pairing, pinning, plaintext refusal, auth attacks, roles, revocation, abuse, lifecycle |
| StorageTest | 12 | encrypted registry: tamper, wrong key, migration/versions, identity change, concurrency |
| SenderTest | 26 | phone side against the real receiver: pairing session, connect, all playback commands, pushed state, observer limits, takeover, heartbeat vs sequence numbers, revoked/unknown/pin-mismatch/unreachable, auto-reconnect, user disconnect, encrypted TV store |

**Not run (cannot run here):** `:app` unit test (`UiFormattingTest`), `:sender-app` unit test (`LinksTest`), instrumented `KeystoreIdentityTest` and `KeystoreSenderTest`, any UI, camera/QR scanning, phone NSD,
Media3, NSD, emulator or real TV test. Run them with the commands in BUILD.md.
