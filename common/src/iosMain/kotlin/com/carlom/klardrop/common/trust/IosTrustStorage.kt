package com.carlom.klardrop.common.trust

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.*

/**
 * iOS implementation of TrustStorage using UserDefaults.
 * Keys are stored as Base64-encoded strings in UserDefaults.
 * 
 * Stores both ECDH keys (for key exchange) and ECDSA keys (for message signing).
 */
class IosTrustStorage : TrustStorage {
    
    companion object {
        private const val TRUST_KEY_PREFIX = "klardrop_trust_"
        private const val ECDSA_KEY_PREFIX = "klardrop_ecdsa_"
        private const val DEVICE_PRIVATE_KEY = "klardrop_device_private_key"
    }
    
    private val mutex = Mutex()
    private val userDefaults = NSUserDefaults.standardUserDefaults
    
    override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {
        mutex.withLock {
            val encodedKey = publicKey.toBase64String()
            val key = TRUST_KEY_PREFIX + deviceId
            userDefaults.setObject(encodedKey, key)
            userDefaults.synchronize()
        }
    }
    
    override suspend fun getTrustedDeviceKey(deviceId: String): ByteArray? {
        return mutex.withLock {
            val key = TRUST_KEY_PREFIX + deviceId
            val encodedKey = userDefaults.stringForKey(key) ?: return@withLock null
            
            return@withLock encodedKey.fromBase64OrNull().also { decoded ->
                if (decoded == null) {
                    // Invalid Base64 encoding - remove corrupted entry
                    userDefaults.removeObjectForKey(key)
                    userDefaults.synchronize()
                }
            }
        }
    }
    
    override suspend fun getAllTrustedDevices(): Map<String, ByteArray> {
        return mutex.withLock {
            val result = mutableMapOf<String, ByteArray>()
            val allKeys = userDefaults.dictionaryRepresentation().keys
            
            allKeys.forEach { keyObj ->
                val key = keyObj.toString()
                if (key.startsWith(TRUST_KEY_PREFIX)) {
                    val deviceId = key.removePrefix(TRUST_KEY_PREFIX)
                    val encodedKey = userDefaults.stringForKey(key)
                    if (encodedKey != null) {
                        val publicKey = encodedKey.fromBase64OrNull()
                        if (publicKey != null) {
                            result[deviceId] = publicKey
                        }
                        // Skip corrupted entries (when fromBase64OrNull returns null)
                    }
                }
            }
            
            result
        }
    }
    
    override suspend fun removeTrustedDevice(deviceId: String) {
        mutex.withLock {
            // Remove ECDH key
            val ecdhKey = TRUST_KEY_PREFIX + deviceId
            userDefaults.removeObjectForKey(ecdhKey)
            
            // Remove ECDSA key
            val ecdsaKey = ECDSA_KEY_PREFIX + deviceId
            userDefaults.removeObjectForKey(ecdsaKey)
            
            userDefaults.synchronize()
        }
    }
    
    override suspend fun clearAllTrustedDevices() {
        mutex.withLock {
            val allKeys = userDefaults.dictionaryRepresentation().keys
            
            allKeys.forEach { keyObj ->
                val key = keyObj.toString()
                if (key.startsWith(TRUST_KEY_PREFIX) || key.startsWith(ECDSA_KEY_PREFIX)) {
                    userDefaults.removeObjectForKey(key)
                }
            }
            
            userDefaults.synchronize()
        }
    }
    
    override suspend fun storeECDSAKey(deviceId: String, ecdsaPublicKey: ByteArray) {
        mutex.withLock {
            val encodedKey = ecdsaPublicKey.toBase64String()
            val key = ECDSA_KEY_PREFIX + deviceId
            userDefaults.setObject(encodedKey, key)
            userDefaults.synchronize()
        }
    }
    
    override suspend fun getECDSAKey(deviceId: String): ByteArray? {
        return mutex.withLock {
            val key = ECDSA_KEY_PREFIX + deviceId
            val encodedKey = userDefaults.stringForKey(key) ?: return@withLock null
            
            return@withLock encodedKey.fromBase64OrNull().also { decoded ->
                if (decoded == null) {
                    // Invalid Base64 encoding - remove corrupted entry
                    userDefaults.removeObjectForKey(key)
                    userDefaults.synchronize()
                }
            }
        }
    }
    
    // Device Identity Persistence Methods
    
    override suspend fun storeDevicePrivateKey(privateKey: ByteArray) {
        mutex.withLock {
            val encodedKey = privateKey.toBase64String()
            userDefaults.setObject(encodedKey, DEVICE_PRIVATE_KEY)
            userDefaults.synchronize()
        }
    }
    
    override suspend fun getDevicePrivateKey(): ByteArray? {
        return mutex.withLock {
            val encodedKey = userDefaults.stringForKey(DEVICE_PRIVATE_KEY) ?: return@withLock null
            
            return@withLock encodedKey.fromBase64OrNull().also { decoded ->
                if (decoded == null) {
                    // Invalid Base64 encoding - remove corrupted entry
                    userDefaults.removeObjectForKey(DEVICE_PRIVATE_KEY)
                    userDefaults.synchronize()
                }
            }
        }
    }
    
    override suspend fun deleteDevicePrivateKey() {
        mutex.withLock {
            userDefaults.removeObjectForKey(DEVICE_PRIVATE_KEY)
            userDefaults.synchronize()
        }
    }
}