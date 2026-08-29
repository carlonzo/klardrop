# Connection & Transfer Review

Review of the discovery → connection → transfer pipeline, driven by three observed symptoms
(two Android devices, same LAN, 10 cm apart):

1. Devices appear in the list with **no status badge**, or stuck on **orange "Connecting"**.
2. A sent text **appears "sent" immediately** with no progress/feedback, while the receiver shows nothing.
3. Connections take **10–20 s** before the receiver's accept prompt appears.

All file paths are relative to the repo root, under
`common/src/commonMain/kotlin/com/carlom/klardrop/common/` unless noted.

## Pipeline overview

```
ServiceDiscoveryMdns → DiscoveryNetwork → VisibleDevices (StateFlow<Map<shortId, DiscoveryDevice>>)
                                               │
        EagerReachabilityConnector (probes) ───┤
                                               ▼
        ConnectionsPool.reachability: Unknown | Probing | Reachable | Unreachable
                                               ▼
        ShowDevicesControllerHelper → DeviceUi → StatusDot (badge)
```

- Badge mapping (list, `common-ui/.../discovery_screen.kt:860`): Reachable→green, Unreachable→red,
  **Probing/Unknown→no dot**. Chat header (`DeviceChatScreen.kt:151`): **Probing/Unknown→orange**.
- Outbound dials happen in two places only: `EagerReachabilityConnector` (on discovery) and
  `Messenger.send` (on user send). **Opening a device's chat screen does not dial.**

## Findings

### Symptom 1 — missing / stuck-orange badge

- **F1. Peers without a Klardrop TCP endpoint are never probed.**
  `communication/EagerReachabilityConnector.kt:70` skips them; reachability stays `Unknown`
  forever → no badge in list, orange in chat. Happens when the device is visible via another
  announce but its Klardrop SRV hasn't resolved; the browse restart that would fix it is
  debounced 30 s with a 60 s backstop (`discovery/DiscoveryNetwork.kt:500,507`).
- **F2. `ConnectOutcome.NotInitiated` leaves reachability at `Probing` forever.**
  `EagerReachabilityConnector.kt:114-119` + `communication/Client.kt:167-175` (BLE non-initiator).
  Nothing ever times Probing out → permanent orange "Connecting".
- **F3. No Probing watchdog at all.** `Probing` only exits via explicit
  `updateConnection`/`markUnreachable`; any probe path without a terminal call wedges the badge.
- **F4. Stale-endpoint eviction can drop the whole device.** Dial refused/timeout →
  `invalidateKlardropEndpoint` (`Client.kt:156-162`); if it was the last endpoint the device
  vanishes from the list until the debounced browse restart re-finds it (flicker in/out).
- **F5. Network change flushes reachability to `Unknown`** (`communication/ConnectionsPool.kt:254`)
  → badge disappears until the next probe lands (green → none → green flicker).

### Symptom 3 — 10–20 s to connect / accept prompt

The receiver's accept dialog appears only after the full dial + greeting + UKEY2 handshake +
first message frame reaches `IncomingAuthorizer`, so every second below lands directly on it.

- **F6. Android advertises IPv6 endpoints that are dialed and black-hole. Prime suspect.**
  `discovery/NearbyShareDiscoveryUtils.kt:181` (`isReachableAddress`) filters loopback/CGNAT but
  **not IPv6**. Android API 34+ `hostAddresses` includes link-local `fe80::` (no zone id → not
  dialable). Worse, the server binds `0.0.0.0` (IPv4 only, `Server.kt:106`) so **no** IPv6
  address of a peer is ever connectable — every advertised IPv6 endpoint burns a full 3 s
  connect timeout (up to 9 s if it stalls mid-greeting). Apple/desktop mDNS keep IPv4 only;
  this is Android-specific — and both test devices are Android.
- **F7. Sequential dialing, no racing, no address preference.** `Client.kt:139` tries each
  endpoint one at a time, each with three chained `withTimeout(3s)` phases
  (connect/greeting-write/greeting-read, `Client.kt:204,229,245`). N bad addresses = N×3 s
  minimum before the good one is tried.
- **F8. No per-device dial lock → duplicate-dial race that kills a live connection.**
  ERC probe and a user send can both pass the single `isAvailable` check (`Client.kt:116`) and
  dial concurrently. Both client-side messengers have `initiatedByUs=true`, so
  `ConnectionsPool.updateConnection` treats the second as a "reconnect" and **closes the first
  socket that was already returned as Connected** (`ConnectionsPool.kt:197-206`). The caller
  believes it is connected on a dead socket; the next send hits the ACK timeout → retry loop.
- **F9. Untimed UKEY2 handshake.** `Client.kt:270` and `Server.kt:248` run
  `KlardropEncryptedTransport` handshakes with no `withTimeout`; a stalled peer hangs until the
  outer 15 s `CONNECTION_WAIT_TIMEOUT` (`Messenger.kt:443`).
- **F10. Worst-case send budget compounds.** 15 s wait loop × up to 3 Messenger attempts with
  1.0/1.5 s backoff (`Messenger.kt:226-306`) → >20 s before hard failure. The retry `delay`
  also runs on the main dispatcher (`Messenger.kt:285`).
- **F11. ERC cooldown thrash.** Failed probe → 5 s cooldown → re-probe repeats the same doomed
  sequential dial; combined with F6 a peer cycles Probing→Unreachable→Probing.

### Symptom 2 — "sent" with no feedback

The wire protocol is sound: a text send genuinely awaits `ACK_RECEIVED` (5 s timeout;
`ACK_AWAITING_USER` extends to 5 min) before reporting `Completed`. The persistence/UI layer
undermines it:

- **F12. The SENT row is persisted before the socket write and before any ACK.**
  `communication/message/TextMessageHandler.kt:61-68` inserts with default `sendStatus=SENT`,
  *then* writes to the socket (`:71`). The chat shows "sent" the instant sending *starts* —
  exactly the reported symptom.
- **F13. Duplicate SENT rows on retry.** The Messenger retry loop re-invokes `handleOutgoing`
  per attempt; each attempt inserts another row; the repository merge dedups by row id only →
  2–3 duplicate bubbles, plus a FAILED row on final failure (same message shown both sent and
  failed, `DeviceChatViewModel.kt:129-141`).
- **F14. The file progress bar is dead.** The chat bubble reads `transferred_size` from the DB,
  which is written 0 at insert and never updated (`persistence/MessageRepository.kt:130`; no
  update query in `FileTransfer.sq`). Live progress flows exist on both ends
  (`MessengerSendProgress.InProgress`, `ReceiveMessageStatus.Progress`) but the ViewModel
  discards them (`DeviceChatViewModel.kt:279-281` `.lastOrNull()`). Bar sits at 0 %, jumps to done.
- **F15. No per-write send timeout.** A half-open socket buffers silently; failure surfaces only
  via the ACK timeout (5–10 s) or heartbeat (15 s + 5 s; wedge detection up to ~3 min).

### Cleanup

- `ConnectionsPool.isAvailable` has left-in `[DEBUG]` logging on every call plus dead
  commented-out code (`ConnectionsPool.kt:149-171`).

## Agent verification matrix

| # | Hypothesis | How to verify |
|---|---|---|
| V1 | IPv6/link-local endpoints dialed first (F6) | Unit-test `isReachableAddress()` with `fe80::`, global IPv6, IPv4 inputs; on device, log dialed address order + per-address elapsed |
| V2 | Sequential 3 s dial dominates the stall (F7) | Unit test `connectTo` with 1 black-holed + 1 good endpoint; assert time-to-connect |
| V3 | Duplicate-dial race closes live sockets (F8) | Two concurrent `connectTo` against a fake server; assert one OPEN pooled messenger, no caller holds a closed one |
| V4 | NotInitiated wedges Probing (F2/F3) | ERC with a client stub returning NotInitiated; assert reachability eventually leaves Probing |
| V5 | Unknown-forever for endpoint-less peers (F1) | ERC skip path unit test; on device, badge of a peer with suppressed Klardrop SRV |
| V6 | SENT-before-write (F12) | `handleOutgoing` with a throwing write channel; assert no SENT row persisted |
| V7 | Duplicate rows on ACK-timeout retry (F13) | Messenger retry loop with ACK never arriving; count inserted rows (expect 1) |
| V8 | Dead file progress bar (F14) | No writer of `transferred_size` exists; send a large file, bar stays 0 % |
| V9 | UKEY2 untimed hang (F9) | Peer completes greeting then stalls; assert dial fails within a bound |
| V10 | 30 s browse debounce delays endpoint refresh (F1/F4) | Kill a peer's server; measure time-to-rediscovery after `invalidateKlardropEndpoint` |

## Fix list (priority order)

1. **ipv6-filter** — reject IPv6 (at minimum link-local) in `isReachableAddress()`; the server
   only listens on IPv4, so IPv6 endpoints are never connectable. (F6)
2. **client-dial** — race TCP endpoint dials concurrently (first success wins, cancel losers);
   per-device connect mutex so concurrent `connectTo` calls coalesce; `withTimeout` around both
   UKEY2 handshakes; drop the `[DEBUG]` noise/dead code in `ConnectionsPool`. (F7, F8, F9)
3. **probing-watchdog** — time out `Probing` (fall back to `Unknown`) and handle
   `NotInitiated` so the badge can't wedge. (F2, F3)
4. **sent-status** — persist outgoing text as SENDING once (not per retry attempt), flip to
   SENT on `ACK_RECEIVED`, to FAILED on terminal failure; no duplicate/contradictory rows. (F12, F13)
5. **file-progress** — feed the live progress flows into the chat bubble (ViewModel state, not
   per-chunk DB writes). (F14)
6. **dial-on-open** — opening a device's chat screen triggers a connect attempt if not pooled
   (safe once the per-device mutex exists). (F1/F11 mitigation, user expectation)

Deferred (revisit after the above land): parallel-happy-eyeballs address preference beyond the
IPv6 filter, per-write send timeouts (F15), browse-debounce tuning (F10/V10).

## Troubleshooting: discovered but offline

For the situation where a device shows up in the list but stays offline (no green dot, sends
fail, or a pair request never arrives). Work through the recipes in order — each fixes one of
the failure modes observed in the 2026-08-28 diagnosis that motivated this document.

### 1. Multiple desktop instances (duplicate advertisements)

**Symptom:** the same desktop appears several times in the phone's list (names like
"omarchy (2)", "(3)…"), or a pair request lands on a different instance than the one you are
looking at.

**Cause:** more than one Klardrop process is running (e.g. an old installed binary plus a
freshly launched one). Each instance advertises the same device id over mDNS, so peers see
duplicates and requests are delivered to an arbitrary one.

**Fix:** Klardrop now ships a single-instance guard — launching a second instance focuses the
running window and exits instead of starting a second copy. To clean up an older setup:

```bash
pkill -f '.local/lib/klardrop/bin/klardrop'   # kill ALL instances
pgrep -fc 'bin/klardrop'                      # must print 0
# then start exactly one instance
```

### 2. Advertised port is dead (server/advertiser desync)

**Symptom:** the device is visible, but connecting to its advertised port is refused while the
app looks perfectly alive.

**Check (desktop):** `avahi-browse -rt _klardrop._tcp` shows the advertised IP/port; then
`nc -z -w 3 <ip> <port>` must succeed. On Android: `adb shell ss -tln` lists the real
listeners — compare with the advertised port.

**Fix:** Klardrop now re-publishes mDNS whenever the live server port drifts (after server
restart, network change, and on a periodic check) and logs
`[DiscoveryNetwork] WARNING: advertised port <p> has no listener` when the advertisement is
stale. Restarting the app re-syncs advertisement and server.

### 3. Device visible but never comes online (no re-probe)

**Symptom:** the peer sits with no status dot or stuck "Connecting" forever, even though the
other side is fine.

**Cause (pre-fix):** a probe that never reached a terminal outcome left the reachability state
wedged on `Probing`, and nothing ever re-probed a failed peer.

**Fix:** two behaviors now prevent the wedge — a 15 s watchdog downgrades a stuck `Probing`
to `Unknown` (logged as `[ConnectionPool] Watchdog: <deviceId> still Probing after 15s -> Unknown`),
and the reachability connector re-probes `Unreachable`/`Unknown` devices every 30 s, logging
each outcome (`[EagerReachabilityConnector] Probe <deviceId>: <outcome> (<detail>)`). A peer
that comes back is recovered automatically within ~45 s; no manual interaction needed.

### 4. Pair request never arrives / fails silently

**Symptom:** you tap pair on one device; the other shows nothing, or the pairing button just
resets with no explanation.

**Causes (pre-fix):** the request was only sent over a freshly dialed connection (so a
firewall-blocked dial killed it even though an inbound connection existed), the receiving UI
dropped requests that arrived before the screen was composed or while a dialog was already
open, and failures were logged but never shown.

**Fix:** pair requests are now also delivered over an existing pooled inbound connection; the
receiver queues concurrent requests, replays ones that arrived before the UI subscribed, and
retains requests that arrive before any dialog callback exists. Failures surface in the UI
next to the pair button with a reason: `no-endpoints` (device not visible), `connect-failed`
(endpoints existed but every dial failed — previously misreported as "No Klardrop TCP or BLE
connection is available"), `ack-timeout`, `session-timeout`, `rejected-by-peer`, or
`clock-skew` (receiver clock off by more than 5 minutes — fix the device clock).

### 5. Baseline recovery recipe

When state looks suspect, restore a healthy baseline before diagnosing further:

```bash
# Desktop: exactly one fresh instance
pkill -f '.local/lib/klardrop/bin/klardrop'
nohup ~/.local/lib/klardrop/bin/klardrop >/tmp/klardrop-desktop.log 2>&1 &

# Phone: restart the app
adb shell am force-stop com.carlom.klardrop
adb shell monkey -p com.carlom.klardrop 1

# Verify the phone's advertisement accepts TCP
avahi-browse -rt _klardrop._tcp          # note phone IP + port
nc -z -w 3 <phone-ip> <port>             # must succeed
```

### 6. Firewall matrix: when discovery works but TCP does not

The 2026-08-28 live repro: both devices saw each other via mDNS, yet client-to-client TCP was
SYN-blackholed in **both** directions. The cause was split across the two devices — each side
blocked a different direction:

| Device | Blocker | Effect | Evidence-based check |
|---|---|---|---|
| Android phone | Battery Saver ON → netd `powersave` chain is default-deny; Klardrop's uid is not allowlisted | App egress dropped AND inbound SYNs to the app's port dropped (even loopback) | `adb shell settings get global low_power` → `1` means ON; `adb shell dumpsys network_management` → check whether the Klardrop uid appears in the `powersave` chain allowlist |
| Android phone | App denied on metered networks (`metered_deny_user` chain) | Same drops when the active Wi-Fi is metered | `adb shell dumpsys network_management` → look for the Klardrop uid in `metered_deny_user` |
| Desktop (Linux) | ufw active with `DEFAULT_INPUT_POLICY="DROP"` and no allow rule for the Klardrop port | Inbound SYNs die before the TCP stack (connection attempts time out, nothing in `ss`) | `sudo ufw status verbose` → shows `Status: active` and `Default: deny (incoming)` |

**Phone fix:** use the in-app banner (see below) to grant the standard battery-optimization
exemption, and re-enable the app on metered networks in Android's network settings if it was
denied.

**Desktop fix:** Klardrop dials with a punch-through that usually removes the need to open
ports (see below), but if you control the firewall you can also allow the port explicitly.

### 7. Firewall punch-through (automatic)

Klardrop now retries failed dials as a **TCP simultaneous open**: the dial socket is bound to
the device's own listening port, so the outbound SYN creates connection state whose reverse
direction is the peer's inbound SYN. Stateful firewalls (ufw/nft) accept reply-direction
packets as ESTABLISHED, so the peer's firewall lets it through with zero configuration —
neither side has to open a port. Both peers attempt this on the periodic re-probe, so the
dial windows overlap. Logged as `[Client] Punch-through dial to <peer> from local :<ownPort>`.
This does not defeat Battery Saver (which drops by app uid, not by direction) — use the
exemption flow for that.

### 8. Battery-saver / metered restriction banner (Android)

When Battery Saver is blocking Klardrop (or the app is denied on metered networks), the
discovery screen shows a banner — "Battery saver is blocking Klardrop — Tap to allow" or
"Klardrop is blocked on metered networks — Tap to check settings". Tapping it launches the
standard OS dialog (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) or the app's network
settings; no port configuration involved. Pairing/connect failures that coincide with the
restriction say so in the failure reason (e.g. "connect failed — Battery saver is blocking
Klardrop"). The monitor also logs
`[ConnectivityRestriction] battery-saver=<bool> battery-optimization-exempt=<bool> metered-denied=<bool>`
on every change.

### 9. Hotspot verification recipe

If both firewall checks above are clean but TCP still fails on the LAN, isolate the network
itself: enable the phone's hotspot and connect the desktop to it, then re-run the `nc -z`
check from recipe 5. On the hotspot there is no AP isolation and no third-party firewall —
if pairing works there but not on your Wi-Fi, the Wi-Fi network (router AP isolation or an
advanced firewall) is the blocker.

### 10. Stale entry after reinstalling the app

Reinstalling regenerates the device's short id **and** its identity key. Old entries on other
devices therefore reference an id that no longer exists and a key that can never validate —
they show up as a dead peer but they **never block re-pairing**: pairing the new install
simply creates a fresh trust entry. To clean up the stale entry, unpair it manually on the
device that holds it (`TrustManager.removeTrust`); that also purges the stale known-device
identity from the trusted-devices directory. No file editing required.
