package com.carlom.klardrop.common.trust

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*

/**
 * iOS implementation of TrustStorage using Keychain Services.
 * Keys are stored securely in the iOS Keychain.
 */
class IosTrustStorage : TrustStorage {
    
    companion object {
        private const val SERVICE_NAME = "klardrop_trust_keys"
    }
    
    override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {
        val query = CFDictionaryCreateMutable(null, 0, null, null)
        
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, CFStringCreateWithCString(null, SERVICE_NAME, kCFStringEncodingUTF8))
        CFDictionarySetValue(query, kSecAttrAccount, CFStringCreateWithCString(null, deviceId, kCFStringEncodingUTF8))
        
        val data = publicKey.usePinned { pinned ->
            CFDataCreate(null, pinned.addressOf(0).reinterpret(), publicKey.size.convert())
        }
        CFDictionarySetValue(query, kSecValueData, data)
        
        // Try to add the item
        val addResult = SecItemAdd(query, null)
        
        if (addResult == errSecDuplicateItem) {
            // Item exists, update it
            val updateQuery = CFDictionaryCreateMutable(null, 0, null, null)
            CFDictionarySetValue(updateQuery, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(updateQuery, kSecAttrService, CFStringCreateWithCString(null, SERVICE_NAME, kCFStringEncodingUTF8))
            CFDictionarySetValue(updateQuery, kSecAttrAccount, CFStringCreateWithCString(null, deviceId, kCFStringEncodingUTF8))
            
            val updateAttributes = CFDictionaryCreateMutable(null, 0, null, null)
            CFDictionarySetValue(updateAttributes, kSecValueData, data)
            
            SecItemUpdate(updateQuery, updateAttributes)
            CFRelease(updateQuery)
            CFRelease(updateAttributes)
        }
        
        CFRelease(query)
        CFRelease(data)
    }
    
    override suspend fun getTrustedDeviceKey(deviceId: String): ByteArray? {
        val query = CFDictionaryCreateMutable(null, 0, null, null)
        
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, CFStringCreateWithCString(null, SERVICE_NAME, kCFStringEncodingUTF8))
        CFDictionarySetValue(query, kSecAttrAccount, CFStringCreateWithCString(null, deviceId, kCFStringEncodingUTF8))
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            
            CFRelease(query)
            
            if (status == errSecSuccess) {
                val data = result.value?.reinterpret<CFDataRef>()
                if (data != null) {
                    val length = CFDataGetLength(data).toInt()
                    val bytes = CFDataGetBytePtr(data)
                    val byteArray = ByteArray(length) { i ->
                        bytes!![i]
                    }
                    CFRelease(data)
                    return byteArray
                }
            }
            
            return null
        }
    }
    
    override suspend fun getAllTrustedDevices(): Map<String, ByteArray> {
        val query = CFDictionaryCreateMutable(null, 0, null, null)
        
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, CFStringCreateWithCString(null, SERVICE_NAME, kCFStringEncodingUTF8))
        CFDictionarySetValue(query, kSecReturnAttributes, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitAll)
        
        val result = mutableMapOf<String, ByteArray>()
        
        memScoped {
            val queryResult = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, queryResult.ptr)
            
            CFRelease(query)
            
            if (status == errSecSuccess) {
                val array = queryResult.value?.reinterpret<CFArrayRef>()
                if (array != null) {
                    val count = CFArrayGetCount(array)
                    for (i in 0 until count) {
                        val item = CFArrayGetValueAtIndex(array, i.convert())?.reinterpret<CFDictionaryRef>()
                        if (item != null) {
                            // Extract account (device ID)
                            val account = CFDictionaryGetValue(item, kSecAttrAccount)?.reinterpret<CFStringRef>()
                            val accountStr = account?.let { 
                                CFStringGetCStringPtr(it, kCFStringEncodingUTF8)?.toKString()
                            }
                            
                            // Extract data (public key)
                            val data = CFDictionaryGetValue(item, kSecValueData)?.reinterpret<CFDataRef>()
                            val publicKey = data?.let { dataRef ->
                                val length = CFDataGetLength(dataRef).toInt()
                                val bytes = CFDataGetBytePtr(dataRef)
                                ByteArray(length) { idx -> bytes!![idx] }
                            }
                            
                            if (accountStr != null && publicKey != null) {
                                result[accountStr] = publicKey
                            }
                        }
                    }
                    CFRelease(array)
                }
            }
        }
        
        return result
    }
    
    override suspend fun removeTrustedDevice(deviceId: String) {
        val query = CFDictionaryCreateMutable(null, 0, null, null)
        
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, CFStringCreateWithCString(null, SERVICE_NAME, kCFStringEncodingUTF8))
        CFDictionarySetValue(query, kSecAttrAccount, CFStringCreateWithCString(null, deviceId, kCFStringEncodingUTF8))
        
        SecItemDelete(query)
        CFRelease(query)
    }
    
    override suspend fun clearAllTrustedDevices() {
        val query = CFDictionaryCreateMutable(null, 0, null, null)
        
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, CFStringCreateWithCString(null, SERVICE_NAME, kCFStringEncodingUTF8))
        
        SecItemDelete(query)
        CFRelease(query)
    }
}