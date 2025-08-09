package com.carlom.klardrop.common.trust.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidSecureKeyStorage(private val context: Context) : SecureKeyStorage {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ALIAS_PREFIX = "klardrop_trust_"
        private const val AUTH_TAG_LENGTH = 128
        private const val ENCRYPTED_PREFS_FILE = "klardrop_trust_keys"
        private const val IV_SUFFIX = "_iv"
        private const val DATA_SUFFIX = "_data"
    }
    
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }
    
    private val encryptedSharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    override suspend fun storePrivateKey(alias: String, key: ByteArray) = withContext(Dispatchers.IO) {
        // First, generate or get the encryption key
        val secretKey = getOrCreateSecretKey(alias)
        
        // Encrypt the private key
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        
        val iv = cipher.iv
        val encryptedKey = cipher.doFinal(key)
        
        // Store encrypted key with IV in SharedPreferences or DataStore
        // For now, we'll use the key alias as a reference
        // In production, you'd store the encrypted data in SharedPreferences
        storeEncryptedData(alias, iv, encryptedKey)
    }
    
    override suspend fun retrievePrivateKey(alias: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val secretKey = keyStore.getKey(KEY_ALIAS_PREFIX + alias, null) as? SecretKey
                ?: return@withContext null
            
            // Retrieve encrypted data (in production from SharedPreferences)
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
    
    private fun storeEncryptedData(alias: String, iv: ByteArray, encryptedKey: ByteArray) {
        try {
            val editor = encryptedSharedPreferences.edit()
            editor.putString(alias + IV_SUFFIX, Base64.encodeToString(iv, Base64.NO_WRAP))
            editor.putString(alias + DATA_SUFFIX, Base64.encodeToString(encryptedKey, Base64.NO_WRAP))
            editor.apply()
        } catch (e: Exception) {
            throw SecurityException("Failed to store encrypted data for alias: $alias", e)
        }
    }
    
    private fun getEncryptedData(alias: String): Pair<ByteArray, ByteArray>? {
        return try {
            val ivString = encryptedSharedPreferences.getString(alias + IV_SUFFIX, null)
            val dataString = encryptedSharedPreferences.getString(alias + DATA_SUFFIX, null)
            
            if (ivString != null && dataString != null) {
                val iv = Base64.decode(ivString, Base64.NO_WRAP)
                val encryptedKey = Base64.decode(dataString, Base64.NO_WRAP)
                iv to encryptedKey
            } else {
                null
            }
        } catch (e: Exception) {
            null // Return null if data is corrupted or cannot be decoded
        }
    }
    
    private fun deleteEncryptedData(alias: String) {
        try {
            val editor = encryptedSharedPreferences.edit()
            editor.remove(alias + IV_SUFFIX)
            editor.remove(alias + DATA_SUFFIX)
            editor.apply()
        } catch (e: Exception) {
            // Log error but don't throw - deletion should be best effort
        }
    }
    
    private fun clearAllEncryptedData() {
        try {
            val editor = encryptedSharedPreferences.edit()
            editor.clear()
            editor.apply()
        } catch (e: Exception) {
            // Log error but don't throw - clearing should be best effort
        }
    }
}

class SecurityException(message: String, cause: Throwable? = null) : Exception(message, cause)

actual class SecureKeyStorageFactoryImpl(private val context: Context): SecureKeyStorageFactory {
    actual override fun create(): SecureKeyStorage = AndroidSecureKeyStorage(context)
}