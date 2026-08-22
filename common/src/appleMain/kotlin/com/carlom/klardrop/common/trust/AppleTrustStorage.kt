package com.carlom.klardrop.common.trust

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.NSUserDefaults
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyRef
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
import platform.Security.kSecUseDataProtectionKeychain
import platform.darwin.OSStatus

/**
 * Apple-platform implementation of [TrustStorage].
 *
 * Peer ECDH/ECDSA public keys are stored in NSUserDefaults (not secret).
 * The device's own ECDSA P-256 private key lives in the Keychain as a
 * permanent, non-exportable item identified by `DEVICE_KEY_TAG`. Signing
 * happens inside the Keychain via [signWithDeviceKey] and the private bytes
 * never leave the secure store.
 *
 * The Keychain item uses `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`
 * so the key is unavailable before first unlock after a reboot, never
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
            val cfPayload = data.toCFData()
            try {
                memScoped {
                    val errVar = alloc<CFErrorRefVar>()
                    val derRef = SecKeyCreateSignature(
                        privateRef,
                        kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
                        cfPayload,
                        errVar.ptr
                    )
                    if (derRef == null) {
                        errVar.value?.let { CFRelease(it) }
                        return@memScoped null
                    }
                    try {
                        val derBytes = derRef.toByteArray()
                        try {
                            EcdsaSignatureFormat.derToRaw(derBytes)
                        } catch (_: Exception) {
                            null
                        }
                    } finally {
                        CFRelease(derRef)
                    }
                }
            } finally {
                CFRelease(cfPayload)
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

    // Each operation tries the data-protection keychain first (entitlement-gated, no interactive
    // ACL prompt — same as iOS) and falls back to the legacy file keychain. The fallback keeps
    // unsigned/ad-hoc builds working (the data-protection keychain returns errSecMissingEntitlement
    // without a real team-prefixed access group) instead of failing key generation at startup.

    private fun findDeviceKey(): SecKeyRef? =
        findDeviceKey(dataProtection = true) ?: findDeviceKey(dataProtection = false)

    private fun findDeviceKey(dataProtection: Boolean): SecKeyRef? = memScoped {
        val query = newCfDict {
            set(kSecClass, kSecClassKey)
            set(kSecAttrApplicationTag, deviceKeyTag())
            set(kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
            set(kSecAttrKeyClass, kSecAttrKeyClassPrivate)
            set(kSecMatchLimit, kSecMatchLimitOne)
            set(kSecReturnRef, kCFBooleanTrue)
            if (dataProtection) set(kSecUseDataProtectionKeychain, kCFBooleanTrue)
        }
        try {
            val out = alloc<CFTypeRefVar>()
            val status: OSStatus = SecItemCopyMatching(query, out.ptr)
            if (status == errSecSuccess) out.value?.reinterpret() else null
        } finally {
            CFRelease(query)
            // The CFData passed via kSecAttrApplicationTag was owned by us; CFRelease
            // is handled by the dict's value release callback (kCFTypeDictionaryValueCallBacks).
        }
    }

    private fun generateDeviceKey(): SecKeyRef? =
        generateDeviceKey(dataProtection = true) ?: generateDeviceKey(dataProtection = false)

    private fun generateDeviceKey(dataProtection: Boolean): SecKeyRef? = memScoped {
        val privateAttrs = newCfDict {
            set(kSecAttrIsPermanent, kCFBooleanTrue)
            set(kSecAttrApplicationTag, deviceKeyTag())
            set(kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            if (dataProtection) set(kSecUseDataProtectionKeychain, kCFBooleanTrue)
        }
        val parameters = newCfDict {
            set(kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
            set(kSecAttrKeySizeInBits, P256_KEY_SIZE_BITS.toCFNumber())
            set(kSecAttrLabel, DEVICE_KEY_LABEL.toCFString())
            if (dataProtection) set(kSecUseDataProtectionKeychain, kCFBooleanTrue)
            set(kSecPrivateKeyAttrs, privateAttrs)
        }
        try {
            val errVar = alloc<CFErrorRefVar>()
            val key = SecKeyCreateRandomKey(parameters, errVar.ptr)
            if (key == null) errVar.value?.let { CFRelease(it) }
            key
        } finally {
            CFRelease(parameters)
            CFRelease(privateAttrs)
        }
    }

    private fun deleteDeviceKey() {
        deleteDeviceKey(dataProtection = true)
        deleteDeviceKey(dataProtection = false)
    }

    private fun deleteDeviceKey(dataProtection: Boolean) {
        val query = newCfDict {
            set(kSecClass, kSecClassKey)
            set(kSecAttrApplicationTag, deviceKeyTag())
            set(kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
            if (dataProtection) set(kSecUseDataProtectionKeychain, kCFBooleanTrue)
        }
        try {
            SecItemDelete(query)
        } finally {
            CFRelease(query)
        }
    }

    private fun exportPublicKey(privateRef: SecKeyRef): ByteArray? = memScoped {
        val publicRef = SecKeyCopyPublicKey(privateRef) ?: return@memScoped null
        try {
            val errVar = alloc<CFErrorRefVar>()
            val data = SecKeyCopyExternalRepresentation(publicRef, errVar.ptr)
            if (data == null) {
                errVar.value?.let { CFRelease(it) }
                return@memScoped null
            }
            try {
                data.toByteArray()
            } finally {
                CFRelease(data)
            }
        } finally {
            CFRelease(publicRef)
        }
    }

    private fun deviceKeyTag(): CFDataRef = DEVICE_KEY_TAG.encodeToByteArray().toCFData()

    private fun readBase64(key: String): ByteArray? {
        val encoded = userDefaults.stringForKey(key) ?: return null
        return encoded.fromBase64OrNull().also { decoded ->
            if (decoded == null) {
                userDefaults.removeObjectForKey(key)
                userDefaults.synchronize()
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun ByteArray.toBase64String(): String = Base64.encode(this)

    @OptIn(ExperimentalEncodingApi::class)
    private fun String.fromBase64OrNull(): ByteArray? =
        runCatching { Base64.decode(this) }.getOrNull()

}

// ---- CoreFoundation helpers ----

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toCFData(): CFDataRef {
    if (isEmpty()) {
        return CFDataCreate(null, null, 0)!!
    }
    return memScoped {
        val pinned = allocArrayOf(this@toCFData)
        CFDataCreate(null, pinned.reinterpret(), size.toLong())!!
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    if (length == 0) return ByteArray(0)
    val src: CPointer<UByteVar> = CFDataGetBytePtr(this) ?: return ByteArray(0)
    val out = ByteArray(length)
    val byteSrc: CPointer<kotlinx.cinterop.ByteVar> = src.reinterpret()
    out.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), byteSrc, length.toULong())
    }
    return out
}

@OptIn(ExperimentalForeignApi::class)
private fun Int.toCFNumber(): platform.CoreFoundation.CFNumberRef = memScoped {
    val intVar = alloc<kotlinx.cinterop.IntVar>()
    intVar.value = this@toCFNumber
    platform.CoreFoundation.CFNumberCreate(
        null,
        platform.CoreFoundation.kCFNumberIntType,
        intVar.ptr
    )!!
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun String.toCFString(): platform.CoreFoundation.CFStringRef =
    platform.CoreFoundation.CFStringCreateWithCString(
        null,
        this,
        platform.CoreFoundation.kCFStringEncodingUTF8
    )!!

@OptIn(ExperimentalForeignApi::class)
private inline fun newCfDict(builder: CfDictBuilder.() -> Unit): platform.CoreFoundation.CFMutableDictionaryRef {
    val dict = CFDictionaryCreateMutable(
        null,
        0,
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr
    )!!
    CfDictBuilder(dict).builder()
    return dict
}

@OptIn(ExperimentalForeignApi::class)
private class CfDictBuilder(private val dict: platform.CoreFoundation.CFMutableDictionaryRef) {
    fun set(key: kotlinx.cinterop.COpaquePointer?, value: kotlinx.cinterop.COpaquePointer?) {
        CFDictionarySetValue(dict, key, value)
    }
}
