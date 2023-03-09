package com.carlom.klardrop.common.communication

import io.ktor.websocket.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ConnectionsPool {

  suspend fun isAvailable(deviceId: String): Boolean

  suspend fun updateConnection(deviceId: String, socket: ConnectionMessenger)

  suspend fun getConnection(deviceId: String): ConnectionMessenger?

  suspend fun closeAllConnections()
}

internal class ConnectionsPoolImpl : ConnectionsPool {

  private val mutex = Mutex(locked = false)
  private val connections = mutableMapOf<String, ConnectionMessenger>()

  override suspend fun isAvailable(deviceId: String): Boolean {
    mutex.withLock {
      val connection = connections[deviceId]?.connection ?: return false

      if (!connection.session.isActive) {
        connections.remove(deviceId)
        return false
      } else {
        return true
      }
    }
  }

  override suspend fun updateConnection(deviceId: String, connectionMessenger: ConnectionMessenger) {
    mutex.withLock {
      connections[deviceId]?.connection?.session?.close()
      println("ConnectionPool: Closing connection before updating with $deviceId")

      connections.put(deviceId, connectionMessenger)
    }
  }

  override suspend fun getConnection(deviceId: String): ConnectionMessenger? {
    return mutex.withLock {
      connections[deviceId]
    }
  }

  override suspend fun closeAllConnections() {
    mutex.withLock {
      connections.forEach { (_, connectionMessenger) ->
        connectionMessenger.connection.session.close()
      }
      connections.clear()
    }
  }

}

data class Connection(
  val session: DefaultWebSocketSession,
  val deviceId: String
)