package com.carlom.klardrop.cloud.deviceregistry.services

import java.util.concurrent.ConcurrentHashMap

interface BrokerSessionManager {
    fun registerSession(deviceId: String, sessionId: String)
    fun disconnectDevice(deviceId: String)
    fun isConnected(deviceId: String): Boolean
}

class InMemoryBrokerSessionManager : BrokerSessionManager {
    private val sessions = ConcurrentHashMap<String, MutableSet<String>>()

    override fun registerSession(deviceId: String, sessionId: String) {
        sessions.computeIfAbsent(deviceId) { ConcurrentHashMap.newKeySet() }.add(sessionId)
    }

    override fun disconnectDevice(deviceId: String) {
        sessions.remove(deviceId)
    }

    override fun isConnected(deviceId: String): Boolean = sessions[deviceId]?.isNotEmpty() == true
}
