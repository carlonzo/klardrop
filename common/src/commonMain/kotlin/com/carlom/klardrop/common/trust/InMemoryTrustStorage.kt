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
    private val sharedSecrets = mutableMapOf<String, ByteArray>()  // ECDH-derived shared secrets
    private var devicePrivateKey: ByteArray? = null  // Device's own private key
    private var devicePublicKey: ByteArray? = null  // Device's own public key (matching)
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
            sharedSecrets.remove(deviceId)
        }
    }

    override suspend fun clearAllTrustedDevices() {
        mutex.withLock {
            trustedDevices.clear()
            ecdsaKeys.clear()
            sharedSecrets.clear()
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
    
    // Device Identity Persistence Methods
    
    override suspend fun storeDevicePrivateKey(privateKey: ByteArray) {
        mutex.withLock {
            devicePrivateKey = privateKey.copyOf()
        }
    }
    
    override suspend fun getDevicePrivateKey(): ByteArray? {
        return mutex.withLock {
            devicePrivateKey?.copyOf()
        }
    }
    
    override suspend fun deleteDevicePrivateKey() {
        mutex.withLock {
            devicePrivateKey = null
            devicePublicKey = null
        }
    }

    override suspend fun storeDevicePublicKey(publicKey: ByteArray) {
        mutex.withLock {
            devicePublicKey = publicKey.copyOf()
        }
    }

    override suspend fun getDevicePublicKey(): ByteArray? {
        return mutex.withLock {
            devicePublicKey?.copyOf()
        }
    }

    override suspend fun storeSharedSecret(deviceId: String, sharedSecret: ByteArray) {
        mutex.withLock {
            sharedSecrets[deviceId] = sharedSecret.copyOf()
        }
    }

    override suspend fun getSharedSecret(deviceId: String): ByteArray? {
        return mutex.withLock {
            sharedSecrets[deviceId]?.copyOf()
        }
    }
}