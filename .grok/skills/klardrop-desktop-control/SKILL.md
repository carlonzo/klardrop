---
name: klardrop-desktop-control
description: >
  Drive the Klardrop JVM desktop app from the terminal without tapping the UI.
  Loopback HTTP on 127.0.0.1:8765 maps onto the same DiscoveryController methods
  the Compose buttons call. Use when autonomously testing discovery, pairing,
  transfers, or unpair against the desktop app; when launching with --debug /
  --klardrop-only / --nearby-only / --ble-only; or when the user runs
  /klardrop-desktop-control.
---

# Klardrop desktop autonomous control

Do not tap the window. Do not ask the user to click Pair / Send / Accept.
Drive everything through `scripts/klardrop-ctl desktop …`.

## Isolated data dir (mandatory)

Never launch against the developer's real `~/.klardrop`. Always:

```
KLARDROP_HOME=/tmp/klardrop-e2e-desktop
```

`--data-dir=` and `KLARDROP_HOME` / `klardrop.data.dir` all root identity, trust, and FileKit dirs there.

## Launch

From the repo root:

```
scripts/klardrop-ctl desktop launch-desktop
scripts/klardrop-ctl desktop launch-desktop --klardrop-only
scripts/klardrop-ctl desktop launch-desktop --nearby-only
scripts/klardrop-ctl desktop launch-desktop --ble-only --data-dir /tmp/klardrop-e2e-desktop
```

Equivalent gradle (only if the script is the wrong shape):

```
./gradlew :desktop:run --args="--debug --data-dir=/tmp/klardrop-e2e-desktop --control-port=8765 --klardrop-only"
```

Flags (processed in Main.kt):

| Flag | Effect |
|---|---|
| `--debug` | Starts the control server (default port 8765) |
| `--control-port=N` | Override port; `0` disables |
| `--data-dir=PATH` | Isolated identity/trust/db |
| `--no-klardrop` / `--no-nearby` / `--no-ble` | Disable one transport |
| `--klardrop-only` / `--nearby-only` / `--ble-only` | Enable one transport, disable the other two |
| `--no-persistence` | In-memory sqlite (not for pairing-across-restart tests) |

Wait until `scripts/klardrop-ctl desktop health` returns `"ok":true` before any other command. The Compose window must actually come up — `DebugControl.bind` runs from `KlardropApp`'s `LaunchedEffect`.

Stop with `scripts/klardrop-ctl desktop stop-desktop`.

## Drive the UI-equivalent actions

```
scripts/klardrop-ctl desktop state
scripts/klardrop-ctl desktop pair <deviceId>
scripts/klardrop-ctl desktop accept-pair <deviceId>
scripts/klardrop-ctl desktop reject-pair <deviceId>
scripts/klardrop-ctl desktop send-text <deviceId> "hello"
scripts/klardrop-ctl desktop send-file <deviceId> /tmp/klardrop-e2e.txt
scripts/klardrop-ctl desktop unpair <deviceId>
scripts/klardrop-ctl desktop accept-incoming --device <deviceId>
scripts/klardrop-ctl desktop reset-identity
scripts/klardrop-ctl desktop logs
```

Waits (poll `/state`, fail with the last snapshot on timeout):

```
scripts/klardrop-ctl desktop wait-for-device <id> 45
scripts/klardrop-ctl desktop wait-for-reachable <id> 45
scripts/klardrop-ctl desktop wait-for-pairing-dialog 30
scripts/klardrop-ctl desktop wait-for-incoming 30
scripts/klardrop-ctl desktop wait-for-trusted <id> 45
```

`/state` is the source of truth: `self.deviceId` (8-char short id), `devices[]` (`trustStatus`, `reachability`, `connectionTypes`, `pairingError`), `pairingDialog`, `incoming[]`, `trustedIds`, `protocols`.

Pairing a peer: `pair` on the initiator, then `wait-for-pairing-dialog` + `accept-pair` on the acceptor. Untrusted text/file also raises `incoming[]` — `accept-incoming` is the banner Accept button.

## Simulate reinstall

Preferred (in-process, control server stays up):

```
scripts/klardrop-ctl desktop reset-identity
```

This rotates the short device id, wipes the trust store, and generates a new signing key. Discovery republishes via `deviceInfoFlow`. Capture the new `self.deviceId` from `/state` afterwards.

Hard wipe (closer to uninstall; control server dies):

```
scripts/klardrop-ctl desktop stop-desktop
rm -rf /tmp/klardrop-e2e-desktop
scripts/klardrop-ctl desktop launch-desktop --klardrop-only
```

## Logs

- Control ring: `scripts/klardrop-ctl desktop logs` (same lines `log()` writes)
- Process stdout/stderr of `:desktop:run` (mDNS, UKEY2, `[DEBUG]` router)
- Dump both into the test-case report directory when a case fails

## Do not

- Launch a second desktop instance on the same data dir (SingleInstance will FOCUS and exit)
- Use the CLI module (`:cli:jvmRun`) as a stand-in for the desktop app — it is a different process with no Compose UI and no control server
- Click the window, even if it is visible
- `pkill -f` / `pgrep -f` on `/tmp/klardrop-e2e-desktop`, `:desktop:run`, `gradlew`, or `MainKt` — those strings match the **agent shell** and kill the session. Stop only with `scripts/klardrop-ctl desktop stop-desktop`
