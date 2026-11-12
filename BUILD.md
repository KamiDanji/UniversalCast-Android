# Building

## Requirements
- JDK 17, Android SDK (platform 34, build-tools 34.x). Android Studio Koala/Ladybug or newer works.
- Set `sdk.dir` in `local.properties` (Android Studio does this automatically).
- Internet access for Gradle (Google Maven + Maven Central).

## Commands (Windows: use `gradlew.bat`)
```
./gradlew :core:test                 # JVM tests for the protocol/security core
./gradlew :app:testDebugUnitTest     # app unit tests
./gradlew :app:assembleDebug         # TV receiver debug APK (applicationId …receiver.debug, mode=development)
./gradlew :sender-app:assembleDebug  # phone app debug APK (applicationId com.universalcast.sender.debug)
./gradlew :sender-app:testDebugUnitTest
./gradlew :app:assembleRelease       # release APK, minified, mode=production, UNSIGNED
./gradlew :app:connectedDebugAndroidTest   # Keystore tests on a device/emulator (API 23+)
```
Open the project folder in Android Studio and pick the `app` run configuration to install on an Android TV
device/emulator (API 23+, Android TV system image).

## Installing
- TV: `adb connect <tv-ip>:5555` then `adb install app/build/outputs/apk/debug/app-debug.apk` (enable developer options + network debugging on the TV).
- Phone: `adb install sender-app/build/outputs/apk/debug/sender-app-debug.apk`, or copy the APK to the phone.
- Phone and TV must be on the same Wi-Fi (client isolation / "guest network" breaks discovery and connections).
- Phone app needs Android 8.0 (API 26)+; it asks for the camera only when you scan a QR code.

## Signing the release build
Create a keystore and add a `signingConfigs` block to `app/build.gradle.kts`, or sign the unsigned APK with
`apksigner`. No signing key is shipped.

## Dev vs prod
`BuildConfig.OPERATING_MODE` is `development` (debug) or `production` (release). The mode only changes labels and
the mDNS `mode` TXT value. **Plaintext and unauthenticated endpoints do not exist in any mode**: `ConfigValidator`
refuses to start with TLS or authentication disabled, and port 0 is only accepted in development (tests).

## Trying it without a sender app
`core` contains a reference sender (`com.universalcast.sender.UcpClient`, `PairingClient`) used by the integration
tests; it shows the exact client side of pairing and authentication.

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
./gradlew :core:test :app:testDebugUnitTest :sender-app:testDebugUnitTest   # all unit tests
./gradlew assembleDebug                      # both apps, debug (installable as-is)
./gradlew :app:assembleDebug                 # TV receiver only
./gradlew :sender-app:assembleDebug          # phone sender only
./gradlew assembleRelease                    # both apps, release (minified, UNSIGNED)
```

## Where the APKs are

| App | Debug (use this for testing) | Release (unsigned) |
|---|---|---|
| TV receiver | `app/build/outputs/apk/debug/app-debug.apk` | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Phone sender | `sender-app/build/outputs/apk/debug/sender-app-debug.apk` | `sender-app/build/outputs/apk/release/sender-app-release-unsigned.apk` |

Release APKs are unsigned, and Android refuses to install an unsigned APK. Use the debug APKs for testing, or
add a `signingConfig` first.

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
./gradlew :core:test :app:testDebugUnitTest :sender-app:testDebugUnitTest
```
`:app:connectedDebugAndroidTest` / `:sender-app:connectedDebugAndroidTest` (Keystore tests) need a connected device.

