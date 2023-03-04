package com.carlom.klardrop.common.communication

import io.ktor.network.sockets.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConnectionsPool {

  private val mutex = Mutex(locked = false)

  private val connections = mutableMapOf<String, Socket>()

  suspend fun isAvailable(deviceId: String): Boolean {
    return mutex.withLock {
      connections.containsKey(deviceId)
    }
  }

  suspend fun updateConnection(deviceId: String, socket: Socket) {
    mutex.withLock {
      connections[deviceId]?.close()
      println("ConnectionPool: Closing connection before updating with $deviceId")

      connections.put(deviceId, socket)
    }
  }

  suspend fun getConnection(deviceId: String): Socket? {
    return mutex.withLock {
      connections[deviceId]
    }
  }

  suspend fun closeAllConnections() {
    mutex.withLock {
      connections.forEach { (_, socket) ->
        socket.close()
      }
      connections.clear()
    }
  }

}