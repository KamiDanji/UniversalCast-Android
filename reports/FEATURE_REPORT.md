# Feature implementation report

Legend: ✅ implemented and verified by automated tests · 🟡 implemented, compiled/run **not verified** (Android layer)

| Area | State |
|---|---|
| UCP 1.0 envelopes, ids, seq, errors, capabilities, state sync, duplicates, size limits | ✅ |
| Pairing: QR, 120 s window, sender proof, SAS, approval, roles | ✅ (logic) · 🟡 (UI) |
| WSS, TLS 1.2/1.3, fingerprint pinning, challenge-response, replay, auth timeout | ✅ |
| Revocation (persist, disconnect, stop playback, block) | ✅ |
| mDNS record content and idempotent advertising | ✅ (records) · 🟡 (Android NSD) |
| Encrypted persistence, corruption handling, migration chain | ✅ (file store) · 🟡 (Keystore keys) |
| Keystore EC identity | 🟡 |
| Media3 playback engine, keep-screen-on | 🟡 |
| TV UI: Home, Pairing, Devices, Player, Settings/Diagnostics | 🟡 |
| Foreground service, multicast/wake locks | 🟡 |
| Dev/prod separation (no insecure endpoint in any mode) | ✅ |
| Phone app: pairing, connection, commands, events, reconnect, TV store (logic in :core) | ✅ |
| Phone app: Keystore identity, NSD discovery, QR scanning, Compose UI, share-intent | 🟡 |
| Browser casting | Not implemented by design; extension point documented; not advertised |
| Debug/release APKs | ❌ not built (no Android SDK in sandbox) |
