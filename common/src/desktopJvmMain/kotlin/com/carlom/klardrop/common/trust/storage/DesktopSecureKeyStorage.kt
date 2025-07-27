package com.carlom.klardrop.common.trust.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

actual class PlatformSecureKeyStorage : SecureKeyStorage {
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
        Unit
    }
    
    private fun loadOrCreateMasterKey(): ByteArray {
        val masterKeyFile = File(STORAGE_DIR, MASTER_KEY_FILE)
        
        return if (masterKeyFile.exists()) {
            // Load existing master key
            masterKeyFile.readBytes()
        } else {
            // Generate new master key
            val random = SecureRandom()
            val key = ByteArray(32)
            random.nextBytes(key)
            
            // Store it
            masterKeyFile.writeBytes(key)
            
            // Set file permissions (Unix-like systems)
            try {
                masterKeyFile.setReadable(false, false)
                masterKeyFile.setReadable(true, true)
                masterKeyFile.setWritable(false, false)
                masterKeyFile.setWritable(true, true)
            } catch (e: Exception) {
                // Ignore on systems that don't support these operations
            }
            
            key
        }
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

