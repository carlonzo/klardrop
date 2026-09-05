---
name: klardrop-connection-tests
description: >
  Matrix of Klardrop connection/pairing/transfer test cases across transports
  (klardrop TCP, Nearby Share, BLE), pairing, disconnect, unpair-while-offline,
  and identity-rotation (simulated reinstall). Use when executing autonomous
  desktop↔Android tests, spawning one agent per case, or when the user runs
  /klardrop-connection-tests.
---

# Klardrop connection test matrix

Load `klardrop-desktop-control` and `klardrop-android-control` before running
anything. One agent per case. Each agent returns a report; it does not fix
code. The parent reads the reports, then writes unit tests and fixes.

## Preconditions (parent, once)

1. `adb devices` lists exactly one phone. Unlock it.
2. Isolated desktop data dir: `/tmp/klardrop-e2e-desktop` (wipe it between protocol groups if identity must be fresh).
3. Both apps built. Control servers: desktop `:8765`, android `:8766` via `adb forward`.
4. Phone and desktop on the same LAN (or BLE in range for BLE-only cases).
5. No extra Klardrop instances (second desktop, Play-store app, leftover `:cli`).

Launch both sides with the **same** isolation flag for the group (`--klardrop-only`, `--nearby-only`, or `--ble-only`). Confirm `/state.protocols` matches on both before case 1 of that group. A leftover `--nearby-only` desktop from a previous run is the usual "android can't see desktop" false alarm — `stop-desktop` + relaunch both with matching flags.

## Report format (every case)

Write `/tmp/klardrop-e2e-logs/<case-id>/REPORT.md` plus `desktop-state.json`, `android-state.json`, `desktop-logs.json`, `android-logcat.txt` (and control `/logs` from both).

```
# <case-id>
Result: PASS | FAIL | BLOCKED
Transports: klardrop | nearby | ble
Steps actually run: …
Final desktop /state: (paste)
Final android /state: (paste)
Bug: (one paragraph, or "none")
Suspected code: (file:symbol, or "unknown")
```

FAIL = assertion missed. BLOCKED = could not even set up (no device in list after 45s, control server down, permission OEM wall). Do not retry a BLOCKED case more than once.

## Assertions cheat-sheet

| Phrase in the case | How to check |
|---|---|
| A sees B | `wait-for-device` on A for B's `self.deviceId` |
| A reachable to B | `wait-for-reachable` (green/Reachable, not Probing/Unknown) |
| Paired | both `/state` have the peer in `trustedIds` **and** `trustStatus=trusted` |
| Unpaired | peer absent from `trustedIds` and `trustStatus` is `untrusted` (or missing) |
| Text arrived | receiver `/logs` or chat not exposed — use `/state.incoming` then `accept-incoming`, then `/logs` containing the text, or a follow-up `/state` with no pendingAuth failure |
| `send-text` returned | ctl waits for the ACK. Untrusted send without `accept-incoming` on the receiver times out (~15s) as FAIL — that is not "text never left" |
| Pairing dialog shown | `wait-for-pairing-dialog` on the acceptor |
| Pairing request never arrived | 20s timeout on `wait-for-pairing-dialog` **and** acceptor `/logs` have no `onPairingRequested` |

Untrusted text still requires `accept-incoming` on the receiver (IncomingAuthorizer). Trusted text does not.

## Case matrix

Transports T ∈ {`klardrop`, `nearby`, `ble`}. Run the T-group with `--<T>-only` on **both** sides. Skip a T-group only if launch reports BLE unsupported (`/state` never shows `connectionTypes` containing `BLE` after 30s of BLE-only) — mark the group BLOCKED, do not fake it on Wi-Fi.

### Group D — discovery (no pairing yet)

| ID | Steps | Expect |
|---|---|---|
| D1-T | Launch both. Wait 45s. | Each `/state.devices` contains the other. `connectionTypes` includes T. |
| D2-T | D1, then inspect reachability for 20s. | Peer is `reachable` (not stuck `probing` / `unknown` / `unreachable`). |
| D3-T | Force-stop Android 10s, keep desktop up. | Desktop still lists the phone, reachability becomes `unreachable` (or the row stays as trusted-offline if previously paired — for this group it should drop or go unreachable). |
| D4-T | D3, then relaunch Android. | Desktop sees the phone again within 45s, reachability returns to `reachable`. |

### Group P — pair / unpair / re-pair

| ID | Steps | Expect |
|---|---|---|
| P1-T | From desktop `pair` android. Android `wait-for-pairing-dialog`, `accept-pair`. | Both trusted within 15s. |
| P2-T | Reverse of P1 (android initiates). | Both trusted. |
| P3-T | P1, then desktop `unpair` while both online. | Both unpaired within 10s (revocation delivered). Subsequent `send-text` is treated as untrusted (incoming prompt on receiver). |
| P4-T | P1, unpair, pair again (same initiator). | Second pairing shows a dialog; both trusted afterwards. |
| P5-T | P1, unpair, pair with the **other** side initiating. | Same as P4. |
| P6-T | `pair` then `reject-pair` on the acceptor. | Both remain untrusted. Initiator has a pairingError. |

### Group U — unpair while the peer is offline (the reported bug)

| ID | Steps | Expect |
|---|---|---|
| U1-T | Pair (P1). Force-stop Android. Desktop `unpair`. | Desktop immediately unpaired (`trustedIds` empty). |
| U2-T | U1, relaunch Android. | Android unpaired within 20s of coming online (revocation on next contact, or a connection-level reject). Both can `send-text` as **untrusted** (prompt, not a hung send). |
| U3-T | Pair. Force-stop **desktop**. Android `unpair`. Relaunch desktop. | Symmetric of U2. |
| U4-T | U2, then pair again. | Pairing dialog appears; both trusted. No stale-identity conflict. |

### Group I — identity change / simulated reinstall (the reported bug)

| ID | Steps | Expect |
|---|---|---|
| I1-T | Pair. Android `reset-identity`. | New android `self.deviceId`. Desktop still lists a stale trusted row for the **old** id and a new untrusted row for the new id (or the old row goes away). They disagree on pairing. |
| I2-T | I1, desktop `send-text` to the **new** id. | Transfer allowed as untrusted (incoming prompt on android). Must **not** silently succeed as a trusted send. Must **not** hang. |
| I3-T | I1, android `send-text` to desktop. | Same: untrusted prompt on desktop, or a reject that causes desktop to drop the old pairing. After the exchange both should no longer consider each other trusted. |
| I4-T | I3, then pair using the new ids. | Dialog on the acceptor; both trusted under the **new** android id. Old id gone from `trustedIds`. |
| I5-T | Pair. Desktop `reset-identity`. Repeat I2–I4 with roles flipped. | Symmetric. |
| I6-T | Pair. `pm clear` the android app (hard reinstall), rewrite debug config, relaunch with same `--<T>-only`. | Same expectations as I1–I4. This is the real uninstall path; I1 is the in-process shortcut. |

### Group X — transfers while paired / unpaired

| ID | Steps | Expect |
|---|---|---|
| X1-T | Unpaired. Desktop `send-text`. Android `accept-incoming`. | Text accepted; no pairing created as a side effect. |
| X2-T | Paired. Desktop `send-text`. | No incoming prompt (auto-accept). Android `/logs` show the text handler completing. |
| X3-T | Paired. `adb push` a >1MB file, `send-file`. | Receiver completes without hanging; progress may be imperfect (known F14) — still record whether it finished. |
| X4-T | Mid-transfer, toggle Wi-Fi off on android 5s then on (skip for BLE-only). | Either resumes or fails cleanly (`Failed`), never stuck `sending…`. |

### Group C — combined / stress

| ID | Steps | Expect |
|---|---|---|
| C1-T | P1 → unpair → P1 → U1 → U2 → I1 → I4 | Each step's expect still holds; no "ghost" trusted row. |
| C2 | Launch **without** isolation (all transports on). D1+P1+X2. | At least one `connectionTypes` entry; pair and trusted send work. Record which transport was used. |
| C3 | Desktop `--klardrop-only`, android `--nearby-only`. Wait 45s. | They should **not** see each other (or see but never `reachable`). Documents cross-protocol isolation. |

## Execution order for a spawned agent

1. Read this skill + the two control skills.
2. Take **one** case id (e.g. `P1-klardrop`).
3. If this is the first case of a T-group, (re)launch both apps with that isolation flag and wipe `/tmp/klardrop-e2e-desktop` if the group requires a clean identity (D, P, I, U, C1).
4. Run the steps only through `klardrop-ctl`. Sleep only via the `wait-for-*` commands.
5. On FAIL/BLOCKED dump logs immediately (`collect-logs` both sides).
6. Return the REPORT.md body as the agent result. Do not start the next case.

## Known related code (for the report's "suspected code", not for the agent to patch)

- Discovery: `DiscoveryNetwork`, `EagerReachabilityConnector`, `VisibleDevices`
- Pair/unpair: `DiscoveryController.onAddToTrusted` / `onForgetDevice`, `PairingProtocolCoordinator`, `TrustManager`
- Offline unpair healing: `MessagesRouter.onMessageIncoming` unknown-sender → `TrustRevocationMessage` reason `device_unknown`. There is **no** wire frame named `forbidden` today.
- Identity: `CurrentDeviceProvider.rotateDeviceId`, `TrustManager.resetIdentity`
- Authorizer: `IncomingAuthorizer` (untrusted text/file prompt)
