package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.ble.BleSession
import com.carlom.klardrop.common.network.NetworkLifecycleMonitor
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.log
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.isClosed
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
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
) : ConnectionsPool {

  private val mutex = Mutex(locked = false)
  private val connections = mutableMapOf<String, ConnectionMessenger>()
  private val reachabilityFlow = MutableStateFlow<Map<String, Reachability>>(emptyMap())

  override val reachability: StateFlow<Map<String, Reachability>> = reachabilityFlow.asStateFlow()

  init {
    // Pool subscribes to coarse network events directly so a single source of
    // truth (the lifecycle monitor) drives both mDNS rebuilds and connection
    // flushing. After NIC up/down or post-wake we have to assume every pooled
    // socket is half-open; dropping them forces the next send to redial fresh.
    if (coroutines != null && networkLifecycleMonitor != null) {
      val scope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)
      networkLifecycleMonitor.observe()
        .onEach {
          log("ConnectionPool", "Network change detected; flushing all pooled connections")
          closeAllConnections()
        }
        .launchIn(scope)
    }
  }

  override suspend fun isAvailable(deviceId: String): Boolean {
    mutex.withLock {
      log("ConnectionPool", "[DEBUG] isAvailable() called for $deviceId")
      val connection = connections[deviceId]
      if (connection == null) {
        log("ConnectionPool", "[DEBUG] isAvailable() = false (no connection) for $deviceId")
        return false
      }
      
      val isClosed = connection.isClosed()
      val isAvailable = !isClosed
      log("ConnectionPool", "[DEBUG] isAvailable() = $isAvailable (isClosed=$isClosed) for $deviceId")
      
//      TODO check if connection is closed. below looks like was not working
//      val connection = connections[deviceId]?.connection ?: return false
//
//      if (!connection.session.isClosed()) {
//        log("ConnectionPool: Connection with $deviceId is closed, removing")
//
//        connections[deviceId]?.connection?.session?.close()
//        connections.remove(deviceId)
//        return false
//      } else {
      return isAvailable
//      }
    }
  }

  override suspend fun updateConnection(deviceId: String, connectionMessenger: ConnectionMessenger) {
    mutex.withLock {
      log("ConnectionPool", "[DEBUG] updateConnection() called for $deviceId")

      connections[deviceId]?.let { oldConnectionMessenger ->
        val isOldClosed = oldConnectionMessenger.isClosed()
        log("ConnectionPool", "[DEBUG] Found existing connection for $deviceId, isClosed = $isOldClosed")

        if (isOldClosed) {
          log("ConnectionPool", "[DEBUG] Old connection is closed, safe to replace for $deviceId")
        } else {
          log("ConnectionPool", "[DEBUG] WARNING: Closing ACTIVE connection for $deviceId to replace it!")
        }

        oldConnectionMessenger.close()
        connections.remove(deviceId)
        log("ConnectionPool", "Closing connection before updating with $deviceId")
      }

      connections[deviceId] = connectionMessenger
      log("ConnectionPool", "Updated connection with $deviceId")
      log("ConnectionPool", "[DEBUG] Total connections: ${connections.size}")
    }
    setReachability(deviceId, Reachability.Reachable)
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

