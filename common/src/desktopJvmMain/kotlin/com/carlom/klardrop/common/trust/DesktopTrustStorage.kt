package com.carlom.klardrop.common.trust

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import java.util.Base64

/**
 * Desktop implementation of TrustStorage using Properties file.
 * Keys are stored as Base64-encoded strings in a properties file.
 */
class DesktopTrustStorage(
    private val appDir: File
) : TrustStorage {
    
    companion object {
        private const val TRUST_FILE_NAME = "trusted_devices.properties"
    }
    
    private val trustFile = File(appDir, TRUST_FILE_NAME)
    private val fileMutex = Mutex() // Prevent concurrent file access
    
    init {
        // Ensure app directory exists
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
    }
    
    private suspend fun loadProperties(): Properties = withContext(Dispatchers.IO) {
        val props = Properties()
        if (trustFile.exists()) {
            try {
                trustFile.inputStream().use { input ->
                    props.load(input)
                }
            } catch (e: Exception) {
                // If file is corrupted, start with empty properties
            }
        }
        props
    }
    
    private suspend fun saveProperties(props: Properties) = withContext(Dispatchers.IO) {
        try {
            trustFile.outputStream().use { output ->
                props.store(output, "Klardrop Trusted Devices - Do not manually edit this file")
            }
        } catch (e: Exception) {
            // Handle file write errors gracefully
            throw RuntimeException("Failed to save trusted devices: ${e.message}", e)
        }
    }
    
    override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {
        fileMutex.withLock {
            val props = loadProperties()
            val encodedKey = Base64.getEncoder().encodeToString(publicKey)
            props.setProperty(deviceId, encodedKey)
            saveProperties(props)
        }
    }
    
    override suspend fun getTrustedDeviceKey(deviceId: String): ByteArray? {
        fileMutex.withLock {
            val props = loadProperties()
            val encodedKey = props.getProperty(deviceId) ?: return null
            
            return try {
                Base64.getDecoder().decode(encodedKey)
            } catch (e: IllegalArgumentException) {
                // Invalid Base64 encoding - remove corrupted entry
                props.remove(deviceId)
                saveProperties(props)
                null
            }
        }
    }
    
    override suspend fun getAllTrustedDevices(): Map<String, ByteArray> {
        fileMutex.withLock {
            val props = loadProperties()
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
            val props = loadProperties()
            props.remove(deviceId)
            saveProperties(props)
        }
    }
    
    override suspend fun clearAllTrustedDevices() {
        fileMutex.withLock {
            val props = Properties()
            saveProperties(props)
        }
    }
}