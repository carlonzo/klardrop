package com.carlom.klardrop.common.trust

/**
 * Interface for storing and retrieving trusted device public keys.
 * Platform-specific implementations handle secure storage (SharedPreferences, Keychain, etc.).
 * 
 * Stores both ECDH keys (for key exchange) and ECDSA keys (for message signing).
 */
interface TrustStorage {
    
    /**
     * Store a trusted device's ECDH public key.
     * @param deviceId Unique device identifier
     * @param publicKey ECDH public key for key exchange
     */
    suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray)
    
    /**
     * Store a trusted device's ECDSA public key for message signing.
     * @param deviceId Unique device identifier
     * @param ecdsaPublicKey ECDSA public key for signature verification
     */
    suspend fun storeECDSAKey(deviceId: String, ecdsaPublicKey: ByteArray)
    
    /**
     * Retrieve a trusted device's ECDH public key.
     * @param deviceId Device identifier
     * @return ECDH public key bytes, or null if device is not trusted
     */
    suspend fun getTrustedDeviceKey(deviceId: String): ByteArray?
    
    /**
     * Retrieve a trusted device's ECDSA public key for signature verification.
     * @param deviceId Device identifier
     * @return ECDSA public key bytes, or null if not stored
     */
    suspend fun getECDSAKey(deviceId: String): ByteArray?
    
    /**
     * Get all trusted devices and their public keys.
     * @return Map of device ID to public key
     */
    suspend fun getAllTrustedDevices(): Map<String, ByteArray>
    
    /**
     * Remove a device from the trusted list.
     * @param deviceId Device to untrust
     */
    suspend fun removeTrustedDevice(deviceId: String)
    
    /**
     * Clear all trusted devices.
     * Used for reset functionality or security cleanup.
     */
    suspend fun clearAllTrustedDevices()
    
    /**
     * Check if a device is trusted.
     * @param deviceId Device identifier
     * @return true if device is in trusted list
     */
    suspend fun isTrusted(deviceId: String): Boolean {
        return getTrustedDeviceKey(deviceId) != null
    }
}