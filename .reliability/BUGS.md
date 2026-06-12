# Klardrop Reliability — Bug Registry

Single source of truth for the reliability bug hunt. Columns:
`id | symptom | suspect | source | repro test | repro? | fix? | notes`

Status legend — repro?/fix?: ⬜ todo · 🔄 in progress · ✅ done · ❌ not-a-bug/won't-fix

| id | symptom | suspect (file:approx) | source | repro test | repro? | fix? | notes |
|----|---------|-----------------------|--------|------------|--------|------|-------|
| B01 | ghost message: auth/handler error swallowed, no ACK → sender hangs/retries | `MessagesRouterImpl.onMessageIncoming` — authorizationScope.launch TEXT branch (no try/catch; exception swallowed by SupervisorJob, ACK_RECEIVED never sent) | static | `textHandlerThrow_isFastRejected_notRetriedAsGhostDuplicates` | ✅ | ✅ | high/high — SupervisorJob swallows handler exception; no terminal ACK → sender timeouts + retries → duplicate insert. FIX bd8734e — TEXT launch wrapped in runCatching, ACK_REJECTED on failure |
| B02 | insertMessage throws → ACK_RECEIVED never sent | `MessagesRouterImpl.onMessageIncoming` TEXT branch → `TextMessageHandler.handleIncoming` → `MessageRepositoryImpl.insertMessage` | static | `textHandlerThrow_isFastRejected_notRetriedAsGhostDuplicates` | ✅ | ✅ | medium/high — DB/FS error unguarded in TEXT path; FILE path has runCatching but TEXT does not; sender gets timeout + retry storm. FIX bd8734e — same runCatching guard as B01 (shared repro) |
| B03 | signature-fail path returns without ACK | `MessagesRouterImpl.onMessageIncoming` — `if (!isValid)` TrustedMessage block (both unknown-sender and known-bad-sig branches return without ACK) | static | `signatureInvalid_unknownSender_repliesWithoutAck_causesRetries` | ✅ | ✅ | medium/high — sender awaits ACK_RECEIVED, gets nothing, times out and retries 3x instead of fast-failing. FIX bd8734e — terminal ACK_REJECTED sent on both !isValid sub-paths |
| B04 | chunk for unknown/removed header dropped silently, no ACK | `MessagesRouterImpl.handleFileChunk` — `if (pipeline == null)` drop branch | static | — | ❌ | ❌ | low/medium — not a bug: ACK_READY gates all legitimate chunks; no honest sender can reach this branch mid-transfer; only defence gap |
| B05 | ACK lost after receiver inserted → retry duplicates msg (no idempotency) | `MessagesRouterImpl.onMessageIncoming` TEXT branch + `TextMessageHandler.handleIncoming` + `MessengerImpl.handleKlardropTransfer` retry loop | static | `duplicateInboundTextIsDedupedButStillReAcked` | ✅ | ✅ | medium/high — no dedup keyed on wire message id; FILE path has receivePipelines[id] dedup; TEXT path is a genuine gap → duplicate DB rows on retry. FIX bd8734e — bounded FIFO `processedTextIds` (max 256); duplicate skips insertMessage but re-sends ACK_RECEIVED |
| B06 | no timeout on cleartext handshake write | `ClientImpl.establishConnection` — `writeChannel.sendMessage(handshakeMessage, serializer)` (no withTimeout; connect+read are bounded to 3s each, write is unbounded) | static | `connectToReturnsFailedWithinBudgetWhenPeerStallsAfterAccept` | ✅ | ✅ | medium/medium — black-holed peer post-accept can stall handshake write indefinitely, burning the full 15s connection budget. FIX 70d2a12 — handshake write wrapped in withTimeout(TCP_CONNECT_TIMEOUT_MS) |
| B07 | no timeout on server initial read | `Server.handleConnection` — `readChannel.readByteArrayMessage()` first read (no withTimeout; client has symmetric 3s bound but server side is unbounded) | static | `silentPeerDoesNotStallTheDetectionReadForever` | ✅ | ✅ | medium/high — silent peer post-TCP-accept holds FD and coroutine forever; only backstop is process exit. FIX 8fa4e22 — first read wrapped in withTimeout(TCP_CONNECT_TIMEOUT_MS), socket closed on timeout |
| B08 | half-open TCP (peer FIN) invisible until next read | `ConnectionsPoolImpl.isAvailable` / `Connection.Tcp.isClosed` | static | — | ❌ | ❌ | low/high — not a bug: send success gated on end-to-end ACK_RECEIVED (awaitRegisteredAck withTimeout 5-10s), not on isClosed; heartbeat proactively evicts half-open sockets |
| B09 | connection pooled before read loop starts → early ACK race | `ClientImpl.establishConnection` — `updateConnection` then `clientScope.launch { acceptIncomingMessages() }` | static | — | ❌ | ❌ | low/high — not a bug: registerPendingAck precedes wire write in ConnectionMessenger.send; TCP buffers ACK until read loop drains; no ACK can be lost |
| B10 | black-holed address starves others (per-addr vs overall timeout) | `ClientImpl.connectTo` loop / `ClientImpl.establishConnection` withTimeout | static | — | ❌ | ❌ | low/high — not a bug: 3s withTimeout per address already in establishConnection; defect already fixed in current code |
| B11 | network-change pool flush closes an in-flight send | `ConnectionsPoolImpl.subscribeToNetworkEvents` debounceJob → `closeAllConnections()` / `ConnectionMessenger.close()` (bypasses writeLock) | static | `spuriousNetworkEventDoesNotAbortInFlightTransfer` | ✅ | ✅ | medium/medium — spurious NetworkChangeEvent kills live in-flight transfer; heartbeat path hardened against this but network-flush path is not. FIX 98d5d99 — flush probes writeLock and skips connections that are actively writing |
| B12 | Android mdns restart() is no-op; no forced recovery on net change | `ServiceDiscoveryMdns.android` — `restart()` | static | — | ❌ | ❌ | low/high — not a bug: restart() is intentionally no-op on Android; forced recovery lives in DiscoveryNetwork.rebuildMdnsState() which cancels + re-launches all NsdManager subscriptions on NetworkChangeEvent.Changed |
| B13 | macOS desktop has no foreground→rebuildMdnsState hook | `macosMain InternalPlatformDependencies` — `networkLifecycleMonitor by lazy { NetworkLifecycleMonitor() }` (no NSApp/NSWorkspace observer registered; iOS does register UIApplicationDidBecomeActive) | static | none/live | ✅ | ✅ | medium/high — compile-gated; live-verify pending. FIX 0b7e276 — NSApplicationDidBecomeActiveNotification + NSWorkspaceDidWakeNotification observers added in networkLifecycleMonitor lazy block; compile-verified macosArm64; **behavior needs live macOS device verification** |
| B14 | no periodic mdns re-announce; 5-min passive TTL lets dead device linger | `VisibleDevicesImpl` (deviceTTLVisibility=5.minutes) / `DiscoveryNetwork` (no app-level re-announce loop) | static | — | ❌ | ❌ | low/high — not a bug: OS mDNS daemon owns periodic re-announcement; 5-min TTL is intentional design trade-off documented in code; connected-peer teardown via heartbeat; adding app-level re-announce would cause duplicate-name collisions |
| B15 | ACK_AWAITING_USER can be re-sent indefinitely (starvation) | `ConnectionMessenger.awaitRegisteredAck` — AwaitingUser branch | static | — | ❌ | ❌ | low/high — not a bug: awaitingUserActive set to null after first AWAITING_USER (branch removed); handleAckMessage removes channel on first delivery; wait bounded at one userResponseTimeout |
| B16 | BLE handshake failure closes bridge silently, no ACK | `BleServerListener.acceptSession` catch block — calls bridge.close() only | static | — | ❌ | ❌ | low/high — not a bug: bridge.close() → session.close() → central's ByteChannel closed → readFully throws EOF → ConnectOutcome.Failed; existing test sessionCloseSurfacesAsClosedChannels proves this |
| B17 | stale endpoint never invalidated on connect/handshake timeout — Mac keeps re-dialing dead port, burning full 3s timeout each probe cycle | `ClientImpl.connectTo` → `establishConnection(...).onFailure` — only `isConnectionRefused()` triggers `invalidateKlardropEndpoint`; `TimeoutCancellationException` from `withTimeout(TCP_CONNECT_TIMEOUT_MS)` blocks is not handled | live log (cli-linux-smoke/nodeB-mac-listen.log) | `ClientConnectTimeoutInvalidatesEndpointTest.connectTimeoutInvalidatesStaleEndpoint` | ✅ | ✅ | medium/high — peer restarted on new ephemeral port; stale SYN black-holes cause TCE; old address:port survives cache indefinitely. FIX: broadened `.onFailure` to also check `cause is TimeoutCancellationException`; same `invalidateKlardropEndpoint` call as refused path |
| B18 | clean peer close after completed exchange logged as ERROR with full EOFException stack trace | `ConnectionMessenger.acceptIncomingMessages` — `.onFailure` block unconditionally calls error-level `log(..., throwable)` for every read-loop exit, including `EOFException("Channel is already closed")` which `isExpectedNetworkNoise()` already classifies as expected | live log (cli-linux-smoke/nodeB-linux-listen.log) | `CleanDisconnectReadLoopTest.peerCloseAfterCompletedExchangeIsNotLoggedAsError` | ✅ | ✅ | low/medium — spurious Bugsnag noise; not a functional defect. FIX: `.onFailure` now consults `isExpectedNetworkNoise()`; expected exits use quiet `log(msg)` (no throwable, no stack trace); unexpected exits retain error-level logging with stack trace |

## Triage notes

### Build baseline (canonical)
- **Test task**: `:klardrop-common:desktopJvmTest` (runs commonTest + integrationCommonTest merged via kotlin.srcDir; no separate integrationCommonTest task)
- **Baseline**: GREEN — 302 tests, 0 failures, 0 errors (was 296; +6 reliability repro tests added across B01/02/03/05/06/07/11)
- **New repro tests go in**: `common/src/integrationCommonTest/kotlin/com/carlom/klardrop/common/communication/` (auto-picked-up, no build.gradle.kts edits needed)
- **Run single class**: `./gradlew :klardrop-common:desktopJvmTest --tests "com.carlom.klardrop.common.communication.MyNewTest"`
- **Run single method**: `./gradlew :klardrop-common:desktopJvmTest --tests "com.carlom.klardrop.common.communication.KlardropIntegrationTest.startServerAndSendTextMessage"`
- **Run all**: `./gradlew :klardrop-common:desktopJvmTest`
- **Non-fatal warning** (pre-existing): `MessageReceiverImplTest.kt:103:15 'This cast can never succeed'` — warning only, no failures

---

### B01 (high/high) — Ghost message: handler exception swallowed, no ACK, sender retries

Root cause: `authorizationScope.launch` TEXT body has no try/catch; any exception (authorizer or insertMessage) propagates to SupervisorJob, is swallowed silently, ACK_RECEIVED is never sent.
Proposed fix: Wrap the TEXT launch body in runCatching; on failure send `MessageAcknowledgment(AckType.REJECTED, ackId)` under writeLock so sender resolves terminally (no retry, no duplicate).
Repro strategy: Inject a ThrowingMessageRepository on server side; assert insertMessage invoked once (currently 2 on retry); assert sender resolves promptly without 3x retry storm. `reproducibleInProcess: yes`.
Primary file: `common/src/commonMain/kotlin/com/carlom/klardrop/common/communication/router/MessagesRouter.kt`

---

### B02 (medium/high) — insertMessage throws → ACK_RECEIVED never sent, retry storm

Root cause: TEXT receive path (MessagesRouterImpl → TextMessageHandler.handleIncoming → insertMessage) has no runCatching; FILE path is hardened but TEXT is not; uncaught throw drops ACK, sender times out (5s) and retries up to 3x.
Proposed fix: In MessagesRouterImpl TEXT branch, wrap handler call in runCatching; send ACK_RECEIVED on success, ACK_REJECTED on failure, always under writeLock.
Repro strategy: Wire ThrowingMessageRepository into server; lower ackTimeoutConfig; assert sender terminates in Error after single attempt (not 3x retry storm). `reproducibleInProcess: yes`.
Primary file: `common/src/commonMain/kotlin/com/carlom/klardrop/common/communication/router/MessagesRouter.kt`

---

### B03 (medium/high) — Signature-fail returns without ACK → sender full-timeout retry

Root cause: Both `if (!isValid)` sub-paths (unknown sender → send revocation; known bad-sig → log only) return without emitting any MessageAcknowledgment for ackId; sender's awaitRegisteredAck times out (5s), retries 3x before Error.
Proposed fix: After the revocation send (or security log), add `writeLock.withLock { sendMessageToDevice(fromDeviceId, MessageAcknowledgment(AckType.REJECTED, ackId), writeChannel, cipher) }` on both sub-paths before returning.
Repro strategy: Unit test in MessagesRouterImplTest cloning `trustedMessageFromUnknownSenderTriggersRevocationReply`; assert a REJECTED ACK frame is also written for the outer signed message id. Pre-fix: only revocation written, no ACK. `reproducibleInProcess: yes`.
Primary file: `common/src/commonMain/kotlin/com/carlom/klardrop/common/communication/router/MessagesRouter.kt`

---

### B05 (medium/high) — No receiver-side idempotency for TEXT → duplicate DB insert on ACK loss + retry

Root cause: MessagesRouterImpl TEXT branch has no processed-id dedup; FILE path has `receivePipelines[header.id]` guard; on sender retry (same id) receiver calls insertMessage again → duplicate row. No dedup anywhere (grep for dedup/idempotent/seenIds in commonMain returns unrelated results).
Proposed fix: Add a small bounded LRU/set of recently-processed inbound message ids per connection in MessagesRouterImpl (guarded by receiveMutex); skip handler+insert for seen ids but still re-send ACK_RECEIVED so ACK-loss retries resolve cleanly.
Repro strategy: Inject counting FakeMessageRepository on server; suppress first ACK_RECEIVED frame; sender retries; assert insertMessage called exactly once. Currently 2 → test fails. `reproducibleInProcess: yes`.
Primary file: `common/src/commonMain/kotlin/com/carlom/klardrop/common/communication/router/MessagesRouter.kt`

---

### B06 (medium/medium) — No timeout on cleartext handshake write → black-hole burns 15s budget

Root cause: `ClientImpl.establishConnection`: TCP connect wrapped in `withTimeout(TCP_CONNECT_TIMEOUT_MS=3s)`, server-greeting read wrapped in `withTimeout(3s)`, but `writeChannel.sendMessage(handshakeMessage, serializer)` between them has no timeout; peer that accepts but never drains receive buffer can stall the write indefinitely.
Proposed fix: Wrap handshake write: `withTimeout(TCP_CONNECT_TIMEOUT_MS) { writeChannel.sendMessage(handshakeMessage, serializer) }` in `ClientImpl.establishConnection`.
Repro strategy: Stand up a raw ServerSocket that accepts but never reads; inject its address; call connectTo; assert returns Failed within ~2x TCP_CONNECT_TIMEOUT_MS. Without fix, burns full 15s CONNECTION_WAIT_TIMEOUT. `reproducibleInProcess: yes`.
Primary file: `common/src/commonMain/kotlin/com/carlom/klardrop/common/communication/Client.kt`

---

### B07 (medium/high) — No timeout on server initial read → silent peer holds FD forever

Root cause: `Server.handleConnection` calls `readChannel.readByteArrayMessage()` (two unbounded readFully calls) with no withTimeout; serverScope has no timeout; a peer that completes TCP handshake but never sends bytes holds the FD and coroutine until process exit. Client already has symmetric `withTimeout(TCP_CONNECT_TIMEOUT_MS)` on its first read.
Proposed fix: In `Server.handleConnection`: `val firstMessage = withTimeout(TCP_CONNECT_TIMEOUT_MS) { readChannel.readByteArrayMessage() }` — surrounding try/catch already handles TimeoutCancellationException and closes the socket.
Repro strategy: Start real Server; open raw TCP socket to server port; send nothing; assert server closes connection within TCP_CONNECT_TIMEOUT_MS + slack (client observes EOF). Without fix, never closes. `reproducibleInProcess: yes`.
Primary file: `common/src/commonMain/kotlin/com/carlom/klardrop/common/communication/Server.kt`

---

### B11 (medium/medium) — Network-change flush closes in-flight send (bypasses writeLock)

Root cause: `ConnectionsPoolImpl.subscribeToNetworkEvents` debounceJob (500ms) calls `closeAllConnections()` which takes pool mutex and calls `ConnectionMessenger.close()` → `socket.close()` unconditionally without checking writeLock; a file send holding writeLock across chunk writes has its socket yanked, forcing restart from byte 0 (up to maxRetries=2 before Error). Heartbeat path was hardened against this exact race; network-flush path was not.
Proposed fix: Give ConnectionMessenger an `isWriteInProgress()` (writeLock.isLocked) accessor; in closeAllConnections (or a flush-specific variant), use bounded tryLock probe per connection before closing — skip connections that are actively writing (link alive); or have `ConnectionMessenger.close()` acquire writeLock with timeout before socket teardown.
Repro strategy: In KlardropIntegrationTest, start large file send; after first chunks land, call `testContext.dropClientConnections()`; assert send completes without Error (fix) or assert file is restarted (bug). Unit variant: ConnectionsPoolNetworkDebounceTest with a locked-writeLock messenger; emit NetworkChangeEvent.Changed; assert connection NOT closed while lock held. `reproducibleInProcess: yes`.
Primary file: `common/src/commonMain/kotlin/com/carlom/klardrop/common/communication/ConnectionsPool.kt`

---

### B13 (medium/high) — macOS native: no foreground/wake hook → rebuildMdnsState never called

Root cause: `macosMain InternalPlatformDependencies.networkLifecycleMonitor` is `by lazy { NetworkLifecycleMonitor() }` with no NSNotificationCenter/NSWorkspace observer registered; `trigger()` is never called; `DiscoveryNetwork.rebuildMdnsState()` never fires on macOS wake/foreground. iOS registers `UIApplicationDidBecomeActive`; Android has ConnectivityManager callbacks; desktopJvm has 5s NIC poll; macOS native has zero recovery signal.
Proposed fix: Add `NSApplicationDidBecomeActiveNotification` and/or `NSWorkspaceDidWakeNotification` observer in the macosMain `networkLifecycleMonitor` lazy block, calling `monitor.trigger()` — mirrors the iOS pattern; only `macosMain InternalPlatformDependencies.kt` changes.
Repro strategy: macosTest (Kotlin/Native): construct macOS InternalPlatformDependencies; post `NSApplicationDidBecomeActiveNotification` via NSNotificationCenter; assert `monitor.observe()` emits `NetworkChangeEvent.Changed`. Currently fails (no emission). `reproducibleInProcess: no` (needs macOS native target / real NSApplication).
Primary file: `common/src/macosMain/kotlin/com/carlom/klardrop/common/InternalPlatformDependencies.kt`

## Live-matrix findings
_(filled by Track B)_

---

## CLI Tooling Changes

### fileSize=0L bug — SendCommand hardcoded zero file size (FIXED)

**Bug**: `SendCommand.buildFileRequest()` previously constructed a `FileMessage` with `fileSize = 0L` and passed no `PlatformFile` (used `toSimpleSendRequest()` instead of `toSendRequest(file)`). The receiver allocated its buffer against the declared 0-byte size, content hash comparison was vacuous, and `FileReceivePipeline` would mark the transfer completed with 0 bytes received.

**Fix**: `buildFileRequest()` now calls `SystemFileSystem.metadataOrNull(path)` to resolve the real file size, exits with usage error if the file doesn't exist, and calls `fileMessage.toSendRequest(PlatformFile(JvmFile(filePath)))` to pass the actual platform file reference. Branch: `feature/reliability-hardening`, files: `cli/.../commands/SendCommand.kt`.

**Same-host smoke result (2026-06-12)**: file `klardrop-smoke-test.txt` (70 bytes) sent from node A → node B; node B log confirms `beginReceive klardrop-smoke-test.txt (70 bytes)` and `Received klardrop-smoke-test.txt (70 bytes) in 15ms`. Size correct.

---

### --data-dir / KLARDROP_HOME isolation (ADDED)

**Feature**: All CLI commands (`discover`, `listen`, `send`, `status`) now accept `--data-dir=PATH` (also readable via `KLARDROP_HOME` env var). When set, the system property `klardrop.data.dir` is written before `Klardrop` initialises; `InternalPlatformDependencies.trustStorage()` roots its `DesktopTrustStorage` under `<data-dir>/trust` instead of `~/.klardrop`, giving each CLI process a separate identity/trust/device-ID.

**Same-host smoke result (2026-06-12)**:
- Node A (`--data-dir=/tmp/kd-A`): device ID = `b5853dfc`
- Node B (`--data-dir=/tmp/kd-B`): device ID = `7063e321`
- Distinct IDs confirmed; node A discovered node B via mDNS (the previously-failing same-host self-filter is resolved by the distinct short device IDs).
- Text "samehost-hello" and file (70 bytes) both received by node B, exit 0.

**Known smell from logs**: `[🔐 TrustManager]: Failed to sign UKEY2 binding — SecKeychainItemModifyContent: The specified item already exists in the keychain.` — the ECDH key is keyed by the short device ID in the macOS Keychain, but both processes share the same Keychain and the same short ID happens to collide with a pre-existing entry. The fallback to opportunistic (unauthenticated) encryption kicks in automatically and transfers still succeed, but the Keychain collision should be investigated if MAC-authenticated transfers are required in same-host multi-node setups. This is a pre-existing Keychain isolation gap, not introduced by this PR.
