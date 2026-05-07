package com.carlom.klardrop.common

import com.carlom.klardrop.common.communication.ConnectionMessenger
import com.carlom.klardrop.common.communication.ConnectionsPool
import com.carlom.klardrop.common.communication.Reachability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeConnectionPool: ConnectionsPool {
  private val connections = mutableMapOf<String, ConnectionMessenger>()
  private val reachabilityFlow = MutableStateFlow<Map<String, Reachability>>(emptyMap())

  override val reachability: StateFlow<Map<String, Reachability>> = reachabilityFlow.asStateFlow()

  override suspend fun isAvailable(deviceId: String): Boolean {
    return connections.containsKey(deviceId)
  }

  override suspend fun updateConnection(deviceId: String, connectionMessenger: ConnectionMessenger) {
    connections[deviceId] = connectionMessenger
    reachabilityFlow.value = reachabilityFlow.value + (deviceId to Reachability.Reachable)
  }

  override suspend fun getConnection(deviceId: String): ConnectionMessenger? {
    return connections[deviceId]
  }

  override suspend fun closeAllConnections() {
    connections.clear()
    reachabilityFlow.value = emptyMap()
  }

  override suspend fun closeConnection(deviceId: String) {
    connections.remove(deviceId)
    reachabilityFlow.value = reachabilityFlow.value + (deviceId to Reachability.Unreachable)
  }

  override fun markProbing(deviceId: String) {
    reachabilityFlow.value = reachabilityFlow.value + (deviceId to Reachability.Probing)
  }

  override fun markUnreachable(deviceId: String) {
    reachabilityFlow.value = reachabilityFlow.value + (deviceId to Reachability.Unreachable)
  }
}