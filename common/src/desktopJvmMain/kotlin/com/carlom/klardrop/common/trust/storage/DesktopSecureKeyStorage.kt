package com.carlom.klardrop.common.trust.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.NetworkInterface
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DesktopSecureKeyStorage : SecureKeyStorage {
    companion object {
        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val ITERATIONS = 100000
        private const val KEY_LENGTH = 256
        private const val SALT_LENGTH = 16
        private const val IV_LENGTH = 16
        
        private val STORAGE_DIR = File(System.getProperty("user.home"), ".klardrop/keys")
        private const val MASTER_KEY_FILE = "master.key"
    }
    
    private val masterKey: ByteArray by lazy {
        loadOrCreateMasterKey()
    }
    
    init {
        STORAGE_DIR.mkdirs()
    }
    
    override suspend fun storePrivateKey(alias: String, key: ByteArray) = withContext(Dispatchers.IO) {
        val file = File(STORAGE_DIR, "$alias.key")
        val encryptedData = encrypt(key, masterKey)
        file.writeBytes(encryptedData)
    }
    
    override suspend fun retrievePrivateKey(alias: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(STORAGE_DIR, "$alias.key")
        if (!file.exists()) return@withContext null
        
        try {
            val encryptedData = file.readBytes()
            decrypt(encryptedData, masterKey)
        } catch (e: Exception) {
            null
        }
    }
    
    override suspend fun deletePrivateKey(alias: String) {
        withContext(Dispatchers.IO) {
            val file = File(STORAGE_DIR, "$alias.key")
            file.delete()
        }
    }
    
    override suspend fun keyExists(alias: String): Boolean = withContext(Dispatchers.IO) {
        File(STORAGE_DIR, "$alias.key").exists()
    }
    
    override suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            STORAGE_DIR.listFiles()?.forEach { file ->
                if (file.name != MASTER_KEY_FILE) {
                    file.delete()
                }
            }
        }
    }
    
    private fun loadOrCreateMasterKey(): ByteArray {
        val saltFile = File(STORAGE_DIR, "salt.dat")
        val legacyMasterKeyFile = File(STORAGE_DIR, MASTER_KEY_FILE)
        
        // Check for legacy master key file and migrate if needed
        if (legacyMasterKeyFile.exists() && !saltFile.exists()) {
            // Migration: generate salt and delete the plaintext master key
            val salt = generateSalt()
            storeSalt(saltFile, salt)
            legacyMasterKeyFile.delete()
        }
        
        val salt = if (saltFile.exists()) {
            loadSalt(saltFile)
        } else {
            val newSalt = generateSalt()
            storeSalt(saltFile, newSalt)
            newSalt
        }
        
        return deriveMasterKey(salt)
    }
    
    private fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return salt
    }
    
    private fun storeSalt(saltFile: File, salt: ByteArray) {
        saltFile.writeBytes(salt)
        
        // Set file permissions (Unix-like systems)
        try {
            saltFile.setReadable(false, false)
            saltFile.setReadable(true, true)
            saltFile.setWritable(false, false)
            saltFile.setWritable(true, true)
        } catch (e: Exception) {
            // Ignore on systems that don't support these operations
        }
    }
    
    private fun loadSalt(saltFile: File): ByteArray {
        return saltFile.readBytes()
    }
    
    private fun deriveMasterKey(salt: ByteArray): ByteArray {
        // Create a reproducible but hard-to-guess passphrase from system properties
        val systemFingerprint = buildSystemFingerprint()
        
        val keySpec = PBEKeySpec(
            systemFingerprint.toCharArray(),
            salt,
            ITERATIONS,
            KEY_LENGTH
        )
        
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val secretKey = factory.generateSecret(keySpec)
        
        // Clear the passphrase from memory
        keySpec.clearPassword()
        
        return secretKey.encoded
    }
    
    private fun buildSystemFingerprint(): String {
        // Build a system fingerprint from various system properties
        val components = mutableListOf<String>()
        
        // User and system identifiers
        System.getProperty("user.name")?.let { components.add("user:$it") }
        System.getProperty("user.home")?.let { components.add("home:${it.hashCode()}") }
        System.getProperty("os.name")?.let { components.add("os:$it") }
        System.getProperty("os.version")?.let { components.add("osver:$it") }
        System.getProperty("os.arch")?.let { components.add("arch:$it") }
        
        // Add hardware identifier if available
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val ni = networkInterfaces.nextElement()
                ni.hardwareAddress?.let { hwAddr ->
                    if (hwAddr.isNotEmpty() && !isLocalLoopbackOrVirtual(ni)) {
                        components.add("hw:${hwAddr.joinToString("") { "%02x".format(it) }}")
                        break // Use only the first valid hardware address
                    }
                }
            }
        } catch (e: Exception) {
            // Fall back if hardware address is not accessible
        }
        
        // If we couldn't get enough entropy, add Java runtime info
        if (components.size < 3) {
            System.getProperty("java.version")?.let { components.add("java:$it") }
            System.getProperty("java.vendor")?.let { components.add("vendor:$it") }
        }
        
        // Join all components with a delimiter
        return components.joinToString("|")
    }
    
    private fun isLocalLoopbackOrVirtual(ni: NetworkInterface): Boolean {
        return ni.isLoopback || ni.isVirtual || ni.name.startsWith("lo") || 
               ni.name.startsWith("vbox") || ni.name.startsWith("vmnet")
    }
    
    private fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = SecretKeySpec(key, ALGORITHM)
        
        // Generate random IV
        val iv = ByteArray(IV_LENGTH)
        SecureRandom().nextBytes(iv)
        
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        val encrypted = cipher.doFinal(data)
        
        // Prepend IV to encrypted data
        return iv + encrypted
    }
    
    private fun decrypt(encryptedData: ByteArray, key: ByteArray): ByteArray {
        if (encryptedData.size < IV_LENGTH) {
            throw IllegalArgumentException("Invalid encrypted data")
        }
        
        // Extract IV
        val iv = encryptedData.sliceArray(0 until IV_LENGTH)
        val ciphertext = encryptedData.sliceArray(IV_LENGTH until encryptedData.size)
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = SecretKeySpec(key, ALGORITHM)
        
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }
}

actual class SecureKeyStorageFactoryImpl: SecureKeyStorageFactory {
    actual override fun create(): SecureKeyStorage = DesktopSecureKeyStorage()
}