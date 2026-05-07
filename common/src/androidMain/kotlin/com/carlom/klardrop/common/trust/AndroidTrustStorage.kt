package com.carlom.klardrop.common.trust

import android.content.Context
import android.util.Base64
import androidx.core.content.edit

/**
 * Android implementation of TrustStorage using SharedPreferences.
 * Keys are stored as Base64-encoded strings in encrypted SharedPreferences.
 * 
 * Stores both ECDH keys (for key exchange) and ECDSA keys (for message signing).
 */
class AndroidTrustStorage(
    context: Context
) : TrustStorage {
    
    companion object {
        private const val TRUST_PREFS = "trust_keys"
        private const val KEY_PREFIX = "trusted_device_"
        private const val ECDSA_KEY_PREFIX = "ecdsa_key_"
        private const val DEVICE_PRIVATE_KEY = "device_private_key"
        private const val DEVICE_PUBLIC_KEY = "device_public_key"
        private const val SHARED_SECRET_PREFIX = "shared_secret_"
    }
    
    private val sharedPrefs = context.getSharedPreferences(TRUST_PREFS, Context.MODE_PRIVATE)
    
    override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {
        val encodedKey = Base64.encodeToString(publicKey, Base64.NO_WRAP)
        sharedPrefs.edit {
          putString(KEY_PREFIX + deviceId, encodedKey)
        }
    }
    
    override suspend fun getTrustedDeviceKey(deviceId: String): ByteArray? {
        val encodedKey = sharedPrefs.getString(KEY_PREFIX + deviceId, null)
            ?: return null
        
        return try {
            Base64.decode(encodedKey, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            // Invalid Base64 encoding - remove corrupted entry
            sharedPrefs.edit { remove(KEY_PREFIX + deviceId) }
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
        sharedPrefs.edit {
            remove(KEY_PREFIX + deviceId)
                .remove(ECDSA_KEY_PREFIX + deviceId)
                .remove(SHARED_SECRET_PREFIX + deviceId)
        }
    }

    override suspend fun clearAllTrustedDevices() {
        sharedPrefs.edit {
            val allKeys = sharedPrefs.all.keys

            // Remove all entries that start with our prefixes
            for (key in allKeys) {
                if (key.startsWith(KEY_PREFIX) ||
                    key.startsWith(ECDSA_KEY_PREFIX) ||
                    key.startsWith(SHARED_SECRET_PREFIX)
                ) {
                    remove(key)
                }
            }
        }
    }
    
    override suspend fun storeECDSAKey(deviceId: String, ecdsaPublicKey: ByteArray) {
        val encodedKey = Base64.encodeToString(ecdsaPublicKey, Base64.NO_WRAP)
        sharedPrefs.edit {
          putString(ECDSA_KEY_PREFIX + deviceId, encodedKey)
        }
    }
    
    override suspend fun getECDSAKey(deviceId: String): ByteArray? {
        val encodedKey = sharedPrefs.getString(ECDSA_KEY_PREFIX + deviceId, null)
            ?: return null
        
        return try {
            Base64.decode(encodedKey, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            // Invalid Base64 encoding - remove corrupted entry
            sharedPrefs.edit { remove(ECDSA_KEY_PREFIX + deviceId) }
            null
        }
    }
    
    // Device Identity Persistence Methods
    
    override suspend fun storeDevicePrivateKey(privateKey: ByteArray) {
        val encodedKey = Base64.encodeToString(privateKey, Base64.NO_WRAP)
        sharedPrefs.edit {
            putString(DEVICE_PRIVATE_KEY, encodedKey)
        }
    }
    
    override suspend fun getDevicePrivateKey(): ByteArray? {
        val encodedKey = sharedPrefs.getString(DEVICE_PRIVATE_KEY, null)
            ?: return null
        
        return try {
            Base64.decode(encodedKey, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            // Invalid Base64 encoding - remove corrupted entry
            sharedPrefs.edit { remove(DEVICE_PRIVATE_KEY) }
            null
        }
    }
    
    override suspend fun deleteDevicePrivateKey() {
        sharedPrefs.edit {
            remove(DEVICE_PRIVATE_KEY)
            remove(DEVICE_PUBLIC_KEY)
        }
    }

    override suspend fun storeDevicePublicKey(publicKey: ByteArray) {
        val encodedKey = Base64.encodeToString(publicKey, Base64.NO_WRAP)
        sharedPrefs.edit {
            putString(DEVICE_PUBLIC_KEY, encodedKey)
        }
    }

    override suspend fun getDevicePublicKey(): ByteArray? {
        val encodedKey = sharedPrefs.getString(DEVICE_PUBLIC_KEY, null) ?: return null
        return try {
            Base64.decode(encodedKey, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            sharedPrefs.edit { remove(DEVICE_PUBLIC_KEY) }
            null
        }
    }

    override suspend fun storeSharedSecret(deviceId: String, sharedSecret: ByteArray) {
        val encoded = Base64.encodeToString(sharedSecret, Base64.NO_WRAP)
        sharedPrefs.edit {
            putString(SHARED_SECRET_PREFIX + deviceId, encoded)
        }
    }

    override suspend fun getSharedSecret(deviceId: String): ByteArray? {
        val encoded = sharedPrefs.getString(SHARED_SECRET_PREFIX + deviceId, null) ?: return null
        return try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            sharedPrefs.edit { remove(SHARED_SECRET_PREFIX + deviceId) }
            null
        }
    }
}