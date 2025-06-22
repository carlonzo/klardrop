package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.utils.log
import io.ktor.network.sockets.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ConnectionsPool {

  suspend fun isAvailable(deviceId: String): Boolean

  suspend fun updateConnection(deviceId: String, connectionMessenger: ConnectionMessenger)

  suspend fun getConnection(deviceId: String): ConnectionMessenger?

  suspend fun closeAllConnections()

  suspend fun closeConnection(deviceId: String)
}

internal class ConnectionsPoolImpl : ConnectionsPool {

  private val mutex = Mutex(locked = false)
  private val connections = mutableMapOf<String, ConnectionMessenger>()

  override suspend fun isAvailable(deviceId: String): Boolean {
    mutex.withLock {
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
      return connections[deviceId]?.isClosed() == false
//      }
    }
  }

  override suspend fun updateConnection(deviceId: String, connectionMessenger: ConnectionMessenger) {
    mutex.withLock {

      connections[deviceId]?.let { oldConnectionMessenger ->
        oldConnectionMessenger.close()
        connections.remove(deviceId)
        log("ConnectionPool", "Closing connection before updating with $deviceId")
      }

      connections[deviceId] = connectionMessenger
      log("ConnectionPool", "Updated connection with $deviceId")
    }
  }

  override suspend fun getConnection(deviceId: String): ConnectionMessenger? {
    return mutex.withLock {
      val connection = connections[deviceId] ?: return@withLock null

      // Remove and return null if connection is closed
      if (connection.isClosed()) {
        log("ConnectionPool", "Removing closed connection for $deviceId")
        connections.remove(deviceId)
        return@withLock null
      }

      connection
    }
  }

  override suspend fun closeAllConnections() {
    mutex.withLock {
      connections.forEach { (deviceId, connectionMessenger) ->
        connectionMessenger.close()
        connections.remove(deviceId)
      }
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
  val socket: Socket,
  val deviceId: String
)

