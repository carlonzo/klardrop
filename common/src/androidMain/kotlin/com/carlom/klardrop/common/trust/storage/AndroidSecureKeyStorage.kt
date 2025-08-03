package com.carlom.klardrop.common.trust.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.carlom.klardrop.common.database.AppDatabase
import com.carlom.klardrop.common.utils.Clock
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidSecureKeyStorage(private val database: AppDatabase) : SecureKeyStorage {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ALIAS_PREFIX = "klardrop_trust_"
        private const val AUTH_TAG_LENGTH = 128
    }
    
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }
    
    override suspend fun storePrivateKey(alias: String, key: ByteArray) = withContext(Dispatchers.IO) {
        // First, generate or get the encryption key
        val secretKey = getOrCreateSecretKey(alias)
        
        // Encrypt the private key
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        
        val iv = cipher.iv
        val encryptedKey = cipher.doFinal(key)
        
        // Store encrypted key with IV in database
        storeEncryptedData(alias, iv, encryptedKey)
    }
    
    override suspend fun retrievePrivateKey(alias: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val secretKey = keyStore.getKey(KEY_ALIAS_PREFIX + alias, null) as? SecretKey
                ?: return@withContext null
            
            // Retrieve encrypted data from database
            val (iv, encryptedKey) = getEncryptedData(alias) ?: return@withContext null
            
            // Decrypt the private key
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(AUTH_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            
            cipher.doFinal(encryptedKey)
        } catch (e: Exception) {
            null
        }
    }
    
    override suspend fun deletePrivateKey(alias: String) = withContext(Dispatchers.IO) {
        keyStore.deleteEntry(KEY_ALIAS_PREFIX + alias)
        deleteEncryptedData(alias)
    }
    
    override suspend fun keyExists(alias: String): Boolean = withContext(Dispatchers.IO) {
        keyStore.containsAlias(KEY_ALIAS_PREFIX + alias)
    }
    
    override suspend fun clearAll() = withContext(Dispatchers.IO) {
        keyStore.aliases().toList()
            .filter { it.startsWith(KEY_ALIAS_PREFIX) }
            .forEach { keyStore.deleteEntry(it) }
        clearAllEncryptedData()
    }
    
    private fun getOrCreateSecretKey(alias: String): SecretKey {
        val keyAlias = KEY_ALIAS_PREFIX + alias
        
        return if (keyStore.containsAlias(keyAlias)) {
            keyStore.getKey(keyAlias, null) as SecretKey
        } else {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
                .build()
            
            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        }
    }
    
    private suspend fun storeEncryptedData(alias: String, iv: ByteArray, encryptedKey: ByteArray) {
        withContext(Dispatchers.IO) {
            database.encryptedKeyQueries.storeEncryptedKey(
                alias = alias,
                iv = iv,
                encrypted_data = encryptedKey,
                created_at = Clock().currentTimeMillis()
            )
        }
    }
    
    private suspend fun getEncryptedData(alias: String): Pair<ByteArray, ByteArray>? {
        return withContext(Dispatchers.IO) {
            database.encryptedKeyQueries.getEncryptedKey(alias).executeAsOneOrNull()?.let { row ->
                row.iv to row.encrypted_data
            }
        }
    }
    
    private suspend fun deleteEncryptedData(alias: String) {
        withContext(Dispatchers.IO) {
            database.encryptedKeyQueries.deleteEncryptedKey(alias)
        }
    }
    
    private suspend fun clearAllEncryptedData() {
        withContext(Dispatchers.IO) {
            database.encryptedKeyQueries.clearAllEncryptedKeys()
        }
    }
}

actual class SecureKeyStorageFactory(private val context: Context) {
    // Keep the database reference to pass to storage
    private var database: AppDatabase? = null
    
    actual fun create(): SecureKeyStorage {
        return database?.let { AndroidSecureKeyStorage(it) }
            ?: throw IllegalStateException("Database not set. Call setDatabase() first.")
    }
    
    fun setDatabase(database: AppDatabase) {
        this.database = database
    }
}