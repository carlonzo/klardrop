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
    
    // Device Identity Persistence Methods
    
    /**
     * Store this device's own identity private key securely.
     * The corresponding public key can be derived from the private key.
     * This key persists across app restarts to maintain device identity.
     * 
     * @param privateKey The raw bytes of the device's ECDSA private key
     */
    suspend fun storeDevicePrivateKey(privateKey: ByteArray)
    
    /**
     * Retrieve this device's own identity private key.
     * Used to maintain consistent device identity across app restarts.
     * 
     * @return The raw bytes of the device's ECDSA private key, or null if none exists
     */
    suspend fun getDevicePrivateKey(): ByteArray?
    
    /**
     * Delete this device's identity private key.
     * Used for security cleanup or device reset functionality.
     * After calling this, the device will generate a new identity on next startup.
     */
    suspend fun deleteDevicePrivateKey()

    /**
     * Store this device's own identity ECDSA public key alongside the private key.
     *
     * The cryptography library used here only persists private keys in RAW format (the
     * scalar), which is not enough to reconstruct the public key on app restart. We
     * therefore persist both halves explicitly so signature verification against the
     * key the peer cached at pairing time keeps working across restarts. Default impl
     * is a no-op so test fakes that pre-date this method don't break compile; real
     * platform stores override it.
     */
    suspend fun storeDevicePublicKey(publicKey: ByteArray) {}

    /**
     * Retrieve this device's own identity ECDSA public key, if it has been persisted
     * alongside the private key. Returns null on legacy installs that only stored the
     * private key — TrustManager treats null as "no usable persisted identity" and
     * generates a fresh one.
     */
    suspend fun getDevicePublicKey(): ByteArray? = null

    /**
     * Persist the ECDH shared secret derived during pairing with [deviceId]. Both peers
     * arrive at the same 32-byte secret without it ever appearing on the wire (only the
     * ECDH public keys are exchanged). The receive-path uses this secret as the input to
     * an HKDF that produces an HMAC key for fast per-chunk integrity checks on file
     * transfers — much cheaper than per-chunk ECDSA, since both halves of the pair share
     * the symmetric key. Default is no-op for forward compat with older test fakes.
     */
    suspend fun storeSharedSecret(deviceId: String, sharedSecret: ByteArray) {}

    /**
     * Retrieve the ECDH shared secret previously stored for [deviceId]. Null on legacy
     * pairings that pre-date this field — callers fall back to per-frame ECDSA signing.
     */
    suspend fun getSharedSecret(deviceId: String): ByteArray? = null
}