# Building

## Requirements
- JDK 17 or newer (built and tested with JDK 21), Android SDK **platform 37** (`compileSdk 37`) and a current build-tools release.
- Toolchain: Gradle 9.7.1 (wrapper), Android Gradle Plugin 9.1.1, Kotlin 2.3.21 with the Compose compiler plugin, Compose BOM 2026.11.01 (Material 3 1.5.0), TV Material 1.1.0. Use a recent Android Studio (one that supports AGP 9.1).
- Set `sdk.dir` in `local.properties` (Android Studio does this automatically).
- Internet access for Gradle (Google Maven + Maven Central).

## Commands (Windows: use `gradlew.bat`)
```
./gradlew test                            # all unit tests (:core, :app, :sender-app, :updater)
./gradlew :core:test                      # JVM tests for the protocol/security/proxy core
./gradlew :app:testDebugUnitTest          # receiver app unit tests
./gradlew :sender-app:testDebugUnitTest     # sender app unit tests
./gradlew :updater:testDebugUnitTest        # auto-updater and Markdown parser unit tests
./gradlew :app:assembleDebug              # TV receiver debug APK (applicationId …receiver.debug, mode=development)
./gradlew :sender-app:assembleDebug       # phone app debug APK (applicationId com.universalcast.sender.debug)
./gradlew assembleRelease                 # both release APKs, minified, mode=production (signed when keystore.properties exists)
./gradlew :app:connectedDebugAndroidTest  # Keystore tests on a device/emulator (API 24+)
```
Open the project folder in Android Studio and pick the `app` run configuration to install on an Android TV
device/emulator (API 24+, Android TV system image).

## Installing
- TV: `adb connect <tv-ip>:5555` then `adb install app/build/outputs/apk/debug/app-debug.apk` (enable developer options + network debugging on the TV).
- Phone: `adb install sender-app/build/outputs/apk/debug/sender-app-debug.apk`, or copy the APK to the phone.
- Phone and TV must be on the same Wi-Fi (client isolation / "guest network" breaks discovery and connections).
- TV app needs Android 7.0 (API 24)+ (raised from 6.0 in 1.2.0). Phone app needs Android 8.0 (API 26)+; it asks for the camera only when you scan a QR code.

## Signing the release build
In-place updates only work if every release is signed with the **same key**. Create one once and keep it safe
(losing it means users must uninstall to update):
```
keytool -genkeypair -v -keystore universalcast-release.jks -alias universalcast -keyalg RSA -keysize 2048 -validity 10000
```
Then create `keystore.properties` in the project root (git-ignored, never commit it or the `.jks`):
```
storeFile=universalcast-release.jks
storePassword=...
keyAlias=universalcast
keyPassword=...
```
`assembleRelease` then produces signed APKs. Without that file the release APKs stay unsigned.

## Releasing an update (auto-updater)
Both apps check GitHub's latest release on start (at most once an hour) and offer to download and install it.
Debug builds never update (different applicationId).

1. Bump `uc.versionName` (e.g. `1.2.2`) and `uc.versionCode` (+1, e.g. `16`) in `gradle.properties`. Both apps share them.
2. `./gradlew assembleRelease` with `keystore.properties` in place (current version: `1.2.2`, `versionCode=16`).
3. Create a GitHub release tagged `v<versionName>` (e.g. `v1.2.1`, not a draft or pre-release) and attach the two APKs,
   renamed so the names start with:
   - `universalcast-receiver` (from `app/build/outputs/apk/release/app-release.apk`), e.g. `universalcast-receiver-1.2.2.apk`
   - `universalcast-sender` (from `sender-app/build/outputs/apk/release/sender-app-release.apk`), e.g. `universalcast-sender-1.2.2.apk`
   (Optional: keep copies locally under the git-untracked `release/` folder.)
4. The GitHub release notes become the "what's new" text in the update dialog, rendered with Markdown formatting (`MarkdownParser`).

The repository must be **public** (the apps call the unauthenticated GitHub API). The updater only downloads over
https from github.com / githubusercontent.com, checks GitHub's SHA-256 digest of the asset, checks the APK is the same
app and a higher versionCode, and Android itself refuses an APK signed with a different key. The first install of each
app still has to be done by hand; the user must also allow "install unknown apps" for UniversalCast once.

## Dev vs prod
`BuildConfig.OPERATING_MODE` is `development` (debug) or `production` (release). The mode only changes labels and
the mDNS `mode` TXT value. **Plaintext and unauthenticated endpoints do not exist in any mode**: `ConfigValidator`
refuses to start with TLS or authentication disabled, and port 0 is only accepted in development (tests).

## Trying it without a sender app
`core` contains both a reference sender (`com.universalcast.sender.UcpClient`, `PairingClient`, used by the integration
tests) and a pure-JVM **Desktop CLI** (`com.universalcast.cli.UniversalCastCli`) supporting `pair`, `cast`,
`cast-file` (streams local files via `MediaProxyServer`), `key`, `text`, `status`, and `stop`.
You can also test phone features without a physical TV using the **Developer Dummy TV Simulator** (in the phone app:
`⋮` → *About UniversalCast* → tap the **Version** card 5 times).

## Testing with the TV emulator and a physical phone

The receiver listens on **8765** (not 8443: on Android TV / Google TV the built-in Chromecast receiver,
`com.google.android.apps.mediashell`, already holds 8443).

The emulator sits behind its own NAT, so a phone on Wi-Fi cannot reach it. Debug receiver builds also put
`127.0.0.1` into the pairing QR (`EXTRA_PAIRING_ADDRESSES`), so a USB-connected phone can reach it through adb:

```sh
adb -s emulator-5554 forward tcp:8765 tcp:8765   # laptop:8765 -> emulator:8765
adb -s <phone-serial> reverse tcp:8765 tcp:8765  # phone:8765  -> laptop:8765
```

On a real TV on the same Wi-Fi none of this is needed. Release builds advertise only the real LAN addresses.

## Building everything

```sh
./gradlew :core:test :app:testDebugUnitTest :sender-app:testDebugUnitTest :updater:testDebugUnitTest   # all unit tests
./gradlew assembleDebug                      # both apps, debug (installable as-is)
./gradlew :app:assembleDebug                 # TV receiver only
./gradlew :sender-app:assembleDebug          # phone sender only
./gradlew assembleRelease                    # both apps, release (minified; signed if keystore.properties is present, else unsigned)
```

## Where the APKs are

| App | Debug (use this for testing) | Release (`keystore.properties` configured / omitted) |
|---|---|---|
| TV receiver | `app/build/outputs/apk/debug/app-debug.apk` | `app/build/outputs/apk/release/app-release.apk` (or `...-unsigned.apk`) |
| Phone sender | `sender-app/build/outputs/apk/debug/sender-app-debug.apk` | `sender-app/build/outputs/apk/release/sender-app-release.apk` (or `...-unsigned.apk`) |

When `keystore.properties` is omitted, release APKs stay unsigned and Android refuses to install an unsigned APK; use
the debug APKs for local testing or configure `keystore.properties` first.

## Installing on a physical TV and phone

**Phone (USB):**
```sh
adb devices                                   # note the phone's serial
adb -s <phone-serial> install -r sender-app/build/outputs/apk/debug/sender-app-debug.apk
```

**TV (over the network):**
1. On the TV: Settings → Device Preferences → About → click "Build" 7 times to unlock Developer options.
2. Developer options → turn on "USB debugging" / "Network debugging" (the name varies by brand).
3. Find the TV's IP: Settings → Network & Internet → your Wi-Fi.
4. From the laptop:
   ```sh
   adb connect <tv-ip>:5555                    # accept the prompt on the TV
   adb -s <tv-ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   Without adb: copy the APK to a USB stick or use a "Send files to TV" app, then open it with a file manager on the TV.

**Testing live:** put the phone and the TV on the same Wi-Fi. No `adb forward`/`reverse` is needed (that's only for
the emulator). Open UniversalCast on the TV → "Pair a new device" → scan the QR code with the phone → compare
the codes → approve on the TV. The receiver listens on port 8765.

Two notes for your real TV:
- **The QR code also lists `127.0.0.1`:** the debug receiver adds it for emulator testing. On a real TV the phone fails on that address instantly and moves on to the TV's Wi-Fi address, so it's harmless.
- **Phones paired with the emulator need to pair again:** the physical TV has its own security key, so it's a separate pairing.

## TV: start on boot and open automatically when a phone casts

- **Start when the TV turns on** (Settings on the TV, default on). Open the app **once** after installing: Android
  keeps a newly installed app in the "stopped" state and delivers no boot broadcast to it until it has been opened.
  A TV in deep standby suspends all apps; the receiver comes back when the TV fully wakes or reboots.
  Test without rebooting: `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.universalcast.receiver.debug`
  (Android 15 restriction check: `adb shell am compat enable FGS_BOOT_COMPLETED_RESTRICTIONS com.universalcast.receiver.debug`).
- **Open automatically when a phone casts** needs "Display over other apps" on Android 10+. Settings on the TV shows
  the status and a button to the system screen when the TV has one. Many TVs don't; then run once:
  ```sh
  adb shell appops set com.universalcast.receiver.debug SYSTEM_ALERT_WINDOW allow   # release build: com.universalcast.receiver
  ```

## Running the tests

```sh
./gradlew :core:test :app:testDebugUnitTest :sender-app:testDebugUnitTest :updater:testDebugUnitTest
```
`:app:connectedDebugAndroidTest` / `:sender-app:connectedDebugAndroidTest` (Keystore tests) need a connected device.

## Emulator testing
Use virtual devices only (Device Manager), never a personal phone/TV that carries a release install: a phone image such as
`system-images;android-36;google_apis;x86_64` (e.g. Pixel 8) and the Android TV 1080p image. Debug builds use the
`.debug` application-id suffix; release builds are signed with the release key, so an emulator that already has a
differently signed `com.universalcast.*` install must uninstall it first.
