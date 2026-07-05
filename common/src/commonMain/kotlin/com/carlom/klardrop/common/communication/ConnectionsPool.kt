package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.ble.BleSession
import com.carlom.klardrop.common.network.NetworkChangeEvent
import com.carlom.klardrop.common.network.NetworkLifecycleMonitor
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.isClosed
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ConnectionsPool {

  /**
   * Per-device reachability state derived from pool membership and probe
   * outcomes. UI consumers (chat screen, device list) observe this to render
   * Online/Offline/Connecting indicators without having to drive their own
   * connection state machine.
   */
  val reachability: StateFlow<Map<String, Reachability>>

  suspend fun isAvailable(deviceId: String): Boolean

  suspend fun updateConnection(deviceId: String, connectionMessenger: ConnectionMessenger)

  suspend fun getConnection(deviceId: String): ConnectionMessenger?

  suspend fun closeAllConnections()

  suspend fun closeConnection(deviceId: String)

  /** Signal that a probe is in flight for [deviceId] — transitions to [Reachability.Probing]. */
  fun markProbing(deviceId: String)

  /** Signal that a probe / send attempt failed — transitions to [Reachability.Unreachable]. */
  fun markUnreachable(deviceId: String)
}

sealed interface Reachability {
  /** No probe attempted yet, or pool was just flushed. */
  data object Unknown : Reachability

  /** A probe is currently in flight. */
  data object Probing : Reachability

  /** A live connection exists in the pool, or the last probe succeeded. */
  data object Reachable : Reachability

  /** Last probe / send attempt failed. */
  data object Unreachable : Reachability
}

internal class ConnectionsPoolImpl(
  coroutines: Coroutines? = null,
  networkLifecycleMonitor: NetworkLifecycleMonitor? = null,
  private val currentDeviceProvider: com.carlom.klardrop.common.discovery.CurrentDeviceProvider? = null,
) : ConnectionsPool {

  /**
   * Test-only constructor: accepts the raw [Flow] of [NetworkChangeEvent]s so tests
   * can drive events via a [kotlinx.coroutines.flow.MutableSharedFlow] without needing
   * the platform-specific [NetworkLifecycleMonitor] expect class.
   */
  internal constructor(
    coroutines: Coroutines,
    networkEvents: Flow<NetworkChangeEvent>,
    currentDeviceProvider: com.carlom.klardrop.common.discovery.CurrentDeviceProvider? = null,
  ) : this(coroutines = null, networkLifecycleMonitor = null, currentDeviceProvider = currentDeviceProvider) {
    subscribeToNetworkEvents(coroutines, networkEvents)
  }

  private val mutex = Mutex(locked = false)
  private val connections = mutableMapOf<String, ConnectionMessenger>()
  private val reachabilityFlow = MutableStateFlow<Map<String, Reachability>>(emptyMap())

  // Our own short device id, resolved once and cached, used to break simultaneous-open ties.
  private var cachedSelfShortId: String? = null

  override val reachability: StateFlow<Map<String, Reachability>> = reachabilityFlow.asStateFlow()

  init {
    // Pool subscribes to coarse network events directly so a single source of
    // truth (the lifecycle monitor) drives both mDNS rebuilds and connection
    // flushing. After NIC up/down or post-wake we have to assume every pooled
    // socket is half-open; dropping them forces the next send to redial fresh.
    if (coroutines != null && networkLifecycleMonitor != null) {
      subscribeToNetworkEvents(coroutines, networkLifecycleMonitor.observe())
    }
  }

  /**
   * Subscribes to [events] with a manual debounce so that a rapid burst of spurious
   * [NetworkChangeEvent.Changed] emissions (e.g. Android's onCapabilitiesChanged /
   * onLinkPropertiesChanged firing in quick succession for signal-strength or DNS
   * updates) collapses into a single flush rather than closing every in-flight
   * connection on each callback.
   *
   * Implementation uses a cancel-and-relaunch pattern with [delay] rather than
   * [kotlinx.coroutines.flow.debounce] because `debounce` is `@FlowPreview` and
   * does not respect the coroutine dispatcher's virtual clock in tests. Plain [delay]
   * IS virtual-time aware and gives correct behavior in both production and tests.
   *
   * The debounce window ([NETWORK_EVENT_DEBOUNCE_MS] = 500 ms) is chosen to:
   *  - absorb the typical Android burst of 2-4 consecutive callbacks that arrives
   *    within ~100 ms when capabilities change;
   *  - still react within half a second on a genuine loss / address change, which
   *    is well within user perception for a reconnect.
   *
   * A real network loss or address change still emits one or more [Changed] events;
   * after the debounce window elapses those events resolve to a single flush —
   * preserving the existing "dead sockets get cleared on a real change" guarantee.
   */
  private fun subscribeToNetworkEvents(coroutines: Coroutines, events: Flow<NetworkChangeEvent>) {
    val scope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
    var debounceJob: Job? = null
    events
      .onEach {
        // Cancel any in-flight debounce timer so back-to-back events within the window
        // collapse into a single flush.
        debounceJob?.cancel()
        debounceJob = scope.launch {
          delay(NETWORK_EVENT_DEBOUNCE_MS)
          log("ConnectionPool", "Network change detected (debounced); flushing idle pooled connections")
          closeIdleConnections()
        }
      }
      .launchIn(scope)
  }

  private companion object {
    /** Debounce window in milliseconds for network-change events. See [subscribeToNetworkEvents]. */
    const val NETWORK_EVENT_DEBOUNCE_MS = 500L
  }

  override suspend fun isAvailable(deviceId: String): Boolean {
    mutex.withLock {
      val connection = connections[deviceId] ?: return false
      return !connection.isClosed()
    }
  }

  override suspend fun updateConnection(deviceId: String, connectionMessenger: ConnectionMessenger) {
    mutex.withLock {
      log("ConnectionPool", "[DEBUG] updateConnection() called for $deviceId")

      val existing = connections[deviceId]
      if (existing != null && !existing.isClosed()) {
        // Tie-break ONLY a genuine simultaneous open — one connection WE dialed and one the PEER
        // dialed (different initiatedByUs). Keep the one initiated by the smaller short id so both
        // peers deterministically converge on the same socket. If the directions are the SAME, this
        // is a reconnect (the peer re-dialed after a stale/dead socket), not a race: the NEW one
        // must win, otherwise we'd keep a dead connection and RST the peer's fresh one.
        val selfId = resolveSelfShortId()
        val isSimultaneousOpen = selfId != null && existing.initiatedByUs != connectionMessenger.initiatedByUs
        if (isSimultaneousOpen) {
          val weShouldInitiate = selfId!! < deviceId
          val keepExisting = existing.initiatedByUs == weShouldInitiate
          if (keepExisting) {
            log("ConnectionPool", "Simultaneous open for $deviceId: keeping existing (initiatedByUs=${existing.initiatedByUs}), dropping incoming")
            connectionMessenger.close()
            return@withLock
          }
          log("ConnectionPool", "Simultaneous open for $deviceId: replacing existing with incoming (tie-break)")
        } else {
          log("ConnectionPool", "Replacing live connection for $deviceId with reconnect (initiatedByUs=${connectionMessenger.initiatedByUs})")
        }
        existing.close()
        connections.remove(deviceId)
      } else if (existing != null) {
        log("ConnectionPool", "[DEBUG] Old connection is closed, replacing for $deviceId")
        existing.close()
        connections.remove(deviceId)
      }

      connections[deviceId] = connectionMessenger
      log("ConnectionPool", "Updated connection with $deviceId")
      log("ConnectionPool", "[DEBUG] Total connections: ${connections.size}")
    }
    setReachability(deviceId, Reachability.Reachable)
  }

  /** Resolve and cache our own short device id (used only for simultaneous-open tie-breaking). */
  private suspend fun resolveSelfShortId(): String? {
    cachedSelfShortId?.let { return it }
    return runCatching { currentDeviceProvider?.get()?.shortDeviceId }
      .getOrNull()
      ?.also { cachedSelfShortId = it }
  }

  override suspend fun getConnection(deviceId: String): ConnectionMessenger? {
    return mutex.withLock {
      log("ConnectionPool", "[DEBUG] getConnection() called for $deviceId")
      val connection = connections[deviceId] ?: run {
        log("ConnectionPool", "[DEBUG] No existing connection found for $deviceId")
        return@withLock null
      }

      // Remove and return null if connection is closed
      val isClosed = connection.isClosed()
      log("ConnectionPool", "[DEBUG] Found connection for $deviceId, isClosed = $isClosed")
      
      if (isClosed) {
        log("ConnectionPool", "Removing closed connection for $deviceId")
        connections.remove(deviceId)
        return@withLock null
      }

      log("ConnectionPool", "[DEBUG] Returning active connection for $deviceId")
      connection
    }
  }

  override suspend fun closeAllConnections() {
    val flushedDeviceIds: List<String>
    mutex.withLock {
      flushedDeviceIds = connections.keys.toList()
      connections.values.forEach { it.close() }
      connections.clear()
    }
    if (flushedDeviceIds.isNotEmpty()) {
      reachabilityFlow.update { current ->
        current.toMutableMap().apply {
          // Reset everything we had connections for: we don't know the new
          // state until the next probe lands. Don't jump straight to
          // Unreachable here because that would briefly flash an Offline
          // indicator on a peer that's about to be re-probed.
          flushedDeviceIds.forEach { put(it, Reachability.Unknown) }
        }
      }
    }
  }

  /**
   * Network-flush variant of [closeAllConnections]: closes only connections that are
   * currently IDLE (no in-flight write). Connections whose [ConnectionMessenger.hasInflightWrite]
   * returns true are skipped — a live chunked-file transfer holds the writeLock for the whole
   * send, so closing the socket mid-write would abort an otherwise healthy transfer triggered by
   * spurious network noise (Android's onCapabilitiesChanged / onLinkPropertiesChanged bursts).
   *
   * Mirrors the probe guard already present in [ConnectionMessenger.heartbeatLoop]: tryLock
   * succeeds → messenger is idle → safe to flush; tryLock fails → writer is mid-frame → skip.
   *
   * Called exclusively from [subscribeToNetworkEvents]' debounce job so that real network
   * address changes still flush idle sockets while live transfers survive the event burst.
   */
  private suspend fun closeIdleConnections() {
    val flushedDeviceIds = mutableListOf<String>()
    mutex.withLock {
      val iter = connections.iterator()
      while (iter.hasNext()) {
        val (deviceId, messenger) = iter.next()
        if (messenger.hasInflightWrite()) {
          log("ConnectionPool", "Skipping network-flush for $deviceId: write in progress")
          continue
        }
        messenger.close()
        iter.remove()
        flushedDeviceIds += deviceId
      }
    }
    if (flushedDeviceIds.isNotEmpty()) {
      reachabilityFlow.update { current ->
        current.toMutableMap().apply {
          flushedDeviceIds.forEach { put(it, Reachability.Unknown) }
        }
      }
    }
  }

  override suspend fun closeConnection(deviceId: String) {
    val hadConnection: Boolean
    mutex.withLock {
      val connectionMessenger = connections[deviceId]
      hadConnection = connectionMessenger != null
      connectionMessenger?.close()
      connections.remove(deviceId)
    }
    if (hadConnection) setReachability(deviceId, Reachability.Unreachable)
  }

  override fun markProbing(deviceId: String) {
    setReachability(deviceId, Reachability.Probing)
  }

  override fun markUnreachable(deviceId: String) {
    setReachability(deviceId, Reachability.Unreachable)
  }

  private fun setReachability(deviceId: String, state: Reachability) {
    reachabilityFlow.update { current ->
      if (current[deviceId] == state) current
      else current.toMutableMap().apply { put(deviceId, state) }
    }
  }

}

/**
 * A live peer connection owned by [ConnectionMessenger]. Backed by either a TCP socket
 * (the mDNS/Klardrop-over-TCP path) or a BLE GATT [BleSession]. Consumers only need
 * [deviceId], [isClosed], and [close] — the transport-specific handle stays internal to
 * the variant.
 */
sealed class Connection {
  abstract val deviceId: String
  abstract val isClosed: Boolean
  abstract fun close()

  class Tcp(val socket: Socket, override val deviceId: String) : Connection() {
    override val isClosed: Boolean get() = socket.isClosed
    override fun close() {
      if (!socket.isClosed) socket.close()
    }
  }

  class Ble(val session: BleSession, override val deviceId: String) : Connection() {
    override val isClosed: Boolean get() = !session.isOpen
    override fun close() {
      if (session.isOpen) session.close()
    }
  }
}

