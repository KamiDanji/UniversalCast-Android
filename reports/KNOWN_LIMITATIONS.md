# Known limitations

1. `:app` was never compiled; expect minor compile/API errors on first build. APKs not produced; no emulator/TV run.
2. Release APK is unsigned; add your signing config.
3. minSdk 23 (Keystore EC keys). TLS 1.3 only on API 29+; older devices use TLS 1.2.
4. The player may fetch `http://` media (cleartext allowed for media only); the control channel is always TLS.
5. Senders pin by QR fingerprint, so a sender must have scanned the QR once; there is no recovery if the TV's
   Keystore key is lost (identity regenerates and every device must re-pair).
6. One active session ("last controller wins"); no multi-room/queue support.
7. Position events are throttled (≈5 s) to avoid flooding; senders extrapolate from `capturedAtMs`.
8. No remote device attestation; a rooted TV is out of scope.
9. Pairing needs a physical user at the TV (D-pad); no accessibility pass (TalkBack) was done.
10. Browser casting is intentionally absent.

## Phone app
11. `:sender-app` was never compiled or run; expect minor compile errors on the first build. Camera/QR flow and NSD discovery were not exercised on a device.
12. Only direct http(s) media URLs can be cast. Files on the phone cannot (the TV fetches media itself; a phone-side HTTP server is not implemented). DRM/streaming-service links (YouTube, Netflix…) are not direct media URLs and will not play.
13. By the receiver's design, losing the connection ends the session and stops TV playback, so closing the app or locking a phone that gets killed stops the video. There is no background service on the phone.
14. The phone's minSdk is 26.
15. NSD `resolveService` is deprecated on Android 14+ but still works; no replacement path was implemented.
