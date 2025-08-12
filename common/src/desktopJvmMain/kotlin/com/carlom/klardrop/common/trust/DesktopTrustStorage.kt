package com.carlom.klardrop.common.trust

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import java.util.Base64

/**
 * Desktop implementation of TrustStorage using Properties files.
 * Keys are stored as Base64-encoded strings in properties files.
 * 
 * Stores both ECDH keys (for key exchange) and ECDSA keys (for message signing).
 */
class DesktopTrustStorage(
    private val appDir: File
) : TrustStorage {
    
    companion object {
        private const val TRUST_FILE_NAME = "trusted_devices.properties"
        private const val ECDSA_FILE_NAME = "ecdsa_keys.properties"
    }
    
    private val trustFile = File(appDir, TRUST_FILE_NAME)
    private val ecdsaFile = File(appDir, ECDSA_FILE_NAME)
    private val fileMutex = Mutex() // Prevent concurrent file access
    
    init {
        // Ensure app directory exists
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
    }
    
    private suspend fun loadProperties(file: File): Properties = withContext(Dispatchers.IO) {
        val props = Properties()
        if (file.exists()) {
            try {
                file.inputStream().use { input ->
                    props.load(input)
                }
            } catch (e: Exception) {
                // If file is corrupted, start with empty properties
            }
        }
        props
    }
    
    private suspend fun saveProperties(props: Properties, file: File, comment: String) = withContext(Dispatchers.IO) {
        try {
            file.outputStream().use { output ->
                props.store(output, comment)
            }
        } catch (e: Exception) {
            // Handle file write errors gracefully
            throw RuntimeException("Failed to save properties to ${file.name}: ${e.message}", e)
        }
    }
    
    override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {
        fileMutex.withLock {
            val props = loadProperties(trustFile)
            val encodedKey = Base64.getEncoder().encodeToString(publicKey)
            props.setProperty(deviceId, encodedKey)
            saveProperties(props, trustFile, "Klardrop Trusted Devices - Do not manually edit this file")
        }
    }
    
    override suspend fun getTrustedDeviceKey(deviceId: String): ByteArray? {
        fileMutex.withLock {
            val props = loadProperties(trustFile)
            val encodedKey = props.getProperty(deviceId) ?: return null
            
            return try {
                Base64.getDecoder().decode(encodedKey)
            } catch (e: IllegalArgumentException) {
                // Invalid Base64 encoding - remove corrupted entry
                props.remove(deviceId)
                saveProperties(props, trustFile, "Klardrop Trusted Devices - Do not manually edit this file")
                null
            }
        }
    }
    
    override suspend fun getAllTrustedDevices(): Map<String, ByteArray> {
        fileMutex.withLock {
            val props = loadProperties(trustFile)
            val result = mutableMapOf<String, ByteArray>()
            
            for ((deviceId, encodedKey) in props) {
                if (deviceId is String && encodedKey is String) {
                    try {
                        val publicKey = Base64.getDecoder().decode(encodedKey)
                        result[deviceId] = publicKey
                    } catch (e: IllegalArgumentException) {
                        // Skip corrupted entries
                        continue
                    }
                }
            }
            
            return result
        }
    }
    
    override suspend fun removeTrustedDevice(deviceId: String) {
        fileMutex.withLock {
            // Remove ECDH key
            val ecdhProps = loadProperties(trustFile)
            ecdhProps.remove(deviceId)
            saveProperties(ecdhProps, trustFile, "Klardrop Trusted Devices - Do not manually edit this file")
            
            // Remove ECDSA key
            val ecdsaProps = loadProperties(ecdsaFile)
            ecdsaProps.remove(deviceId)
            saveProperties(ecdsaProps, ecdsaFile, "Klardrop ECDSA Keys - Do not manually edit this file")
        }
    }
    
    override suspend fun clearAllTrustedDevices() {
        fileMutex.withLock {
            // Clear ECDH keys
            val ecdhProps = Properties()
            saveProperties(ecdhProps, trustFile, "Klardrop Trusted Devices - Do not manually edit this file")
            
            // Clear ECDSA keys
            val ecdsaProps = Properties()
            saveProperties(ecdsaProps, ecdsaFile, "Klardrop ECDSA Keys - Do not manually edit this file")
        }
    }
    
    override suspend fun storeECDSAKey(deviceId: String, ecdsaPublicKey: ByteArray) {
        fileMutex.withLock {
            val props = loadProperties(ecdsaFile)
            val encodedKey = Base64.getEncoder().encodeToString(ecdsaPublicKey)
            props.setProperty(deviceId, encodedKey)
            saveProperties(props, ecdsaFile, "Klardrop ECDSA Keys - Do not manually edit this file")
        }
    }
    
    override suspend fun getECDSAKey(deviceId: String): ByteArray? {
        fileMutex.withLock {
            val props = loadProperties(ecdsaFile)
            val encodedKey = props.getProperty(deviceId) ?: return null
            
            return try {
                Base64.getDecoder().decode(encodedKey)
            } catch (e: IllegalArgumentException) {
                // Invalid Base64 encoding - remove corrupted entry
                props.remove(deviceId)
                saveProperties(props, ecdsaFile, "Klardrop ECDSA Keys - Do not manually edit this file")
                null
            }
        }
    }
}