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
- `sender.MediaSniffer` – recognises stream URLs (HLS/DASH/progressive, ignoring segments), confirms them by content
  (`#EXTM3U`, `<MPD`, Content-Type) and parses HLS master playlists (qualities) and media playlists (duration).

Cast browser (`sender-app/.../web`): `BrowserScreen` is a hardened WebView. `shouldInterceptRequest` (plus a
service-worker client) shows it every request the page and its iframes make. Hits go to `MediaCandidates`, which
probes each one in the background (`StreamProbe`, using the page's headers), hides HLS variant playlists covered
by a master, drops non-media, and ranks the rest by duration/size. Casting sends
`media.load {uri, mimeType, title, headers}`. The receiver (`Media3PlaybackEngine`) builds a per-item
`DefaultHttpDataSource` with those headers, so they apply to playlists, segments and AES keys.

## Request pipeline (UcpProtocolHandler)
size → strict decode → duplicate-id cache → sequence → connection phase → permission → payload validation → execute.
Responses allocate their sequence number after routing so events emitted during a request order correctly.

## Threading
Server threads handle protocol; ExoPlayer is touched only on the main thread through a synchronous `onMain` bridge;
engine events are re-dispatched on a dedicated executor to avoid main/network lock inversion.

## Extension points
`PlaybackEngine` (new playback backends), `Capabilities.playbackMethods` (browser casting would add a method and
its own permission), `Method`/`Permissions.requiredFor` (new protocol methods), `ServiceAdvertiser`.
