---
name: klardrop-android-control
description: >
  Drive a USB-connected Klardrop Android debug build from the host terminal
  without tapping the device. adb forward + loopback HTTP on device:8766 maps
  onto the same DiscoveryController methods the Compose buttons call. Use when
  autonomously testing pairing/transfers against a phone; granting runtime
  permissions; isolating klardrop/nearby/BLE via klardrop-debug.json; or when
  the user runs /klardrop-android-control.
---

# Klardrop Android autonomous control

Do not tap the phone. Do not ask the user to click Pair / Send / Accept.
Drive everything through `scripts/klardrop-ctl android …` after `adb devices` shows a device.

## Package / ports

| | |
|---|---|
| Debug applicationId | `com.carlom.klardrop.debug` |
| Activity | `com.carlom.klardrop.android.MainActivity` |
| Control port on device | `8766` (loopback) |
| Host access | `adb forward tcp:8766 tcp:8766` then `http://127.0.0.1:8766` |

Release (`com.carlom.klardrop`) does **not** start the control server. Always install the debug variant.

## Launch

```
adb devices          # must list a device
scripts/klardrop-ctl android launch-android
scripts/klardrop-ctl android launch-android --klardrop-only
scripts/klardrop-ctl android launch-android --nearby-only
scripts/klardrop-ctl android launch-android --ble-only
```

That installs `:android:installDebug`, grants runtime permissions, writes `files/klardrop-debug.json`, force-stops, starts the activity, and forwards the port. Wait until `scripts/klardrop-ctl android health` returns `"ok":true`. Confirm `/state.protocols` matches the isolation flag — a previous `--nearby-only` process will still be running if you only `installDebug` without force-stop.

`KlarDropApplication` reads the JSON **in `onCreate`**, so protocol flags only take effect on a cold start after the file is in place. Changing transports = rewrite config + `am force-stop` + `am start`. Never write the JSON *after* start and expect the running process to pick it up.

JSON shape (`<filesDir>/klardrop-debug.json`):

```
{"controlPort":8766,"enableKlardrop":true,"enableNearby":false,"enableBle":false}
```

Rewrite without a full install: `scripts/klardrop-ctl android write-android-config --klardrop-only` then `stop-android` / start again.

## Permissions

`launch-android` already `pm grant`s:

- `POST_NOTIFICATIONS`
- `BLUETOOTH_SCAN` / `ADVERTISE` / `CONNECT`
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`
- `NEARBY_WIFI_DEVICES`

If `/state` shows a permissions banner or BLE never appears, re-run `scripts/klardrop-ctl android grant-android-permissions` and `refresh-permissions`. Some OEMs still need a one-time Settings toggle the agent cannot flip — record that as a blocker, don't stall.

Keep the phone unlocked and the activity in the foreground. Pairing dialogs still fire as system notifications when backgrounded, but `accept-pair` goes through the in-app `pendingPairings` map which is populated either way.

## Drive the UI-equivalent actions

Same command set as desktop, target `android`:

```
scripts/klardrop-ctl android state
scripts/klardrop-ctl android pair <deviceId>
scripts/klardrop-ctl android accept-pair <deviceId>
scripts/klardrop-ctl android send-text <deviceId> "hello"
scripts/klardrop-ctl android unpair <deviceId>
scripts/klardrop-ctl android accept-incoming --device <deviceId>
scripts/klardrop-ctl android reset-identity
scripts/klardrop-ctl android wait-for-device <id> 45
scripts/klardrop-ctl android wait-for-reachable <id> 45
scripts/klardrop-ctl android wait-for-pairing-dialog 30
scripts/klardrop-ctl android wait-for-trusted <id> 45
```

Device ids in `/state` are the 8-char short ids. Match by prefix.

For `send-file`, the path is **on the device**. Push first:

```
adb push /tmp/klardrop-e2e.txt /sdcard/Download/klardrop-e2e.txt
scripts/klardrop-ctl android send-file <deviceId> /sdcard/Download/klardrop-e2e.txt
```

## Simulate reinstall

Preferred (control server stays up):

```
scripts/klardrop-ctl android reset-identity
```

Then re-read `/state` for the new `self.deviceId`.

Hard wipe (`pm clear` drops identity **and** `klardrop-debug.json`):

```
adb shell pm clear com.carlom.klardrop.debug
scripts/klardrop-ctl android write-android-config --klardrop-only
scripts/klardrop-ctl android launch-android --klardrop-only
```

`launch-android` reinstalls if needed, rewrites config, grants permissions, starts.

## Logs

Always capture both when a case fails:

```
scripts/klardrop-ctl android collect-logs /tmp/klardrop-e2e-logs
adb logcat -d -v time | tee android-logcat.txt
```

Useful tags: `Klardrop`, `DiscoveryController`, `PairingProtocolCoordinator`, `MessagesRouter`, `TrustManager`, `DebugControl`, `DiscoveryNetwork`, `Messenger`, `Client`, `Server`.

## Do not

- Tap the device, even via `adb shell input tap`, unless `/health` is down and you are diagnosing a control-server bind failure
- Use the Play-store package `com.carlom.klardrop` (no control server)
- Assume IPv6/link-local endpoints will connect — the unified server binds IPv4 (`0.0.0.0`)
- Leave another Klardrop build (Play / old debug) running on the phone; `am force-stop` both package names if discovery shows duplicates
