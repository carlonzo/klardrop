package com.carlom.klardrop.common.trust

/**
 * Interface for storing and retrieving trusted device public keys.
 * Platform-specific implementations handle secure storage (SharedPreferences, Keychain, etc.).
 *
 * Stores both ECDH keys (for key exchange) and ECDSA keys (for message signing).
 */
interface TrustStorage {

    /**
     * Store a trusted device's ECDH public key.
     * @param deviceId Unique device identifier
     * @param publicKey ECDH public key for key exchange
     */
    suspend fun storeTrustedDevice(deviceId: String, publicKey: ByteArray)

    /**
     * Store a trusted device's ECDSA public key for message signing.
     * @param deviceId Unique device identifier
     * @param ecdsaPublicKey ECDSA public key for signature verification
     */
    suspend fun storeECDSAKey(deviceId: String, ecdsaPublicKey: ByteArray)

    /**
     * Retrieve a trusted device's ECDH public key.
     * @param deviceId Device identifier
     * @return ECDH public key bytes, or null if device is not trusted
     */
    suspend fun getTrustedDeviceKey(deviceId: String): ByteArray?

    /**
     * Retrieve a trusted device's ECDSA public key for signature verification.
     * @param deviceId Device identifier
     * @return ECDSA public key bytes, or null if not stored
     */
    suspend fun getECDSAKey(deviceId: String): ByteArray?

    /**
     * Get all trusted devices and their public keys.
     * @return Map of device ID to public key
     */
    suspend fun getAllTrustedDevices(): Map<String, ByteArray>

    /**
     * Remove a device from the trusted list.
     * @param deviceId Device to untrust
     */
    suspend fun removeTrustedDevice(deviceId: String)

    /**
     * Clear all trusted devices.
     * Used for reset functionality or security cleanup.
     */
    suspend fun clearAllTrustedDevices()

    /**
     * Check if a device is trusted.
     * @param deviceId Device identifier
     * @return true if device is in trusted list
     */
    suspend fun isTrusted(deviceId: String): Boolean {
        return getTrustedDeviceKey(deviceId) != null
    }

    // Device Identity Persistence Methods
    //
    // SECURITY CONTRACT — read before changing implementations:
    //
    // The device's own ECDSA private key MUST be persisted in a platform-secure store
    // (Android Keystore, Apple Keychain / Secure Enclave, OS-managed credential vault on
    // desktop). Storing it in plaintext-equivalent locations such as SharedPreferences,
    // NSUserDefaults, or a properties file is NOT acceptable: those are readable by any
    // process with disk access, are included in OS backups by default, and on rooted
    // devices are trivially exfiltrated. The private key never has to leave the secure
    // store — implementations should override [signWithDeviceKey] to sign in-place. The
    // [getDevicePrivateKey] / [storeDevicePrivateKey] byte-array path exists only for
    // tests and the desktop fallback path.
    //
    // The matching public key is NOT a secret and may live in any persistent storage.

    /**
     * Persist this device's own ECDSA private key.
     *
     * Implementations MUST place the bytes in a platform-secure store. Where the
     * platform exposes a non-exportable key handle (Android Keystore alias, Apple
     * Keychain `kSecAttrApplicationTag`), prefer to ignore the supplied bytes and
     * generate the key directly inside the secure store. The supplied bytes form
     * exists for the in-memory test fake and the desktop fallback path.
     */
    suspend fun storeDevicePrivateKey(privateKey: ByteArray)

    /**
     * Retrieve this device's ECDSA private key bytes.
     *
     * Returns `null` on platforms where the private key is held in a secure store
     * that does not export key material (Android Keystore, Secure Enclave). Such
     * platforms must override [signWithDeviceKey] so callers never need the bytes.
     * Use [hasDeviceKey] to check presence without materializing the bytes.
     */
    suspend fun getDevicePrivateKey(): ByteArray?

    /**
     * Persist this device's ECDSA public key for fast read-back during pairing
     * and message signing. The public key is not a secret; any persistent
     * platform store is acceptable.
     */
    suspend fun storeDevicePublicKey(publicKey: ByteArray)

    /**
     * Retrieve this device's ECDSA public key, or `null` if no identity has
     * been generated yet.
     */
    suspend fun getDevicePublicKey(): ByteArray?

    /**
     * Whether this device has a persisted identity key. Cheaper than
     * [getDevicePrivateKey] on platforms where the private key lives in a
     * secure store and cannot be exported. Default implementation infers
     * presence from [getDevicePrivateKey].
     */
    suspend fun hasDeviceKey(): Boolean {
        return getDevicePrivateKey() != null
    }

    /**
     * Delete this device's identity private key (and public key).
     * After this, the device generates a new identity on next initialize().
     */
    suspend fun deleteDevicePrivateKey()

    /**
     * Sign data with this device's persisted ECDSA private key.
     *
     * Default implementation loads the raw private key via [getDevicePrivateKey]
     * and signs through [TrustCrypto.signWithECDSA]. Platforms that hold the
     * private key in a secure store (Android Keystore, Apple Keychain) MUST
     * override this to sign inside the secure store, returning a signature in
     * the wire format used by [TrustCrypto.verifyECDSA] — 64-byte RAW (r‖s).
     *
     * @return signature bytes, or `null` if no identity is present.
     */
    suspend fun signWithDeviceKey(data: ByteArray, crypto: TrustCrypto): ByteArray? {
        val privateBytes = getDevicePrivateKey() ?: return null
        return crypto.signWithECDSA(TrustCrypto.ECDSAPrivateKey(privateBytes), data)
    }

    /**
     * Persist the ECDH shared secret derived during pairing with [deviceId]. Both peers
     * arrive at the same 32-byte secret without it ever appearing on the wire (only the
     * ECDH public keys are exchanged). The receive-path uses this secret as the input to
     * an HKDF that produces an HMAC key for fast per-chunk integrity checks on file
     * transfers — much cheaper than per-chunk ECDSA, since both halves of the pair share
     * the symmetric key. Default is no-op for forward compat with older test fakes.
     */
    suspend fun storeSharedSecret(deviceId: String, sharedSecret: ByteArray) {}

    /**
     * Retrieve the ECDH shared secret previously stored for [deviceId]. Null on legacy
     * pairings that pre-date this field — callers fall back to per-frame ECDSA signing.
     */
    suspend fun getSharedSecret(deviceId: String): ByteArray? = null

    /**
     * Ensure this device has an identity keypair, generating one if none exists,
     * and return the public key.
     *
     * Default implementation generates a fresh keypair through [crypto] and
     * persists both halves via [storeDevicePrivateKey] / [storeDevicePublicKey].
     * Platforms backed by a secure store that generates keys in-place (Android
     * Keystore, Apple Keychain) override this to skip the byte-array round trip
     * and read the public key out of the secure store instead.
     *
     * The private and public halves can live in *different* stores with different
     * lifetimes — on desktop the private key is in the OS keychain (global, survives
     * an app-data wipe) while the public key is a properties file under the app dir.
     * If those desync (a storage migration, a partial data clear, a keychain entry
     * left over from a previous identity) we would otherwise advertise one public key
     * at pairing time while signing with a non-matching private key: every peer then
     * rejects our signatures permanently, and re-pairing can't recover because we keep
     * handing back the same broken pair. So before reusing a stored pair we confirm the
     * two halves actually match; on mismatch (or a missing half) we regenerate both.
     */
    suspend fun ensureDeviceKey(crypto: TrustCrypto): TrustCrypto.ECDSAPublicKey {
        val storedPublic = getDevicePublicKey()
        if (hasDeviceKey() && storedPublic != null) {
            // getDevicePrivateKey() returns null on secure-store platforms that don't
            // export private bytes — but those override ensureDeviceKey(), so here a null
            // means "no usable private key" and we fall through to regenerate. When bytes
            // are present, prove the halves are a pair before trusting them.
            val storedPrivate = getDevicePrivateKey()
            if (storedPrivate != null && ecdsaKeyHalvesMatch(crypto, storedPrivate, storedPublic)) {
                return TrustCrypto.ECDSAPublicKey(storedPublic)
            }
        }
        val fresh = crypto.generateECDSAKeyPair()
        storeDevicePrivateKey(fresh.privateKey.data)
        storeDevicePublicKey(fresh.publicKey.data)
        return fresh.publicKey
    }
}

/**
 * Fixed probe signed with [privateKey] and verified against [publicKey] to confirm the two
 * are the same P-256 keypair. Cheap (one sign + one verify, once per app launch) and self-
 * contained — no dedicated "derive public from private" API needed across platforms. Any
 * exception (corrupt bytes, wrong length) is treated as "don't match" so the caller regenerates.
 */
private suspend fun ecdsaKeyHalvesMatch(
    crypto: TrustCrypto,
    privateKey: ByteArray,
    publicKey: ByteArray,
): Boolean = runCatching {
    val probe = "klardrop-identity-keypair-probe".encodeToByteArray()
    val signature = crypto.signWithECDSA(TrustCrypto.ECDSAPrivateKey(privateKey), probe)
    crypto.verifyECDSA(publicKey, probe, signature)
}.getOrDefault(false)
