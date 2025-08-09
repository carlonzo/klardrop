package com.carlom.klardrop.common.trust

/**
 * Interface for storing and retrieving trusted device public keys.
 * Platform-specific implementations handle secure storage (SharedPreferences, Keychain, etc.).
 */
interface TrustStorage {
    
    /**
     * Store a trusted device's public key.
     * @param deviceId Unique device identifier
     * @param publicKey ECDSA public key for signature verification
     */
    suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray)
    
    /**
     * Retrieve a trusted device's public key.
     * @param deviceId Device identifier
     * @return Public key bytes, or null if device is not trusted
     */
    suspend fun getTrustedDeviceKey(deviceId: String): ByteArray?
    
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