package com.carlom.klardrop.common

import com.carlom.klardrop.common.communication.ConnectionMessenger
import com.carlom.klardrop.common.communication.ConnectionsPool

class FakeConnectionPool: ConnectionsPool {
  private val connections = mutableMapOf<String, ConnectionMessenger>()

  override suspend fun isAvailable(deviceId: String): Boolean {
    return connections.containsKey(deviceId)
  }

  override suspend fun updateConnection(deviceId: String, socket: ConnectionMessenger) {
    connections[deviceId] = socket
  }

  override suspend fun getConnection(deviceId: String): ConnectionMessenger? {
    return connections[deviceId]
  }

  override suspend fun closeAllConnections() {
    connections.clear()
  }

  override suspend fun closeConnection(deviceId: String) {
    connections.remove(deviceId)
  }
}