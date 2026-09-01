package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.ble.BleChannelBridge
import com.carlom.klardrop.common.ble.BleRoleSelector
import com.carlom.klardrop.common.ble.BleTransport
import com.carlom.klardrop.common.communication.message.HandshakeMessage
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.common.utils.logLocal
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.invoke
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.coroutineContext

/**
 * Per-address TCP connect timeout (milliseconds), enforced via [withTimeout].
 *
 * Ktor's NIO-based connect suspends until the OS completes the 3-way
 * handshake.  When a peer advertises a stale/black-holed address (SYN
 * packets silently dropped rather than RST'd) there is no OS-level upper
 * bound on that wait — it can block for the full OS retransmit cycle (tens
 * of seconds).  One such address therefore consumes the entire 15 s
 * CONNECTION_WAIT_TIMEOUT before any other advertised address is tried.
 *
 * 3 s is generous for any reachable LAN peer and leaves room for 4+ stale
 * addresses to be tried sequentially within the 15 s budget.
 */
internal const val TCP_CONNECT_TIMEOUT_MS = 3_000L

/**
 * Bound (F9) for the UKEY2 handshake phase, enforced via [withTimeout] on both the initiator
 * ([ClientImpl]) and responder ([Server]) side. Distinct from — and much larger than —
 * [TCP_CONNECT_TIMEOUT_MS]: the handshake is not a single TCP connect but a full mutual
 * key-agreement (P256 keygen + multi-message exchange + ECDSA identity binding). On Apple
 * targets that crypto measures ~3.7s (macOS/iOS-sim arm64) versus a few ms on the JVM, so
 * reusing the 3s connect timeout here made every Klardrop dial time out mid-UKEY2 on native.
 * 10s gives ~2.7x headroom over the measured native cost while still failing a genuinely
 * stalled peer well before the 15s outer CONNECTION_WAIT_TIMEOUT, so a raced sibling endpoint
 * can still take over.
 */
internal const val UKEY2_HANDSHAKE_TIMEOUT_MS = 10_000L

/**
 * T10 firewall punch-through burst: after a direct dial exhausts every advertised endpoint,
 * retry this many times with the dial socket BOUND to our own listening port (1s apart).
 * Both peers run the same burst via the reachability prober, so dial windows overlap and
 * stateful firewalls accept the cross SYNs as ESTABLISHED (TCP simultaneous open).
 */
internal const val PUNCH_THROUGH_ATTEMPTS = 3

/** Gap between punch-through burst attempts. See [PUNCH_THROUGH_ATTEMPTS]. */
internal const val PUNCH_THROUGH_ATTEMPT_INTERVAL_MS = 1_000L

/**
 * Result of a [Client.connectTo] call. The connector uses this to distinguish a
 * genuine dial failure from a deliberate decision not to initiate (e.g. BLE
 * role-selection means the peer will dial *us*), so reachability is not
 * incorrectly forced to [Unreachable] for inbound-only peers.
 */
sealed interface ConnectOutcome {
  /** A connection was successfully established and added to the pool. */
  data object Connected : ConnectOutcome

  /**
   * We deliberately did not initiate — e.g. BLE role-selection says the peer
   * dials us, or we are already connected. The probe is inconclusive; the peer
   * may still reach us via an inbound connection.
   */
  data object NotInitiated : ConnectOutcome

  /** Every dial attempt failed with an error. The peer is genuinely unreachable. */
  data object Failed : ConnectOutcome
}

interface Client {
  suspend fun connectTo(deviceId: String): ConnectOutcome

  /**
   * Releases this client's networking resources — most importantly its [SelectorManager].
   *
   * On Kotlin/Native a SelectorManager is NOT free to leave running: ktor's posix selector loop
   * blocks in `pselect` and only ever yields to re-dispatch onto the same dispatcher, so each live
   * instance permanently occupies one of Dispatchers.IO's 64 parallelism slots until it is closed.
   * The app builds one client and keeps it, so this is a no-op there; test fixtures build dozens,
   * and without this they exhaust the pool and deadlock the whole native test binary.
   */
  fun close() = Unit
}

/**
 * Returns true when this exception (or any cause in its chain) represents a hard connection
 * refusal — ECONNREFUSED, i.e. the remote port is not listening. Used to distinguish
 * "peer is gone / restarted" from transient network glitches. Works across platforms by
 * inspecting class simpleName and message text rather than JVM-only types:
 *  - JVM / Android: `java.net.ConnectException`.
 *  - Apple-native (Ktor over POSIX sockets): `PosixException.ConnectionRefusedException`
 *    (simpleName "ConnectionRefusedException") and/or the strerror text "Connection refused".
 *
 * We deliberately match ONLY the refusal-specific class name and the canonical refusal text —
 * NOT the broad `PosixException` base, which also covers ECONNRESET / ETIMEDOUT /
 * EHOSTUNREACH. Treating those as "refused" would wrongly invalidate a still-valid endpoint
 * on a transient error.
 */
internal fun Throwable.isConnectionRefused(): Boolean {
  var current: Throwable? = this
  var depth = 0
  while (current != null && depth < 8) {
    val name = current::class.simpleName ?: ""
    val msg = current.message.orEmpty()
    if (name == "ConnectException" || name == "ConnectionRefusedException") return true
    if (msg.contains("ECONNREFUSED", ignoreCase = true) ||
      msg.contains("Connection refused", ignoreCase = true)
    ) return true
    current = current.cause?.takeIf { it !== current }
    depth++
  }
  return false
}

class ClientImpl(
  private val connectionsPool: ConnectionsPool,
  private val coroutines: Coroutines,
  private val messagesRouter: MessagesRouter,
  private val serializer: MessageSerializer,
  private val visibleDevices: VisibleDevices,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val trustManager: TrustManager,
  private val ackTimeoutConfig: AckTimeoutConfig = AckTimeoutConfig.DEFAULT,
  private val heartbeatConfig: HeartbeatConfig = HeartbeatConfig.DEFAULT,
  private val bleTransport: BleTransport? = null,
  /**
   * Our own server's bound port, published by [Server] on bind (0 while unknown). The T10
   * punch-through dial binds its socket to this port so the outbound SYN creates conntrack
   * state for the peer's inbound SYN to our listener. 0 disables punch-through entirely.
   */
  private val serverPort: StateFlow<Int> = MutableStateFlow(0),
) : Client {

  private val clientScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
  private val visibleDevicesFlow =
    visibleDevices.visibleDevices.stateIn(clientScope, started = SharingStarted.Eagerly, initialValue = emptyMap())

  private val selectorManager = SelectorManager(coroutines.ioDispatcher)

  override fun close() {
    selectorManager.close()
    clientScope.cancel()
  }

  // Per-device dial coalescing (F8): EagerReachabilityConnector and Messenger.send can both call
  // connectTo() for the same device concurrently. Both would pass the isAvailable() check below
  // and dial independently; whichever handshake completes second then looks like a "reconnect" to
  // ConnectionsPool.updateConnection and closes the first — already-Connected — socket out from
  // under whoever obtained it. Only the first concurrent caller for a given deviceId actually
  // dials; later callers await that in-flight attempt's outcome instead.
  private val inFlightMutex = Mutex()
  private val inFlightConnects = mutableMapOf<String, CompletableDeferred<ConnectOutcome>>()

  override suspend fun connectTo(deviceId: String): ConnectOutcome = coroutines.ioDispatcher {

    if (connectionsPool.isAvailable(deviceId)) {
      log("Client", "has already a connection with $deviceId. skipping")
      return@ioDispatcher ConnectOutcome.Connected
    }

    val (inFlight, isOwner) = obtainInFlightDeferred(deviceId)
    if (!isOwner) {
      log("Client", "Dial already in flight for $deviceId; awaiting its outcome")
      return@ioDispatcher inFlight.await()
    }

    try {
      performDial(deviceId, inFlight)
    } catch (t: Throwable) {
      // Make sure any coalesced waiter unblocks even if this owner attempt throws (e.g. the
      // "no TCP or BLE connection available" precondition below) rather than completing normally.
      //
      // A CancellationException here means THIS owner's own coroutine was cancelled (e.g. its
      // caller's coroutine was cancelled) — it says nothing about whether the dial itself would
      // have succeeded. That cancellation must not be transported across the coalescing boundary:
      // every coalesced waiter is an unrelated, independent caller (typically wrapped in
      // runCatching by Messenger/ERC/dial-on-open), and runCatching would swallow a propagated
      // CancellationException and misreport it as a failed connect. Resolve waiters to a normal
      // Failed outcome instead so they see an ordinary "connect failed", not a foreign
      // cancellation. Genuine dial errors (non-cancellation) are still reported via
      // completeExceptionally so waiters observe the real failure.
      if (!inFlight.isCompleted) {
        if (t is kotlinx.coroutines.CancellationException) {
          inFlight.complete(ConnectOutcome.Failed)
        } else {
          inFlight.completeExceptionally(t)
        }
      }
      throw t
    } finally {
      // NonCancellable: this finally can run during cancellation-driven unwinding (e.g. the
      // caller's withTimeoutOrNull expiring, or viewModelScope cancelling on screen close).
      // Mutex.lock() only checks cancellation on its suspending (contended) path, so a plain
      // withLock here would, if another connectTo held inFlightMutex at that instant, throw
      // CancellationException before this body runs — skipping the map cleanup and leaking
      // the completed deferred in inFlightConnects forever, permanently wedging this device's
      // outbound dials onto the stale coalesced outcome.
      withContext(NonCancellable) {
        inFlightMutex.withLock {
          if (inFlightConnects[deviceId] === inFlight) inFlightConnects.remove(deviceId)
        }
      }
    }
  }

  /** Returns the in-flight deferred for [deviceId] plus whether THIS call created it (i.e. must dial). */
  private suspend fun obtainInFlightDeferred(deviceId: String): Pair<CompletableDeferred<ConnectOutcome>, Boolean> =
    inFlightMutex.withLock {
      val existing = inFlightConnects[deviceId]
      if (existing != null) {
        existing to false
      } else {
        val created = CompletableDeferred<ConnectOutcome>()
        inFlightConnects[deviceId] = created
        created to true
      }
    }

  // Extension on CoroutineScope so the `launch` below runs as a structured child of the calling
  // connectTo() invocation's own scope (coroutines.ioDispatcher { ... }), matching the original
  // (pre-coalescing) code's cancellation semantics.
  private suspend fun CoroutineScope.performDial(deviceId: String, connectionJob: CompletableDeferred<ConnectOutcome>): ConnectOutcome {
    val discoveryDevice = visibleDevicesFlow.value[deviceId] ?: kotlin.run {
      log("Client", "cant connect. Device $deviceId cant be found")
      connectionJob.complete(ConnectOutcome.Failed)
      return ConnectOutcome.Failed
    }

    val tcpConnections = discoveryDevice.getKlardropConnection()
    val bleConnections = discoveryDevice.getBleConnection()

    require(tcpConnections.isNotEmpty() || bleConnections.isNotEmpty()) {
      "Cant connect to $deviceId. No known route: device is visible but advertises no Klardrop TCP or BLE endpoint"
    }

    // launch coroutine to connect and await for the connection to stay alive. TCP is
    // preferred (higher throughput); BLE is only tried if no TCP path worked, and only
    // when this device is the lex-smaller initiator per BleRoleSelector.
    launch {
      if (tcpConnections.isNotEmpty()) {
        raceTcpConnections(tcpConnections, deviceId, connectionJob)
      }

      // T10: the direct dials are exhausted without a connection — try the firewall
      // punch-through burst before falling back to BLE / marking the peer unreachable.
      if (!connectionJob.isCompleted && tcpConnections.isNotEmpty()) {
        punchThroughBurst(tcpConnections, deviceId, connectionJob)
      }

      if (!connectionJob.isCompleted && bleTransport != null && bleConnections.isNotEmpty()) {
        val selfId = currentDeviceProvider.get().shortDeviceId
        if (!BleRoleSelector.shouldInitiate(selfShortDeviceId = selfId, peerShortDeviceId = deviceId)) {
          log("Client", "Not the initiator for BLE to $deviceId (self=$selfId); awaiting inbound GATT")
          // Deliberately not initiating — the peer will dial us. This is not a failure;
          // leave reachability as Probing so we don't mark the peer Unreachable.
          connectionJob.complete(ConnectOutcome.NotInitiated)
          return@launch
        }
        for (ble in bleConnections) {
          log("Client", "Connecting via BLE to $deviceId (address=${ble.address})")
          establishBleConnection(ble, deviceId, connectionJob)
            .onFailure { logLocal("Client", "Failed BLE connect to $deviceId @ ${ble.address}", it) }
          if (connectionJob.isCompleted) return@launch
        }
      }

      // All TCP and BLE attempts were exhausted without success — genuine failure.
      if (!connectionJob.isCompleted) connectionJob.complete(ConnectOutcome.Failed)
    }

    // await for the connection to be established and connectionpool to be updated
    val outcome = connectionJob.await()
    log("Client", "On client connection completed with $deviceId: outcome: $outcome")
    return outcome
  }

  /**
   * T10 firewall punch-through: when a direct dial fails with a timeout/unreachable
   * classification, retry with the dial socket BOUND to our own listening port. The
   * outbound SYN creates conntrack state whose REVERSE direction is the peer's inbound
   * SYN to our listening port, which stateful firewalls (ufw/nft/conntrack-based APs)
   * accept. Both peers run the same burst via the reachability prober, so dial windows
   * overlap naturally: the OS completes the simultaneous open OR a listener accepts the
   * second connection — ConnectionsPool.updateConnection's tie-break dedupes the pair.
   *
   * Tight burst ([PUNCH_THROUGH_ATTEMPTS] attempts, [PUNCH_THROUGH_ATTEMPT_INTERVAL_MS]
   * apart) before the caller falls back to its normal cadence. Skipped entirely when our own
   * server port is unknown (nothing to punch through from) or when the platform has no bound
   * dial ([punchThroughSupported]) — there every attempt fails by construction, so the burst
   * would only add its whole schedule of delays ahead of the BLE fallback.
   */
  private suspend fun CoroutineScope.punchThroughBurst(
    tcpConnections: List<DeviceConnection.KlardropConnection>,
    deviceId: String,
    connectionJob: CompletableDeferred<ConnectOutcome>,
  ) {
    if (!punchThroughSupported) return
    val ownPort = serverPort.value
    if (ownPort <= 0) return
    log("Client", "Direct dial to $deviceId failed; starting punch-through burst from local :$ownPort")
    repeat(PUNCH_THROUGH_ATTEMPTS) { attempt ->
      if (connectionJob.isCompleted) return
      raceTcpConnections(tcpConnections, deviceId, connectionJob, punchThrough = true)
      if (connectionJob.isCompleted) {
        log("Client", "Punch-through dial to $deviceId succeeded (attempt ${attempt + 1})")
        return
      }
      if (attempt < PUNCH_THROUGH_ATTEMPTS - 1) delay(PUNCH_THROUGH_ATTEMPT_INTERVAL_MS)
    }
    log("Client", "Punch-through burst to $deviceId exhausted ($PUNCH_THROUGH_ATTEMPTS attempts)")
  }

  /**
   * F7: races every advertised TCP endpoint concurrently instead of dialing them one at a time.
   * Sequentially, N bad addresses cost up to N x TCP_CONNECT_TIMEOUT_MS before a good one is even
   * tried; racing bounds the wait to a single TCP_CONNECT_TIMEOUT_MS regardless of how many stale
   * addresses are mixed in.
   *
   * [winnerGate] arbitrates which attempt — of possibly several that complete a full handshake —
   * gets to register itself with [ConnectionsPool] via [establishConnection]; the rest close their
   * redundant sockets. Once a winner is known the remaining in-flight attempts are cancelled so
   * they don't keep burning connect/handshake timeouts for no reason. [winnerGate] is completed
   * with the winning attempt's own [Job] (rather than [Unit]) so the cancellation sweep below can
   * exclude it: the winner still has to run `ConnectionsPool.updateConnection` (which can suspend
   * on a contended pool mutex) and `connectionJob.complete` AFTER flipping the gate, and cancelling
   * it mid-registration would both leak its socket and spuriously fail a dial that actually won.
   */
  private suspend fun raceTcpConnections(
    tcpConnections: List<DeviceConnection.KlardropConnection>,
    deviceId: String,
    connectionJob: CompletableDeferred<ConnectOutcome>,
    punchThrough: Boolean = false,
  ) = coroutineScope {
    val winnerGate = CompletableDeferred<Job>()
    // Built fully (LAZY, not yet running) before any of them start, so the cancellation watcher
    // below always sees the complete list — no self-referential race on a partially-built list.
    val jobs: List<Job> = tcpConnections.map { connection ->
      launch(start = CoroutineStart.LAZY) {
        log("Client", "Connecting to $deviceId with address ${connection.address} port ${connection.port}")
        establishConnection(connection.address, connection.port, deviceId, connectionJob, winnerGate, punchThrough)
          // TCP dial failures (peer not listening, connection refused, peer closed
          // mid-handshake) are routine on a flaky LAN. Keep the on-device log,
          // skip Sentry.
          .onFailure { cause ->
            logLocal("Client", "Failed TCP connect to $deviceId @ ${connection.address}", cause)
            // If the dial was actively refused (peer's port is dead — e.g. peer restarted
            // on a new ephemeral port), remove the stale endpoint from the visible-device
            // cache immediately. This prevents every subsequent send attempt from retrying
            // the dead address+port until mDNS delivers a fresh SRV record.
            // Also invalidate on a per-address connect/handshake TIMEOUT: a peer that
            // moved ports/networks causes SYN black-holing (no RST), so the
            // TimeoutCancellationException thrown by the withTimeout(TCP_CONNECT_TIMEOUT_MS)
            // blocks in establishConnection signals the same "stale cached endpoint" condition
            // as a refused connection. mDNS re-delivers the fresh SRV quickly after invalidation.
            // NOTE: a loser cancelled by the watcher below throws a plain CancellationException,
            // not TimeoutCancellationException, so a winning sibling never causes this endpoint
            // to be wrongly invalidated.
            // NOTE (T10): punch-through failures never invalidate — the endpoint was already
            // judged by the direct dial above; the punch-through dial failing means the
            // firewall path did not open, not that the endpoint is stale.
            val refused = cause.isConnectionRefused()
            val timedOut = cause is kotlinx.coroutines.TimeoutCancellationException
            if ((refused || timedOut) && !punchThrough) {
              val reason = if (refused) "connection refused" else "connect/handshake timeout"
              log("Client", "Dial to $deviceId @ ${connection.address}:${connection.port} failed ($reason) — invalidating stale endpoint")
              visibleDevices.invalidateKlardropEndpoint(deviceId, connection.address, connection.port)
            }
          }
      }
    }
    jobs.forEach { it.start() }

    val watcher = launch {
      val winnerJob = winnerGate.await()
      // Exclude the winner itself: it still has to register with ConnectionsPool and complete
      // connectionJob after flipping the gate (see kdoc above) — cancelling it here would abort
      // that in-flight registration.
      jobs.forEach { if (it !== winnerJob) it.cancel() }
    }
    jobs.joinAll()
    // If nobody won (all addresses failed), the watcher is still awaiting winnerGate — cancel it
    // so this coroutineScope can return instead of hanging on that last child.
    watcher.cancel()
  }

  private suspend fun establishConnection(
    address: String,
    port: Int,
    deviceId: String,
    connectionJob: CompletableDeferred<ConnectOutcome>,
    winnerGate: CompletableDeferred<Job>,
    punchThrough: Boolean = false,
  ): Result<Unit> {
    // Tracks the socket across the whole attempt so the `finally` below can always close it
    // on any non-success exit — including a plain CancellationException thrown mid-suspend
    // (e.g. the watcher cancelling a losing attempt while it's parked in the greeting-read or
    // UKEY2 withTimeout blocks below). `runCatching` would otherwise swallow that cancellation
    // as a Result.failure and the socket, already open, would never be closed: a real fd leak
    // under repeated dials. `handedOff` is set true only once the socket has been registered
    // with ConnectionsPool (or explicitly closed by one of the losing-branch checks below) —
    // i.e. once *something* else owns its lifecycle.
    var socket: io.ktor.network.sockets.Socket? = null
    var handedOff = false
    try {
      return runCatching {

      // withTimeout caps the per-address connect phase.  Ktor's NIO-based
      // connect waits for the OS to complete the TCP 3-way handshake; if the
      // remote address is black-holed (SYN packets silently dropped — not
      // refused) this wait has no OS-level upper bound and can block for tens
      // of seconds, consuming the entire CONNECTION_WAIT_TIMEOUT budget before
      // any other advertised address is tried.  socketTimeout only applies to
      // read/write I/O, not to connect, so withTimeout is the correct mechanism.
      socket = withTimeout(TCP_CONNECT_TIMEOUT_MS) {
        if (punchThrough) {
          // T10 firewall punch-through: bind the dial socket to our own listening port so
          // the outbound SYN creates conntrack state for the peer's inbound SYN to us.
          val ownPort = serverPort.value
          log("Client", "Punch-through dial to $deviceId from local :$ownPort (remote $address:$port)")
          punchThroughConnect(selectorManager, InetSocketAddress(address, port), ownPort)
            ?: error("Punch-through dial to $address:$port failed")
        } else {
          aSocket(selectorManager).tcp().connect(address, port) {
            // Coarse OS-level backstop. The application-level heartbeat is the
            // primary liveness mechanism; keep-alive only helps if the heartbeat
            // coroutine is itself wedged.
            keepAlive = true
          }
        }
      }
      val activeSocket = checkNotNull(socket)
      log("Client", "Connected to $address:$port. Sending greetings")

      handedOff = handshakeAndRegister(activeSocket, address, port, deviceId, connectionJob, winnerGate)
      }
    } finally {
      // Backstop for any exit that didn't already close/hand off the socket — most notably a
      // CancellationException thrown by one of the withTimeout blocks above when the watcher in
      // raceTcpConnections cancels this attempt (loser) while it's suspended mid-handshake.
      // runCatching only catches synchronously-thrown exceptions that already unwound past this
      // point; it does NOT prevent this finally from running, so this still closes the socket even
      // though runCatching's Result ends up a Failure wrapping the CancellationException.
      if (!handedOff) socket?.close()
    }
  }

  /**
   * Shared post-connect phase of a dial: greeting exchange, UKEY2 initiator handshake,
   * endpoint-race gate, and pool registration. Used by [establishConnection] for normal
   * dials and by the T10 punch-through tests for pre-connected (locally bound) sockets.
   *
   * Closes [activeSocket] itself before rethrowing any failure, so the caller's
   * finally-close is an idempotent backstop. Returns true when the socket's lifecycle has
   * been handed off — registered with the pool, managed by the same-device-id server path,
   * or explicitly closed as a lost race — and the caller must not close it again.
   *
   * [winnerGate] is null on paths with no sibling attempts (punch-through tests): the
   * winner-takes-all check is skipped.
   */
  internal suspend fun handshakeAndRegister(
    activeSocket: io.ktor.network.sockets.Socket,
    address: String,
    port: Int,
    deviceId: String,
    connectionJob: CompletableDeferred<ConnectOutcome>,
    winnerGate: CompletableDeferred<Job>?,
  ): Boolean {
    try {
      val self = currentDeviceProvider.get()
      val handshakeMessage = HandshakeMessage(
        deviceId = self.shortDeviceId,
        deviceName = self.deviceName,
        osType = self.osType,
        deviceType = self.deviceType,
        supportsEncryption = true,
      )
      val writeChannel = activeSocket.openWriteChannel(autoFlush = true)
      // Bound the handshake write to match the connect and read phases.  On most
      // JVM / Ktor stacks a single small write is heap-buffered and returns
      // immediately, but on platforms where flush awaits the kernel drain a peer
      // that accepts the TCP handshake then never reads can stall this write
      // indefinitely — consuming the whole connection budget before any other
      // address is tried.
      withTimeout(TCP_CONNECT_TIMEOUT_MS) {
        writeChannel.sendMessage(handshakeMessage, serializer)
      }

      log("Client", "Waiting for response greetings from $deviceId")

      val readChannel = activeSocket.openReadChannel()
      // Bound the wait for the peer's greeting too. A peer can complete the TCP
      // 3-way handshake — satisfying the connect withTimeout above — yet never send
      // its HandshakeMessage: e.g. a connection the peer's kernel queued but the app
      // never accepted (backlog-stalled), a half-open/black-holed socket, or a peer
      // that died right after accept. socketTimeout only covers post-handshake I/O on
      // an established channel and would not fire here, so without this explicit bound
      // that silent peer stalls the whole dial indefinitely — the same black-hole
      // symptom we already cap at the connect phase. Reuse the connect budget: a real
      // peer sends its greeting immediately after accept, well inside this window.
      val serverHandshakeMessage = withTimeout(TCP_CONNECT_TIMEOUT_MS) {
        readChannel.readMessage(serializer) as HandshakeMessage
      }

      if (serverHandshakeMessage.deviceId != deviceId) {
        log("Client", "cant connect. Device $deviceId found is wrong: ${serverHandshakeMessage.deviceId}")
        // Thrown (not a direct connectionJob.complete) so a sibling endpoint still racing (F7) isn't
        // aborted by this one's failure — connectionJob only resolves Failed once every address is
        // exhausted (see raceTcpConnections / performDial). CompletableDeferred discards a losing
        // Failed anyway if a sibling already won, so this stays correct even without the guard.
        error("Device id mismatch for $deviceId: peer identified itself as ${serverHandshakeMessage.deviceId}")
      }

      // Encryption is required: refuse peers (e.g. older builds) that don't advertise it rather
      // than silently falling back to cleartext.
      if (!serverHandshakeMessage.supportsEncryption) {
        log("Client", "Device $deviceId does not support encrypted transport; refusing (encryption required)")
        error("Peer $deviceId does not support encrypted transport")
      }

      log("Client", "Connection established with ${serverHandshakeMessage.deviceId}; starting UKEY2 handshake")

      // Run the UKEY2 handshake (initiator role) over the same socket and bind it to the peer's
      // device identity. Done before any ConnectionMessenger exists so every subsequent frame is
      // encrypted. Bounded (F9): an untimed handshake would hang until the outer 15s
      // CONNECTION_WAIT_TIMEOUT if the peer stalls mid-UKEY2 instead of failing this one attempt.
      val cipher = withTimeout(UKEY2_HANDSHAKE_TIMEOUT_MS) {
        KlardropEncryptedTransport.runInitiatorHandshake(
          readChannel = readChannel,
          writeChannel = writeChannel,
          selfDeviceId = self.shortDeviceId,
          peerDeviceId = deviceId,
          trustManager = trustManager,
        )
      }

      // Winner-takes-all (F7): every advertised TCP endpoint is raced concurrently, so more than one
      // attempt can reach here with a fully authenticated socket. Only the first to flip winnerGate
      // may register itself with ConnectionsPool; every other attempt — even one with a perfectly
      // good handshake — backs off and closes its own socket instead of fighting the winner for the
      // pool slot (which is what used to close an already-Connected socket out from under a caller).
      // winnerGate carries the winning attempt's own Job (see raceTcpConnections) so the
      // cancellation watcher can spare it once it wins.
      if (winnerGate != null &&
        !winnerGate.complete(coroutineContext[Job] ?: error("handshakeAndRegister must run inside a Job"))
      ) {
        log("Client", "Lost the endpoint race for $deviceId @ $address:$port; closing redundant socket")
        activeSocket.close()
        return true
      }

      // Check if client and server have the same device ID (test scenario). The UKEY2 handshake
      // above still ran so the server side completes; we just don't create a competing
      // client-side messenger — the server already manages this socket.
      val clientDeviceId = self.shortDeviceId
      if (clientDeviceId == deviceId) {
        log("Client", "Client and server have same device ID - server will manage the connection")
      } else {
        // Create a ConnectionMessenger for the client side to send messages to the server
        val connection = Connection.Tcp(activeSocket, deviceId)
        val connectionMessenger = ConnectionMessenger(
          coroutines = coroutines,
          connection = connection,
          messagesRouter = messagesRouter,
          readChannel = readChannel,
          writeChannel = writeChannel,
          ackTimeoutConfig = ackTimeoutConfig,
          heartbeatConfig = heartbeatConfig,
          messageSerializer = serializer,
          cipher = cipher,
          initiatedByUs = true,
        )

        // Store the connection in the client's pool keyed by the server's device ID. From this
        // point on the socket is owned by the pool/messenger, not by this attempt.
        connectionsPool.updateConnection(deviceId, connectionMessenger)

        // Start listening for incoming messages (including ACKs) in a separate coroutine
        clientScope.launch {
          connectionMessenger.acceptIncomingMessages()
        }
      }

      connectionJob.complete(ConnectOutcome.Connected)
      return true
    } catch (t: Throwable) {
      runCatching { activeSocket.close() }
      throw t
    }
  }

  private suspend fun establishBleConnection(
    bleConnection: DeviceConnection.BleConnection,
    deviceId: String,
    connectionJob: CompletableDeferred<ConnectOutcome>,
  ) = runCatching {
    val transport = checkNotNull(bleTransport) { "No BLE transport injected" }
    val session = transport.connectCentral(bleConnection.address, deviceId)
    val bridge = BleChannelBridge(session, clientScope).start()

    val self = currentDeviceProvider.get()
    // Central speaks first — send the rich handshake so the server can enrich its
    // VisibleDevices entry. BLE advertisements only carry the bare shortDeviceId
    // for privacy; this is the first place the friendly name is revealed.
    bridge.writeChannel.sendMessage(
      HandshakeMessage(
        deviceId = self.shortDeviceId,
        deviceName = self.deviceName,
        osType = self.osType,
        deviceType = self.deviceType,
        supportsEncryption = true,
      ),
      serializer,
    )
    val serverHandshake = bridge.readChannel.readMessage(serializer) as HandshakeMessage

    if (serverHandshake.deviceId != deviceId) {
      log("Client", "BLE handshake id mismatch: expected $deviceId got ${serverHandshake.deviceId}")
      bridge.close()
      connectionJob.complete(ConnectOutcome.Failed)
      return@runCatching
    }

    // Encryption is required: refuse peers (e.g. older builds) that don't advertise it.
    if (!serverHandshake.supportsEncryption) {
      log("Client", "BLE peer $deviceId does not support encrypted transport; refusing (encryption required)")
      bridge.close()
      connectionJob.complete(ConnectOutcome.Failed)
      return@runCatching
    }

    // Server's reply may carry rich identity — enrich our VisibleDevices entry so
    // the BLE peer shows up with friendly name + OS/device type instead of the
    // shortDeviceId placeholder.
    if (serverHandshake.deviceName.isNotEmpty()) {
      runCatching {
        visibleDevices.onNewDeviceVisible(
          com.carlom.klardrop.common.discovery.DeviceInfo(
            deviceId = serverHandshake.deviceId,
            name = serverHandshake.deviceName,
            deviceType = serverHandshake.deviceType,
            osType = serverHandshake.osType,
          ),
          bleConnection,
        )
      }
    }

    // We (the central) spoke first, so we are the UKEY2 initiator — same role mapping as the
    // TCP client. Runs over the BLE bridge channels before any messenger exists.
    val cipher = KlardropEncryptedTransport.runInitiatorHandshake(
      readChannel = bridge.readChannel,
      writeChannel = bridge.writeChannel,
      selfDeviceId = self.shortDeviceId,
      peerDeviceId = deviceId,
      trustManager = trustManager,
    )

    val connection = Connection.Ble(session, deviceId)
    val connectionMessenger = ConnectionMessenger(
      coroutines = coroutines,
      connection = connection,
      messagesRouter = messagesRouter,
      readChannel = bridge.readChannel,
      writeChannel = bridge.writeChannel,
      ackTimeoutConfig = ackTimeoutConfig,
      heartbeatConfig = heartbeatConfig,
      messageSerializer = serializer,
      cipher = cipher,
      initiatedByUs = true,
    )
    connectionsPool.updateConnection(deviceId, connectionMessenger)
    clientScope.launch { connectionMessenger.acceptIncomingMessages() }
    connectionJob.complete(ConnectOutcome.Connected)
  }
}
