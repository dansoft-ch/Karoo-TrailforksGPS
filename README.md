# TrailforksFusedGPS

*Deutsche Fassung: [README.de.md](README.de.md)*

A small Android app for the **Hammerhead Karoo 3** that makes **sideloaded map apps such as Trailforks**
follow your position continuously. It forwards the Karoo's own GPS fixes to Android's **"fused"**
location provider, which is broken on the Karoo.

Package: `ch.dansoft.karoofusedgps` · Display name: **TrailforksFusedGPS** (sorts right after
Trailforks in the Karoo app list)

## The problem

Trailforks can be sideloaded onto the Karoo 3 and shows the map, but **your position does not
update**. The location dot only jumps to the current position when you switch the display off and on
again, then it freezes.

Things that do *not* fix it:

- **Location permission:** worth checking anyway. On our unit Android 12 had silently auto-revoked
  Trailforks' location permission because the app had not been used for months. Granting it again
  gave a single fix after display off/on, but no continuous updates.
- **Installing Google Play Services or microG:** not needed (see below), and microG would require
  signature spoofing, which the stock Karoo firmware does not offer.
- **A generic "mock location" app:** these apps inject fake or replayed positions, not the Karoo's real
  GPS. Most of them also replace the `gps` provider, so the Karoo ride app would record fake data.

## Root cause

Diagnosed with `adb shell dumpsys location` on a Karoo 3 (Android 12, build `1.665.2548.20`):

| Provider | Implemented by | Delivers fixes? |
|---|---|---|
| `gps` | GNSS chip, requested by `io.hammerhead.locationservice` | **yes**, about 1 Hz |
| `fused` | Qualcomm `com.qualcomm.location` (`izatprovider.FusedLocationService`) | **never** (`last location=null`, 0 locations) |
| `network` | Qualcomm `com.qualcomm.location` | never |

- **Trailforks**, through its Mapbox location engine, listens to the **`fused`** provider for
  continuous updates. It only asks `gps` directly for a short burst (about 30 s) when the app
  resumes. That burst is exactly why display off/on gives one fresh position.
- On the Karoo, the Qualcomm fused provider is enabled and bound, but it **never produces a
  location**. It apparently relies on Qualcomm location services that the Karoo does not use.
- The Hammerhead ride app and Locus Map use the `gps` provider directly. That is why they work fine.
- When the Hammerhead ride app is **not** in the foreground, the system only requests GPS every
  **30 minutes**. Listening passively is therefore not enough while Trailforks is on screen.
- The Qualcomm package cannot be disabled without root: it is `PERSISTENT` and comes back after a
  reboot, and `pm disable` of the single component is refused for the shell user.

**Proof:** registering a test provider named `fused` via adb
(`cmd location providers add-test-provider fused` plus `set-test-provider-location`) made
Trailforks follow the simulated position continuously, with no display off/on needed.

## The solution: this app

TrailforksFusedGPS replaces the broken `fused` provider with Android's own mechanism for that, a
**test provider** (mock location), and feeds it the real GPS fixes:

1. **GPS requests:** while the display is on, it requests `gps` at 1 Hz. If the Karoo is already
   tracking, this adds practically no cost.
2. **Forwarding:** every real GPS fix is copied 1:1 into the `fused` test provider, with accuracy,
   altitude, speed, bearing and timestamps.
3. **Only `fused` is replaced.** The `gps` provider is untouched, so the Karoo ride app, ride
   recording and Locus Map behave exactly as before.
4. **Display off:** by default forwarding continues while the display is off, so a ride recorded
   *in Trailforks* has no gaps. To save a little battery, turn off **"Auch bei Display aus
   weiterleiten"** (forward when display is off). The GPS request then pauses while the display is
   dark and resumes as soon as it turns on. A ride recorded by the Karoo ride app is unaffected either
   way, because the Karoo requests GPS itself.
5. **Autostart:** by default it starts automatically at boot (`LOCKED_BOOT_COMPLETED` and
   `BOOT_COMPLETED`, with up to three attempts). The switch **"Autostart beim Einschalten"** turns
   this off. After an app update it always restarts. It runs as a small foreground service with a
   silent notification.
6. **Off switch:** the switch **"Weiterleitung GPS → fused"** removes the test provider again, which
   restores the original state.

The switches use the Karoo style: black knob when on, light grey when off.

The app screen shows whether the fused replacement is active, whether GPS is being requested, how
many fixes have been forwarded and the age of the last fix. Each step is logged:

```
adb logcat -s KarooFusedGPS
```

## Tested with

| Component | Version |
|---|---|
| Hammerhead Karoo 3 firmware | 1.665.2548.20 |
| Android | 12 (API 32) |
| Trailforks (sideloaded) | 2024.10.1 (versionCode 19168404) |
| TrailforksFusedGPS | 1.4 (tag `v1.4`), tested 21.11.2026 |

Result: after a reboot the service starts on its own, and Trailforks follows the position
continuously. The Karoo ride app and Locus Map are unaffected. Other Trailforks or Karoo versions
may behave differently. Check with `adb shell dumpsys location` whether Trailforks registers on the
`fused` provider.

## Installation (adb)

Requirements: USB debugging enabled on the Karoo, a USB cable, and
[Android platform-tools](https://developer.android.com/tools/releases/platform-tools)
(on Windows: `winget install Google.PlatformTools`).

```
adb install -r apk/karoo-fused-gps.apk
adb shell pm grant ch.dansoft.karoofusedgps android.permission.ACCESS_FINE_LOCATION
adb shell pm grant ch.dansoft.karoofusedgps android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant ch.dansoft.karoofusedgps android.permission.ACCESS_BACKGROUND_LOCATION
adb shell appops set ch.dansoft.karoofusedgps android:mock_location allow
adb shell am start -n ch.dansoft.karoofusedgps/.MainActivity
```

The `appops` line does the same as *Developer options → Select mock location app →
TrailforksFusedGPS*. Background location is needed because the service starts at boot, while the
app is not in the foreground.

Also make sure **Trailforks itself has location permission**. Android may auto-revoke it after
months without use:

```
adb shell pm grant com.pinkbike.trailforks android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.pinkbike.trailforks android.permission.ACCESS_COARSE_LOCATION
adb shell appops set --uid com.pinkbike.trailforks AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore
```

Notes:

- After a reboot the GPS needs a sky view to get a fix. Indoors this can take many minutes.
- Do not "force stop" the app. Android then treats it as stopped and blocks the autostart until you
  open the app once by hand.

## Uninstall / revert

Turn the switch off in the app, or uninstall it completely:

```
adb uninstall ch.dansoft.karoofusedgps
```

After that, `fused` is served by the Qualcomm provider again, and everything is exactly as before.

## Building

There is no local Android SDK needed. GitHub Actions (`.github/workflows/build.yml`) builds the APK
on every push and commits it as `apk/karoo-fused-gps.apk`.

- Plain Kotlin, no AndroidX dependencies. `minSdk` 26, `targetSdk` 34. Gradle 8.7, AGP 8.5.2.
- Source: `ForwardService.kt` (forwarding), `BootReceiver.kt` (autostart), `MainActivity.kt`
  (switch and status).
- **Signing:** builds are signed with the key in `keystore/`. The password is in
  `app/build.gradle.kts`, so updates install over older versions. The key is public, so **if you fork
  this, generate your own keystore**.

## Disclaimer

This is a personal workaround, not affiliated with Hammerhead, SRAM, Trailforks or Pinkbike. It
uses Android's mock location feature, so apps see the forwarded positions marked as mock locations.
Trailforks accepts them. Use at your own risk.
