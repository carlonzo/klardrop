package com.carlom.klardrop.common.trust

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory implementation of TrustStorage for testing and development.
 * Data will be lost when application restarts.
 * 
 * Stores both ECDH and ECDSA keys for each trusted device.
 */
class InMemoryTrustStorage : TrustStorage {
    
    private val trustedDevices = mutableMapOf<String, ByteArray>()  // ECDH keys
    private val ecdsaKeys = mutableMapOf<String, ByteArray>()  // ECDSA keys for signing
    private val mutex = Mutex()
    
    override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {
        mutex.withLock {
            trustedDevices[deviceId] = publicKey.copyOf()
        }
    }
    
    override suspend fun getTrustedDeviceKey(deviceId: String): ByteArray? {
        return mutex.withLock {
            trustedDevices[deviceId]?.copyOf()
        }
    }
    
    override suspend fun getAllTrustedDevices(): Map<String, ByteArray> {
        return mutex.withLock {
            trustedDevices.mapValues { it.value.copyOf() }
        }
    }
    
    override suspend fun removeTrustedDevice(deviceId: String) {
        mutex.withLock {
            trustedDevices.remove(deviceId)
            ecdsaKeys.remove(deviceId)
        }
    }
    
    override suspend fun clearAllTrustedDevices() {
        mutex.withLock {
            trustedDevices.clear()
            ecdsaKeys.clear()
        }
    }
    
    override suspend fun storeECDSAKey(deviceId: String, ecdsaPublicKey: ByteArray) {
        mutex.withLock {
            ecdsaKeys[deviceId] = ecdsaPublicKey.copyOf()
        }
    }
    
    override suspend fun getECDSAKey(deviceId: String): ByteArray? {
        return mutex.withLock {
            ecdsaKeys[deviceId]?.copyOf()
        }
    }
}