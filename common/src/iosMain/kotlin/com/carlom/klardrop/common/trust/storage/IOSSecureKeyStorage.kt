package com.carlom.klardrop.common.trust.storage

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.Security.*
import platform.CoreFoundation.*
import platform.posix.memcpy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IOSSecureKeyStorage : SecureKeyStorage {
    companion object {
        private const val SERVICE_NAME = "com.klardrop.trust"
        private const val KEY_PREFIX = "trust_key_"
    }
    
    override suspend fun storePrivateKey(alias: String, key: ByteArray) = withContext(Dispatchers.Default) {
        val keyData = key.toNSData()
        val account = KEY_PREFIX + alias
        
        // First, try to delete any existing key
        deletePrivateKey(alias)
        
        val query = createBaseQuery(account)
        query[kSecValueData] = keyData
        
        val status = SecItemAdd(query as CFDictionaryRef, null)
        if (status != errSecSuccess) {
            throw SecurityException("Failed to store key: $status")
        }
    }
    
    override suspend fun retrievePrivateKey(alias: String): ByteArray? = withContext(Dispatchers.Default) {
        val account = KEY_PREFIX + alias
        val query = createBaseQuery(account)
        query[kSecReturnData] = kCFBooleanTrue
        query[kSecMatchLimit] = kSecMatchLimitOne
        
        memScoped {
            val result: CFTypeRefVar = alloc()
            val status = SecItemCopyMatching(query as CFDictionaryRef, result.ptr)
            
            return@withContext when (status) {
                errSecSuccess -> {
                    val data = result.value as? NSData
                    data?.toByteArray()
                }
                errSecItemNotFound -> null
                else -> throw SecurityException("Failed to retrieve key: $status")
            }
        }
    }
    
    override suspend fun deletePrivateKey(alias: String): Unit = withContext(Dispatchers.Default) {
        val account = KEY_PREFIX + alias
        val query = createBaseQuery(account)
        SecItemDelete(query as CFDictionaryRef)
    }
    
    override suspend fun keyExists(alias: String): Boolean = withContext(Dispatchers.Default) {
        retrievePrivateKey(alias) != null
    }
    
    override suspend fun clearAll(): Unit = withContext(Dispatchers.Default) {
        val query = mutableMapOf<Any?, Any?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to SERVICE_NAME
        )
        SecItemDelete(query as CFDictionaryRef)
    }
    
    private fun createBaseQuery(account: String): MutableMap<Any?, Any?> {
        return mutableMapOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to SERVICE_NAME,
            kSecAttrAccount to account,
            kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        )
    }
}

// Extension functions for data conversion
private fun ByteArray.toNSData(): NSData {
    return NSMutableData().apply {
        if (this@toNSData.isNotEmpty()) {
            this@toNSData.usePinned { pinned ->
                appendBytes(pinned.addressOf(0), this@toNSData.size.toULong())
            }
        }
    }
}

private fun NSData.toByteArray(): ByteArray {
    val length = this.length.toInt()
    val bytes = ByteArray(length)
    if (length > 0) {
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), this.bytes, length.toULong())
        }
    }
    return bytes
}

class SecurityException(message: String) : Exception(message)

actual class SecureKeyStorageFactoryImpl: SecureKeyStorageFactory {
    actual override fun create(): SecureKeyStorage = IOSSecureKeyStorage()
}