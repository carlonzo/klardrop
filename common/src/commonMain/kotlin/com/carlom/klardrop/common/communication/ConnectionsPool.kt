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

