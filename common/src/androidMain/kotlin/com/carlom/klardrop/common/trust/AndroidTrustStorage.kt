package com.carlom.klardrop.common.trust

import android.content.Context
import android.util.Base64

/**
 * Android implementation of TrustStorage using SharedPreferences.
 * Keys are stored as Base64-encoded strings in encrypted SharedPreferences.
 * 
 * Stores both ECDH keys (for key exchange) and ECDSA keys (for message signing).
 */
class AndroidTrustStorage(
    private val context: Context
) : TrustStorage {
    
    companion object {
        private const val TRUST_PREFS = "trust_keys"
        private const val KEY_PREFIX = "trusted_device_"
        private const val ECDSA_KEY_PREFIX = "ecdsa_key_"
    }
    
    private val sharedPrefs = context.getSharedPreferences(TRUST_PREFS, Context.MODE_PRIVATE)
    
    override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {
        val encodedKey = Base64.encodeToString(publicKey, Base64.NO_WRAP)
        sharedPrefs.edit()
            .putString(KEY_PREFIX + deviceId, encodedKey)
            .apply()
    }
    
    override suspend fun getTrustedDeviceKey(deviceId: String): ByteArray? {
        val encodedKey = sharedPrefs.getString(KEY_PREFIX + deviceId, null)
            ?: return null
        
        return try {
            Base64.decode(encodedKey, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            // Invalid Base64 encoding - remove corrupted entry
            sharedPrefs.edit().remove(KEY_PREFIX + deviceId).apply()
            null
        }
    }
    
    override suspend fun getAllTrustedDevices(): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        val allEntries = sharedPrefs.all
        
        for ((key, value) in allEntries) {
            if (key.startsWith(KEY_PREFIX) && value is String) {
                val deviceId = key.removePrefix(KEY_PREFIX)
                try {
                    val publicKey = Base64.decode(value, Base64.NO_WRAP)
                    result[deviceId] = publicKey
                } catch (e: IllegalArgumentException) {
                    // Skip corrupted entries
                    continue
                }
            }
        }
        
        return result
    }
    
    override suspend fun removeTrustedDevice(deviceId: String) {
        sharedPrefs.edit()
            .remove(KEY_PREFIX + deviceId)
            .remove(ECDSA_KEY_PREFIX + deviceId)
            .apply()
    }
    
    override suspend fun clearAllTrustedDevices() {
        val editor = sharedPrefs.edit()
        val allKeys = sharedPrefs.all.keys
        
        // Remove all entries that start with our prefixes
        for (key in allKeys) {
            if (key.startsWith(KEY_PREFIX) || key.startsWith(ECDSA_KEY_PREFIX)) {
                editor.remove(key)
            }
        }
        
        editor.apply()
    }
    
    override suspend fun storeECDSAKey(deviceId: String, ecdsaPublicKey: ByteArray) {
        val encodedKey = Base64.encodeToString(ecdsaPublicKey, Base64.NO_WRAP)
        sharedPrefs.edit()
            .putString(ECDSA_KEY_PREFIX + deviceId, encodedKey)
            .apply()
    }
    
    override suspend fun getECDSAKey(deviceId: String): ByteArray? {
        val encodedKey = sharedPrefs.getString(ECDSA_KEY_PREFIX + deviceId, null)
            ?: return null
        
        return try {
            Base64.decode(encodedKey, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            // Invalid Base64 encoding - remove corrupted entry
            sharedPrefs.edit().remove(ECDSA_KEY_PREFIX + deviceId).apply()
            null
        }
    }
}