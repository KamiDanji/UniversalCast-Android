# Architecture

```
 Sender ──mDNS──▶ _universalcast._tcp ─┐
 Sender ──WSS (pinned TLS)──▶ UcpServer ─▶ UcpProtocolHandler ─▶ PlaybackEngine ─▶ Media3PlaybackEngine ─▶ ExoPlayer
                                  │            │  ├ Authenticator (challenge/response)
                                  │            │  ├ PairingManager (QR, SAS, approval)
                                  │            │  ├ SessionManager (one active controller session)
                                  │            │  └ SenderRegistry ─▶ EncryptedFileSenderRegistry (AES-GCM, Keystore key)
                                  └ ReceiverIdentity ─▶ KeystoreIdentityProvider (EC P-256, AndroidKeyStore)
```
## Modules
- **:core** – no Android. `ucp` (JSON, envelopes, protocol constants), `security` (crypto, TLS pinning, identities),
  `pairing`, `auth`, `session`, `identity` (roles/permissions/registry), `network` (server + handler),
  `discovery`, `config`, `storage`, `sender` (reference client), `core.ReceiverCore` (composition root).
- **:app** – `ReceiverRuntime` (process-wide state as StateFlows), `service.ReceiverService` (foreground service,
  multicast + wake lock, network callback), `platform` (Keystore, NSD, Media3), `ui` (Compose screens).

## Phone app (:sender-app)
`SenderRuntime` (Keystore identity, encrypted TV list, `SenderController`, NSD discovery) + Compose screens
(Home, Pair, Remote). The behaviour that matters is in `:core` so it can be tested against the real receiver:
- `sender.PairingSession` – pairing ceremony (address fallback, SAS, verified approval).
- `sender.SenderController` – single worker thread (keeps request sequence numbers ordered), pinned connect,
  lazy session creation, commands, event-driven state, heartbeat, reconnect with backoff, failure classification
  (unreachable / rejected / pin mismatch).
- `sender.EncryptedFileReceiverStore` – persistence of paired TVs.
- `sender.MediaSniffer` / `sender.CandidateList` – pure URL classification (HLS / DASH / progressive / audio /
  subtitle / ignore: segments, keys, images…), subtitle language guessing, and the thread-safe, deduplicated, ranked
  (HLS master > HLS > DASH > progressive > audio, newest first) candidate list of a page that hides variant playlists
  of a known master.
- `sender.StreamProbe` / `sender.HlsParser` – `HttpURLConnection` probe (≤ 256 KiB, 5 s, manual redirects with
  host-scoped Cookie) that classifies by content: HLS master/media/live/duration/subtitle renditions, DASH, HTML page,
  progressive; detects DRM.
- `sender.CastHeaders`, `sender.BrowserCast`, `sender.PageScan`, `sender.MediaCast` – the headers a page's player would
  send, the `media.load` built from a candidate, the DOM "Scan page" script and its parser.
  `SenderController.load(MediaCast)` validates headers/subtitles with the receiver's own rules before sending.

Cast browser (`sender-app/.../web/BrowserScreen.kt`): a hardened WebView. `shouldInterceptRequest` (plus a
service-worker client) sees every request of every frame and feeds `CandidateList`; "Scan page" adds what the DOM
shows. The candidates sheet probes lazily, shows quality/duration/live/DRM, lets the user pick subtitles and casts to
the connected TV (or the only paired TV, or a chosen one). After casting, the page's media is paused and the remote is
drawn as an overlay above the still-composed browser.

Receiver side (`Media3PlaybackEngine`): one ExoPlayer with `DefaultMediaSourceFactory(DefaultDataSource(
ResolvingDataSource(OkHttpDataSource)))`. The resolver adds the current item's headers per request (Cookie only to the
media host); an OkHttp network interceptor strips Cookie on every other hop, including redirects. Subtitles are
`MediaItem.SubtitleConfiguration`s loaded through the same stack; English is the preferred text language.

## TV app lifecycle
- `ReceiverService` is a `connectedDevice` foreground service (allowed from BOOT_COMPLETED on Android 15, unlike
  `mediaPlayback`). Start failures are logged, never thrown.
- `BootReceiver` (BOOT_COMPLETED, QUICKBOOT_POWERON, MY_PACKAGE_REPLACED) → `shouldAutostart()` → service.
- Auto-open: `ReceiverRuntime` detects a cast start (`CastStartDetector`), the service asks `AutoLaunchPolicy`
  (setting, activity resumed, 2 s debounce, overlay permission on API 29+) and starts `MainActivity`
  (`EXTRA_FROM_CAST`). `OverlayAnchor` keeps a 1×1 overlay window while the permission is granted (Android 15 needs a
  visible one). When a cast-opened playback ends, the activity moves its task to the back.
- `PlayerScreen`: video + subtitles, a spinner while loading/buffering, an error message; nothing focusable.
  Back stops playback; media keys toggle play/pause (`ALLOW_REMOTE_MEDIA_KEYS`); D-pad does nothing; immersive mode.

## Request pipeline (UcpProtocolHandler)
size → strict decode → duplicate-id cache → sequence → connection phase → permission → payload validation → execute.
Responses allocate their sequence number after routing so events emitted during a request order correctly.

## Threading
Server threads handle protocol (`UcpServer` also runs a 100 ms write watchdog that works around a lost-wakeup race in
Java-WebSocket 1.6.0's NIO write path); ExoPlayer is touched only on the main thread through a synchronous `onMain` bridge;
engine events are re-dispatched on a dedicated executor to avoid main/network lock inversion.

## Extension points
`PlaybackEngine` (new playback backends), `Capabilities.features`/`playbackMethods` (browser casting added the
`media-headers` and `subtitles` features; the method is still `direct-url`), `Method`/`Permissions.requiredFor` (new protocol methods), `ServiceAdvertiser`.
