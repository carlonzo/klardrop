package com.carlom.klardrop.common.trust.storage

interface SecureKeyStorage {
    /**
     * Store a private key securely using platform-specific secure storage
     * @param alias Unique identifier for the key
     * @param key The private key bytes to store
     */
    suspend fun storePrivateKey(alias: String, key: ByteArray)
    
    /**
     * Retrieve a private key from secure storage
     * @param alias Unique identifier for the key
     * @return The private key bytes or null if not found
     */
    suspend fun retrievePrivateKey(alias: String): ByteArray?
    
    /**
     * Delete a private key from secure storage
     * @param alias Unique identifier for the key
     */
    suspend fun deletePrivateKey(alias: String)
    
    /**
     * Check if a key exists in secure storage
     * @param alias Unique identifier for the key
     * @return true if the key exists
     */
    suspend fun keyExists(alias: String): Boolean
    
    /**
     * Clear all stored keys (use with caution)
     */
    suspend fun clearAll()
}

expect class SecureKeyStorageFactory {
    fun create(): SecureKeyStorage
}