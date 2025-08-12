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
    }
    
    private val mutex = Mutex()
    private val userDefaults = NSUserDefaults.standardUserDefaults
    
    override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {
        mutex.withLock {
            val encodedKey = publicKey.encodeBase64()
            val key = TRUST_KEY_PREFIX + deviceId
            userDefaults.setObject(encodedKey, key)
            userDefaults.synchronize()
        }
    }
    
    override suspend fun getTrustedDeviceKey(deviceId: String): ByteArray? {
        return mutex.withLock {
            val key = TRUST_KEY_PREFIX + deviceId
            val encodedKey = userDefaults.stringForKey(key) ?: return@withLock null
            
            return@withLock try {
                encodedKey.decodeBase64()
            } catch (e: Exception) {
                // Invalid Base64 encoding - remove corrupted entry
                userDefaults.removeObjectForKey(key)
                userDefaults.synchronize()
                null
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
                        try {
                            val publicKey = encodedKey.decodeBase64()
                            result[deviceId] = publicKey
                        } catch (e: Exception) {
                            // Skip corrupted entries
                        }
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
            val encodedKey = ecdsaPublicKey.encodeBase64()
            val key = ECDSA_KEY_PREFIX + deviceId
            userDefaults.setObject(encodedKey, key)
            userDefaults.synchronize()
        }
    }
    
    override suspend fun getECDSAKey(deviceId: String): ByteArray? {
        return mutex.withLock {
            val key = ECDSA_KEY_PREFIX + deviceId
            val encodedKey = userDefaults.stringForKey(key) ?: return@withLock null
            
            return@withLock try {
                encodedKey.decodeBase64()
            } catch (e: Exception) {
                // Invalid Base64 encoding - remove corrupted entry
                userDefaults.removeObjectForKey(key)
                userDefaults.synchronize()
                null
            }
        }
    }
}

/**
 * Simple Base64 encoding/decoding functions for iOS
 */
private fun ByteArray.encodeBase64(): String {
    // Use a simple hex encoding for now (Base64 encoding can be complex with Kotlin/Native)
    return this.joinToString("") { byte -> 
        val unsigned = byte.toInt() and 0xFF
        when {
            unsigned < 16 -> "0${unsigned.toString(16)}"
            else -> unsigned.toString(16)
        }
    }
}

private fun String.decodeBase64(): ByteArray {
    // Decode hex string back to ByteArray
    return this.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}