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
        private const val SHARED_SECRETS_FILE_NAME = "shared_secrets.properties"
        private const val DEVICE_KEY_FILE_NAME = "device_private_key.properties"
        private const val DEVICE_PRIVATE_KEY = "device_private_key"
        private const val DEVICE_PUBLIC_KEY = "device_public_key"
    }

    private val trustFile = File(appDir, TRUST_FILE_NAME)
    private val ecdsaFile = File(appDir, ECDSA_FILE_NAME)
    private val sharedSecretsFile = File(appDir, SHARED_SECRETS_FILE_NAME)
    private val deviceKeyFile = File(appDir, DEVICE_KEY_FILE_NAME)
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

            // Remove ECDH-derived shared secret
            val secretsProps = loadProperties(sharedSecretsFile)
            secretsProps.remove(deviceId)
            saveProperties(secretsProps, sharedSecretsFile, "Klardrop Shared Secrets - Do not manually edit this file")
        }
    }

    override suspend fun clearAllTrustedDevices() {
        fileMutex.withLock {
            saveProperties(Properties(), trustFile, "Klardrop Trusted Devices - Do not manually edit this file")
            saveProperties(Properties(), ecdsaFile, "Klardrop ECDSA Keys - Do not manually edit this file")
            saveProperties(Properties(), sharedSecretsFile, "Klardrop Shared Secrets - Do not manually edit this file")
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
    
    // Device Identity Persistence Methods
    
    override suspend fun storeDevicePrivateKey(privateKey: ByteArray) {
        fileMutex.withLock {
            // Preserve any already-stored public key so a separate store call sequence
            // (private then public) doesn't blow away the public half mid-write.
            val props = loadProperties(deviceKeyFile)
            val encodedKey = Base64.getEncoder().encodeToString(privateKey)
            props.setProperty(DEVICE_PRIVATE_KEY, encodedKey)
            saveProperties(props, deviceKeyFile, "Klardrop Device Identity - Do not manually edit this file")
        }
    }

    override suspend fun getDevicePrivateKey(): ByteArray? {
        fileMutex.withLock {
            val props = loadProperties(deviceKeyFile)
            val encodedKey = props.getProperty(DEVICE_PRIVATE_KEY) ?: return null

            return try {
                Base64.getDecoder().decode(encodedKey)
            } catch (e: IllegalArgumentException) {
                props.remove(DEVICE_PRIVATE_KEY)
                saveProperties(props, deviceKeyFile, "Klardrop Device Identity - Do not manually edit this file")
                null
            }
        }
    }

    override suspend fun deleteDevicePrivateKey() {
        fileMutex.withLock {
            if (deviceKeyFile.exists()) {
                deviceKeyFile.delete()
            }
        }
    }

    override suspend fun storeDevicePublicKey(publicKey: ByteArray) {
        fileMutex.withLock {
            val props = loadProperties(deviceKeyFile)
            val encodedKey = Base64.getEncoder().encodeToString(publicKey)
            props.setProperty(DEVICE_PUBLIC_KEY, encodedKey)
            saveProperties(props, deviceKeyFile, "Klardrop Device Identity - Do not manually edit this file")
        }
    }

    override suspend fun getDevicePublicKey(): ByteArray? {
        fileMutex.withLock {
            val props = loadProperties(deviceKeyFile)
            val encodedKey = props.getProperty(DEVICE_PUBLIC_KEY) ?: return null
            return try {
                Base64.getDecoder().decode(encodedKey)
            } catch (e: IllegalArgumentException) {
                props.remove(DEVICE_PUBLIC_KEY)
                saveProperties(props, deviceKeyFile, "Klardrop Device Identity - Do not manually edit this file")
                null
            }
        }
    }

    override suspend fun storeSharedSecret(deviceId: String, sharedSecret: ByteArray) {
        fileMutex.withLock {
            val props = loadProperties(sharedSecretsFile)
            props.setProperty(deviceId, Base64.getEncoder().encodeToString(sharedSecret))
            saveProperties(props, sharedSecretsFile, "Klardrop Shared Secrets - Do not manually edit this file")
        }
    }

    override suspend fun getSharedSecret(deviceId: String): ByteArray? {
        fileMutex.withLock {
            val props = loadProperties(sharedSecretsFile)
            val encoded = props.getProperty(deviceId) ?: return null
            return try {
                Base64.getDecoder().decode(encoded)
            } catch (e: IllegalArgumentException) {
                props.remove(deviceId)
                saveProperties(props, sharedSecretsFile, "Klardrop Shared Secrets - Do not manually edit this file")
                null
            }
        }
    }
}