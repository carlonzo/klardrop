package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.utils.log
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ConnectionsPool {

  suspend fun isAvailable(deviceId: String): Boolean

  suspend fun updateConnection(deviceId: String, socket: ConnectionMessenger)

  suspend fun getConnection(deviceId: String): ConnectionMessenger?

  suspend fun closeAllConnections()

  suspend fun closeConnection(deviceId: String)
}

internal class ConnectionsPoolImpl : ConnectionsPool {

  private val mutex = Mutex(locked = false)
  private val connections = mutableMapOf<String, ConnectionMessenger>()

  override suspend fun isAvailable(deviceId: String): Boolean {
    mutex.withLock {
//      val connection = connections[deviceId]?.connection ?: return false
//
//      if (!connection.session.isClosed()) {
//        log("ConnectionPool: Connection with $deviceId is closed, removing")
//
//        connections[deviceId]?.connection?.session?.close()
//        connections.remove(deviceId)
//        return false
//      } else {
      return true
//      }
    }
  }

  override suspend fun updateConnection(deviceId: String, socket: ConnectionMessenger) {
    mutex.withLock {
      if (connections.containsKey(deviceId)) {
        connections[deviceId]?.close()
        connections.remove(deviceId)
        log("ConnectionPool: Closing connection before updating with $deviceId")
      }

      connections[deviceId] = socket
      log("ConnectionPool: Updated connection with $deviceId")
    }
  }

  override suspend fun getConnection(deviceId: String): ConnectionMessenger? {
    return mutex.withLock { connections[deviceId] }
  }

  override suspend fun closeAllConnections() {
    mutex.withLock {
      connections.keys.forEach { closeConnection(it) }
    }
  }

  override suspend fun closeConnection(deviceId: String) {
    return mutex.withLock {
      val connectionMessenger = connections[deviceId] ?: return

      connectionMessenger.close()
      connections.remove(deviceId)
    }
  }

}

data class Connection(
  val session: DefaultWebSocketSession,
  val deviceId: String
)

internal fun DefaultWebSocketSession.isClosed(): Boolean {
  // if there is a close reason, the connection may be closed
  return closeReason.isCompleted
}