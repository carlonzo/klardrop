package com.carlom.klardrop.common.trust

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.CoreFoundation.CFBridgingRelease
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.numberWithBool
import platform.Foundation.numberWithInt
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyRef
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrLabel
import platform.Security.kSecClass
import platform.Security.kSecClassKey
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecReturnRef
import platform.darwin.OSStatus

/**
 * Apple-platform implementation of [TrustStorage].
 *
 * Peer ECDH/ECDSA public keys are stored in NSUserDefaults (not secret).
 * The device's own ECDSA P-256 private key lives in the Keychain as a
 * permanent, non-exportable item identified by [DEVICE_KEY_TAG]. Signing
 * happens inside the Keychain via [signWithDeviceKey] and the private bytes
 * never leave the secure store.
 *
 * The Keychain item uses `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`,
 * so the key is unavailable before the first unlock after a reboot, never
 * leaves the device, and is not synchronized via iCloud Keychain.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class AppleTrustStorage : TrustStorage {

    companion object {
        private const val TRUST_KEY_PREFIX = "klardrop_trust_"
        private const val ECDSA_KEY_PREFIX = "klardrop_ecdsa_"
        private const val SHARED_SECRET_PREFIX = "klardrop_shared_secret_"
        private const val DEVICE_PUBLIC_KEY = "klardrop_device_public_key"
        private const val DEVICE_KEY_TAG = "com.carlom.klardrop.device-ecdsa-v1"
        private const val DEVICE_KEY_LABEL = "Klardrop Device Identity"
        private const val P256_KEY_SIZE_BITS = 256
    }

    private val mutex = Mutex()
    private val userDefaults = NSUserDefaults.standardUserDefaults
    private val tagData: NSData by lazy { DEVICE_KEY_TAG.encodeToByteArray().toNSData() }

    // ---- Peer trust map (NSUserDefaults — not secret) ----

    override suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray) {
        mutex.withLock {
            userDefaults.setObject(publicKey.toBase64String(), TRUST_KEY_PREFIX + deviceId)
            userDefaults.synchronize()
        }
    }

    override suspend fun getTrustedDeviceKey(deviceId: String): ByteArray? = mutex.withLock {
        readBase64(TRUST_KEY_PREFIX + deviceId)
    }

    override suspend fun getAllTrustedDevices(): Map<String, ByteArray> = mutex.withLock {
        val result = mutableMapOf<String, ByteArray>()
        userDefaults.dictionaryRepresentation().keys.forEach { keyObj ->
            val key = keyObj.toString()
            if (key.startsWith(TRUST_KEY_PREFIX)) {
                userDefaults.stringForKey(key)?.fromBase64OrNull()?.let { decoded ->
                    result[key.removePrefix(TRUST_KEY_PREFIX)] = decoded
                }
            }
        }
        result
    }

    override suspend fun removeTrustedDevice(deviceId: String) {
        mutex.withLock {
            userDefaults.removeObjectForKey(TRUST_KEY_PREFIX + deviceId)
            userDefaults.removeObjectForKey(ECDSA_KEY_PREFIX + deviceId)
            userDefaults.removeObjectForKey(SHARED_SECRET_PREFIX + deviceId)
            userDefaults.synchronize()
        }
    }

    override suspend fun clearAllTrustedDevices() {
        mutex.withLock {
            userDefaults.dictionaryRepresentation().keys.forEach { keyObj ->
                val key = keyObj.toString()
                if (key.startsWith(TRUST_KEY_PREFIX) ||
                    key.startsWith(ECDSA_KEY_PREFIX) ||
                    key.startsWith(SHARED_SECRET_PREFIX)
                ) {
                    userDefaults.removeObjectForKey(key)
                }
            }
            userDefaults.synchronize()
        }
    }

    override suspend fun storeECDSAKey(deviceId: String, ecdsaPublicKey: ByteArray) {
        mutex.withLock {
            userDefaults.setObject(ecdsaPublicKey.toBase64String(), ECDSA_KEY_PREFIX + deviceId)
            userDefaults.synchronize()
        }
    }

    override suspend fun getECDSAKey(deviceId: String): ByteArray? = mutex.withLock {
        readBase64(ECDSA_KEY_PREFIX + deviceId)
    }

    override suspend fun storeSharedSecret(deviceId: String, sharedSecret: ByteArray) {
        mutex.withLock {
            userDefaults.setObject(sharedSecret.toBase64String(), SHARED_SECRET_PREFIX + deviceId)
            userDefaults.synchronize()
        }
    }

    override suspend fun getSharedSecret(deviceId: String): ByteArray? = mutex.withLock {
        readBase64(SHARED_SECRET_PREFIX + deviceId)
    }

    // ---- Device identity (Keychain) ----

    override suspend fun storeDevicePrivateKey(privateKey: ByteArray) {
        // Keychain does not import raw EC private bytes; the device key is
        // generated in-place by ensureDeviceKey. This entry point is reachable
        // only from test paths — make sure the Keychain item exists.
        mutex.withLock {
            val existing = findDeviceKey()
            if (existing == null) {
                generateDeviceKey()?.let { CFRelease(it) }
            } else {
                CFRelease(existing)
            }
        }
    }

    override suspend fun getDevicePrivateKey(): ByteArray? = null

    override suspend fun hasDeviceKey(): Boolean = mutex.withLock {
        val existing = findDeviceKey()
        if (existing != null) {
            CFRelease(existing)
            true
        } else {
            false
        }
    }

    override suspend fun storeDevicePublicKey(publicKey: ByteArray) {
        mutex.withLock {
            userDefaults.setObject(publicKey.toBase64String(), DEVICE_PUBLIC_KEY)
            userDefaults.synchronize()
        }
    }

    override suspend fun getDevicePublicKey(): ByteArray? = mutex.withLock {
        userDefaults.stringForKey(DEVICE_PUBLIC_KEY)?.fromBase64OrNull()?.let { return@withLock it }
        // Cache miss / corrupt entry: rebuild from the Keychain item.
        val privateRef = findDeviceKey() ?: return@withLock null
        try {
            val raw = exportPublicKey(privateRef) ?: return@withLock null
            userDefaults.setObject(raw.toBase64String(), DEVICE_PUBLIC_KEY)
            userDefaults.synchronize()
            raw
        } finally {
            CFRelease(privateRef)
        }
    }

    override suspend fun deleteDevicePrivateKey() {
        mutex.withLock {
            deleteDeviceKey()
            userDefaults.removeObjectForKey(DEVICE_PUBLIC_KEY)
            userDefaults.synchronize()
        }
    }

    override suspend fun signWithDeviceKey(data: ByteArray, crypto: TrustCrypto): ByteArray? = mutex.withLock {
        val privateRef = findDeviceKey() ?: return@withLock null
        try {
            memScoped {
                val errVar = alloc<CFErrorRefVar>()
                val payload = data.toNSData()
                val cfPayload = CFBridgingRetain(payload) as CFDataRef
                val derSignature = try {
                    SecKeyCreateSignature(
                        privateRef,
                        kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
                        cfPayload,
                        errVar.ptr
                    )
                } finally {
                    CFRelease(cfPayload)
                }
                if (derSignature == null) {
                    errVar.value?.let { CFRelease(it) }
                    return@memScoped null
                }
                val derBytes = (CFBridgingRelease(derSignature) as NSData).toByteArray()
                try {
                    EcdsaSignatureFormat.derToRaw(derBytes)
                } catch (_: Exception) {
                    null
                }
            }
        } finally {
            CFRelease(privateRef)
        }
    }

    override suspend fun ensureDeviceKey(crypto: TrustCrypto): TrustCrypto.ECDSAPublicKey = mutex.withLock {
        val privateRef = findDeviceKey()
            ?: generateDeviceKey()
            ?: error("Failed to generate device identity in Apple Keychain")
        try {
            val raw = exportPublicKey(privateRef)
                ?: error("Failed to export public key from Apple Keychain")
            userDefaults.setObject(raw.toBase64String(), DEVICE_PUBLIC_KEY)
            userDefaults.synchronize()
            TrustCrypto.ECDSAPublicKey(raw)
        } finally {
            CFRelease(privateRef)
        }
    }

    // ---- Keychain helpers ----

    private fun findDeviceKey(): SecKeyRef? = memScoped {
        val query = NSMutableDictionary().apply {
            setObject(kSecClassKey, kSecClass!!)
            setObject(tagData, kSecAttrApplicationTag!!)
            setObject(kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeyType!!)
            setObject(kSecAttrKeyClassPrivate, kSecAttrKeyClass!!)
            setObject(kSecMatchLimitOne, kSecMatchLimit!!)
            setObject(NSNumber.numberWithBool(true), kSecReturnRef!!)
        }
        val out = alloc<CFTypeRefVar>()
        val status: OSStatus = SecItemCopyMatching(query.bridgeAsCFDictionary(), out.ptr)
        when (status) {
            errSecSuccess -> out.value?.reinterpret()
            errSecItemNotFound -> null
            else -> null
        }
    }

    private fun generateDeviceKey(): SecKeyRef? = memScoped {
        val privateAttrs = NSMutableDictionary().apply {
            setObject(NSNumber.numberWithBool(true), kSecAttrIsPermanent!!)
            setObject(tagData, kSecAttrApplicationTag!!)
            setObject(kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, kSecAttrAccessible!!)
        }
        val parameters = NSMutableDictionary().apply {
            setObject(kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeyType!!)
            setObject(NSNumber.numberWithInt(P256_KEY_SIZE_BITS), kSecAttrKeySizeInBits!!)
            setObject(DEVICE_KEY_LABEL, kSecAttrLabel!!)
            setObject(privateAttrs, kSecPrivateKeyAttrs!!)
        }
        val errVar = alloc<CFErrorRefVar>()
        val key = SecKeyCreateRandomKey(parameters.bridgeAsCFDictionary(), errVar.ptr)
        if (key == null) errVar.value?.let { CFRelease(it) }
        key
    }

    private fun deleteDeviceKey() {
        memScoped {
            val query = NSMutableDictionary().apply {
                setObject(kSecClassKey, kSecClass!!)
                setObject(tagData, kSecAttrApplicationTag!!)
                setObject(kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeyType!!)
            }
            SecItemDelete(query.bridgeAsCFDictionary())
        }
    }

    private fun exportPublicKey(privateRef: SecKeyRef): ByteArray? {
        val publicRef = SecKeyCopyPublicKey(privateRef) ?: return null
        try {
            memScoped {
                val errVar = alloc<CFErrorRefVar>()
                val data = SecKeyCopyExternalRepresentation(publicRef, errVar.ptr)
                if (data == null) {
                    errVar.value?.let { CFRelease(it) }
                    return null
                }
                return (CFBridgingRelease(data) as NSData).toByteArray()
            }
        } finally {
            CFRelease(publicRef)
        }
    }

    private fun readBase64(key: String): ByteArray? {
        val encoded = userDefaults.stringForKey(key) ?: return null
        return encoded.fromBase64OrNull().also { decoded ->
            if (decoded == null) {
                userDefaults.removeObjectForKey(key)
                userDefaults.synchronize()
            }
        }
    }
}

// ---- NSData / CFDictionary helpers ----

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData.dataWithBytes(null, 0u)
    return memScoped {
        val pinned = allocArrayOf(this@toNSData)
        NSData.create(bytes = pinned, length = this@toNSData.size.toULong())
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    val src = bytes ?: return out
    val srcBytes = src.reinterpret<ByteVar>()
    for (i in 0 until len) {
        out[i] = srcBytes[i]
    }
    return out
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun NSDictionary.bridgeAsCFDictionary(): CFDictionaryRef =
    CFBridgingRetain(this)?.reinterpret() ?: error("Failed to bridge NSDictionary to CFDictionaryRef")
